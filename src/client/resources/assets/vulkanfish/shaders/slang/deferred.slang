// deferred.slang – Compute: Beleuchtung aus dem G-Buffer (1 Thread/Pixel) in
// ein HDR-Bild. Sonne/Mond mit weichen Schatten, Himmels-Ambient, Blocklicht,
// Emission, Himmel + Fog. Tonemap folgt in final.slang (nach dem Bloom).
#include "gpu_scene.slang"
#include "look.slang"
#include "shadow.slang"

[[vk::binding(0, 0)]] Texture2D<float4> gAlbedoMat;    // Albedo (Gamma) | Material+Emission
[[vk::binding(1, 0)]] Texture2D<float4> gNormalLight;  // Oktaeder-Normale | Block | Himmel
[[vk::binding(2, 0)]] Texture2D<float> gDepth;         // Reverse-Z
[[vk::binding(3, 0)]] Texture2D<float> gShadowMap;     // Lichtraum-Tiefe (verzerrt)
[[vk::binding(4, 0)]] SamplerComparisonState gShadowCmp; // Hardware-PCF (LESS_OR_EQUAL)
[[vk::binding(5, 0)]] Texture2D<float4> gBackground;   // Vanillas Himmel (nur Nether/End)
[[vk::binding(6, 0)]] [format("rgba16f")] RWTexture2D<float4> gOut;
[[vk::binding(7, 0)]] ConstantBuffer<FrameUniforms> gFrame;
[[vk::binding(8, 0)]] SamplerState gLinear;

#if VF_RT
// ---------- Raytracing-Blocklicht (Variante deferredMainRt, nur mit Ray Queries) ----------
// Pro Pixel: Lichter der eigenen Gitterzelle bewerten, fuer die staerksten RT_RAYS einen
// Schattenstrahl (Ray Query gegen die Terrain-TLAS) zu einem zufaelligen Punkt der Licht-
// quelle schicken (weiche Schatten, TAA mittelt). Helligkeit bleibt an Vanillas Blocklicht-
// Stufe gekoppelt (die ist Flutfuellung = gutes indirektes Licht); Raytracing liefert die
// Richtung, die Verdeckung und die Farbe der Lichter.
#include "rt_common.slang"
[[vk::binding(9, 0)]] RaytracingAccelerationStructure gTlas;
[[vk::binding(10, 0)]] StructuredBuffer<RtLight> gLights;
[[vk::binding(11, 0)]] StructuredBuffer<uint> gCellCount;
[[vk::binding(12, 0)]] StructuredBuffer<uint> gCellLights;
[[vk::binding(13, 0)]] StructuredBuffer<TerrainVertex> gVerts;  // Alpha-Test fuer Cutout-Geometrie
[[vk::binding(14, 0)]] Texture2D<float4> gAtlas;
[[vk::binding(15, 0)]] SamplerState gAtlasSamp;

static const uint RT_RAYS = 4;        // Schattenstrahlen pro Pixel (staerkste Lichter)
static const float RT_FILL = 0.25;    // Anteil, der als indirektes Licht auch im Schatten bleibt
static const uint GEOM_QUADS = 65536; // RtAccel.GEOM_QUADS

bool shadowRayVisible(float3 origin, float3 dir, float tmax)
{
    RayDesc ray;
    ray.Origin = origin;
    ray.Direction = dir;
    ray.TMin = 0.0;
    ray.TMax = tmax;
    RayQuery<RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH> q;
    q.TraceRayInline(gTlas, RAY_FLAG_NONE, 0xFF, ray);
    while (q.Proceed())
    {
        // Nur Cutout-Geometrie (Blaetter, Gitter ...) meldet Kandidaten: Textur-Alpha pruefen
        if (q.CandidateType() == CANDIDATE_NON_OPAQUE_TRIANGLE)
        {
            uint prim = q.CandidatePrimitiveIndex();
            uint quad = q.CandidateInstanceID() + q.CandidateGeometryIndex() * GEOM_QUADS + (prim >> 1);
            float2 b = q.CandidateTriangleBarycentrics();
            uint v0 = quad * 4;
            uint v1 = v0 + 1 + (prim & 1); // Dreiecke (0,1,2) und (0,2,3)
            float2 uv = tvUv(gVerts[v0]) * (1.0 - b.x - b.y) + tvUv(gVerts[v1]) * b.x + tvUv(gVerts[v1 + 1]) * b.y;
            if (gAtlas.SampleLevel(gAtlasSamp, uv, 0).a >= 0.5)
                q.CommitNonOpaqueTriangleHit();
        }
    }
    return q.CommittedStatus() == COMMITTED_NOTHING;
}

float interleavedNoise(float2 p)
{
    return frac(52.9829189 * frac(dot(p, float2(0.06711056, 0.00583715))));
}

// Gleichverteilter Punkt in der Einheitskugel aus zwei Zufallszahlen + Radius-Zufall
float3 spherePoint(float u, float v, float w)
{
    float z = u * 2.0 - 1.0;
    float a = v * 2.0 * PI;
    float r = sqrt(max(1.0 - z * z, 0.0));
    return float3(r * cos(a), z, r * sin(a)) * pow(w, 1.0 / 3.0);
}

// valid = false -> kein Licht im Gitter bekannt, Vanilla-Blocklicht verwenden
float3 rtBlockLight(FrameUniforms f, float3 wp, float3 n, float blockL, bool foliage, int2 px, out bool valid, out float visOut)
{
    valid = false;
    visOut = 1.0;
    int3 c = int3(floor(wp / float(LIGHT_CELL))) - f.lightGrid.xyz;
    if (any(c < 0) || any(c >= LIGHT_GRID))
        return float3(0.0);
    uint cell = uint((c.z * LIGHT_GRID + c.y) * LIGHT_GRID + c.x);
    uint count = min(gCellCount[cell], LIGHT_CAP);
    if (count == 0)
        return float3(0.0);

    // Bewerten: unverschattete Staerke (S, Farbe Cun) + staerkste RT_RAYS nach Staerke*N.L
    float S = 0.0;
    float3 Cun = float3(0.0);
    uint selIdx[RT_RAYS];
    float selW[RT_RAYS];
    uint selN = 0;
    float restW = 0.0;
    float3 restC = float3(0.0);
    for (uint i = 0; i < count; i++)
    {
        uint li = gCellLights[cell * LIGHT_CAP + i];
        RtLight l = gLights[li];
        float3 d3 = l.pos - wp;
        float d = length(d3);
        float a = rtLightFalloff(d, rtLightEmission(l.packed));
        if (a <= 0.0)
            continue;
        float3 col = rtLightColor(l.packed);
        // Weiches N.L (Wrap): streifend beleuchteter Boden bleibt hell wie bei Vanilla, Rueckseiten dunkel
        float nl = foliage ? 0.65 : saturate((dot(n, d3 / max(d, 1e-4)) + 0.3) / 1.3);
        S += a;
        Cun += a * col;
        float w = a * nl;
        if (w <= 1e-5)
            continue;
        if (selN < RT_RAYS)
        {
            selIdx[selN] = li;
            selW[selN] = w;
            selN++;
            continue;
        }
        uint m = 0; // schwaechste Auswahl ersetzen, sie zaehlt dann unverschattet
        for (uint k = 1; k < RT_RAYS; k++)
            if (selW[k] < selW[m]) m = k;
        if (w > selW[m])
        {
            restW += selW[m];
            restC += selW[m] * rtLightColor(gLights[selIdx[m]].packed);
            selIdx[m] = li;
            selW[m] = w;
        }
        else
        {
            restW += w;
            restC += w * col;
        }
    }
    if (S <= 1e-5)
        return float3(0.0);

    // Schattenstrahlen
    float D = restW;
    float3 C = restC;
    float noise = interleavedNoise(float2(px) + float(f.frameIndex % 64u) * 5.588238);
    for (uint k = 0; k < selN; k++)
    {
        RtLight l = gLights[selIdx[k]];
        float r1 = frac(noise + float(k) * 0.618034);
        float r2 = frac(noise * 7.13 + float(f.frameIndex) * 0.7548777 + float(k) * 0.3819660);
        float r3 = frac(noise * 3.71 + float(f.frameIndex) * 0.5698403);
        float3 target = l.pos + spherePoint(r1, r2, r3) * rtLightRadius(l.packed);
        float3 toL = target - wp;
        float3 origin = wp + (foliage ? normalize(toL) : n) * 0.03;
        float3 dir = target - origin;
        float t = length(dir);
        if (t < 0.05 || shadowRayVisible(origin, dir / t, t - 0.03))
        {
            D += selW[k];
            C += selW[k] * rtLightColor(l.packed);
        }
    }
    valid = true;
    float vis = saturate(D / S * 1.6);
    visOut = vis;
    float3 fill = Cun / S;
    float3 tint = D > 1e-5 ? C / D : fill;
    // Am Gitterrand weich auf Vanilla ueberblenden
    float3 g0 = float3(f.lightGrid.xyz * LIGHT_CELL);
    float3 e = min(wp - g0, g0 + float(LIGHT_GRID * LIGHT_CELL) - wp);
    float edge = saturate((min(e.x, min(e.y, e.z)) - 2.0) / 12.0);
    float3 rt = fill * RT_FILL + tint * vis * (1.0 - RT_FILL);
    return blockLightLevel(blockL) * lerp(TORCH_COLOR, rt, edge);
}
#endif

[shader("compute")]
[NumThreads(8, 8, 1)]
#if VF_RT
void deferredMainRt(in uint3 tid : SV_DispatchThreadID)
#else
void deferredMain(in uint3 tid : SV_DispatchThreadID)
#endif
{
    FrameUniforms f = gFrame;
    if (tid.x >= uint(f.screenSize.x) || tid.y >= uint(f.screenSize.y))
        return;
    int2 px = int2(tid.xy);
    float depth = gDepth.Load(int3(px, 0));
    float2 uv = (float2(px) + 0.5) / f.screenSize;
    float4 ndc = float4(uv * 2.0 - 1.0, max(depth, 1e-7), 1.0);
    float4 wp4 = mul(f.invViewProj, ndc);
    float3 wp = wp4.xyz / wp4.w;
    float3 dir = normalize(wp - f.camPos);

    if (depth <= 0.0)
    {
        // Himmel: Overworld eigener, sonst Vanillas (Nether-Nebel, End-Himmel)
        float3 sky = f.dimension == 0 ? skyRadiance(f, dir, true) : toLinear(gBackground.Load(int3(px, 0)).rgb);
        if (f.fogType != 0)
            sky = applyFog(f, sky, dir, 64.0, sky);
        gOut[px] = float4(sky, 1.0);
        return;
    }

    float4 g0 = gAlbedoMat.Load(int3(px, 0));
    float4 g1 = gNormalLight.Load(int3(px, 0));
    float3 albedo = toLinear(g0.rgb);
    uint packed = uint(g0.a * 255.0 + 0.5);
    uint mat = packed & 0xF;
    float emission = float(packed >> 4) / 15.0;
    float3 n = octDecode(g1.xy);
    float blockL = g1.z;
    float skyL = g1.w;
    bool foliage = isFoliage(mat);

    // Direktes Licht (Sonne/Mond): Schatten innerhalb der Distanz, sonst Himmelslicht als Proxy.
    // |lightDir| = Staerke (blendet am Horizont aus, Sonne<->Mond-Wechsel ohne Sprung)
    float lightFade = length(f.lightDir);
    float3 L = f.lightDir / max(lightFade, 1e-4);
    float ndl = dot(n, L);
    float diffuse = (foliage ? saturate(ndl) * 0.55 + 0.45 : saturate(ndl)) * lightFade; // Laub laesst Licht durch
    float shadow = 1.0;
    float skyGate = smoothstep(0.35, 0.95, skyL);
    if (f.dimension == 0 && diffuse > 0.0)
    {
        if (f.shadowDistance > 0.0 && length(wp - f.camPos) < f.shadowDistance)
            shadow = sampleShadowMap(gShadowMap, gShadowCmp, f, wp, foliage ? L : n, float2(px)) * lerp(skyGate, 1.0, 0.75);
        else
            shadow = skyGate;
    }
    float3 direct = f.dimension == 0 ? directColor(f) * diffuse * shadow : float3(0.0);

    // Himmels-Ambient (quadratisch mit Himmelslicht, wie es sich in Hoehlen verliert)
    float3 ambient = f.dimension == 0 ? ambientColor(f, n) * (skyL * skyL)
                   : (f.dimension == 1 ? float3(0.14, 0.07, 0.05) : float3(0.09, 0.08, 0.12));
    float3 blockLight = blockLightColor(blockL);
#if VF_RT
    // Leuchtende Bloecke selbst (Glowstone, Fackel) bleiben bei Vanilla: ihr Licht kommt von innen
    if ((f.rtFlags & 1u) != 0 && blockL > 0.0 && emission == 0.0)
    {
        bool valid;
        float vis;
        float3 rt = rtBlockLight(f, wp, n, blockL, foliage, px, valid, vis);
        if (valid)
            blockLight = rt;
        if (f.debugView == 9) // Debug: RT-Sichtbarkeit (weiss = beleuchtet), rot = kein Licht im Gitter
        {
            gOut[px] = float4(valid ? toLinear(float3(vis)) : float3(0.3, 0.0, 0.0), 1.0);
            return;
        }
    }
#endif
    float3 lightSum = direct + ambient + blockLight + MIN_LIGHT;
    float3 c = albedo * lightSum;
    if (f.debugView != 0 && f.debugView <= 4) // 5+ sind Wasser-Ansichten
    {
        float3 dbg = f.debugView == 1 ? float3(shadow) : f.debugView == 2 ? n * 0.5 + 0.5
                   : f.debugView == 3 ? g0.rgb : toGamma(lightSum * 0.25);
        gOut[px] = float4(toLinear(dbg), 1.0);
        return;
    }

    // Emission: helle Pixel leuchtender Bloecke (Fackel-Flamme, Glowstone), Lava komplett
    if (emission > 0.0)
    {
        float mask = mat == MAT_LAVA ? 1.0 : smoothstep(0.35, 0.8, luma(g0.rgb));
        c += albedo * emission * mask * 3.5;
    }
    // Erze: gesaettigte Pixel (die Erz-Sprenkel) gluehen dezent
    if (mat == MAT_ORE)
    {
        float sat = max(g0.r, max(g0.g, g0.b)) - min(g0.r, min(g0.g, g0.b));
        c += albedo * smoothstep(0.22, 0.45, sat) * 1.6;
    }

    float dist = length(wp - f.camPos);
    float3 skyCol = f.dimension == 0 ? skyRadiance(f, dir, false) : toLinear(f.fogColor);
    c = applyFog(f, c, dir, dist, skyCol);
    gOut[px] = float4(c, 1.0);
}
