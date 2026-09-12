// water.slang – Wasser (eigene Implementierung): laeuft NACH Vanillas Entities
// direkt ins Main-Target (Gamma, RGBA8) gegen dessen Tiefe. Liest eine Kopie
// von Farbe/Tiefe fuer Brechung + Screen-Space-Reflexionen.
//  - Wellen: ueberlagerte Wellenzuege (Tiefwasser-Dispersion) als Normale
//  - Koerper: Brechung + farbabhaengige Absorption je nach Wassertiefe,
//    getoent mit Vanillas Biom-Wasserfarbe, Streulicht aus Himmel/Sonne
//  - Oberflaeche: Fresnel-Mix mit SSR (Himmel als Rueckfall), Sonnenglanz
//  - Kaustiken auf dem Grund, Snell-Fenster wenn die Kamera unter Wasser ist
#include "terrain_common.slang"
#include "shadow.slang"

[[vk::binding(4, 0)]] Texture2D<float4> gSceneColor;  // Kopie des Main-Targets (Gamma)
[[vk::binding(5, 0)]] SamplerState gLinear;
[[vk::binding(7, 0)]] Texture2D<float> gSceneDepth;   // Kopie der Main-Tiefe (Terrain + Entities)
[[vk::binding(8, 0)]] Texture2D<float> gShadowMap;
[[vk::binding(9, 0)]] SamplerComparisonState gShadowCmp;
[[vk::binding(10, 0)]] Texture2D<float4> gAtlas;       // Block-Atlas (Glas/Eis)
[[vk::binding(11, 0)]] SamplerState gAtlasSamp;

struct WaterVertOut
{
    float4 pos : SV_Position;
    float3 worldPos;
    float3 faceNormal;
    float4 tint;    // Vanillas Wasserfarbe (Biom), Gamma
    float2 light;   // Block, Himmel
};

[shader("mesh")]
[NumThreads(64, 1, 1)]
[outputtopology("triangle")]
void waterMesh(
    in uint3 gid : SV_GroupID,
    in uint3 ltid : SV_GroupThreadID,
    out vertices WaterVertOut verts[64],
    out indices uint3 tris[124])
{
    uint lt = ltid.x;
    MeshletHeader mh = gMeshlets[gVisibleMeshlets[gid.x]];
    FrameUniforms f = gFrame;
    uint vCount = min(mh.vertexCount, 64);
    uint tCount = min(mh.triCount, 124);
    SetMeshOutputCounts(vCount, tCount);
    computeQuadNormals(mh, lt, vCount);
    if (lt < vCount)
    {
        TerrainVertex tv;
        float3 wp, n;
        loadVertex(mh, lt, f, tv, wp, n);
        verts[lt].pos = mul(f.viewProj, float4(wp, 1.0));
        verts[lt].worldPos = wp;
        verts[lt].faceNormal = n;
        verts[lt].tint = tvColor(tv);
        verts[lt].light = tvLight(tv);
    }
    for (uint t = lt; t < tCount; t += MESH_THREADS)
        tris[t] = unpackTri(gTris[mh.indexStart + t]);
}

// ---------- Wellen ----------
// Hoehenfeld = wenige Wellenzuege (Tiefwasser-Dispersion) + animiertes Rauschen in
// zwei Oktaven gegen sichtbare Periodik. Normale aus der analytischen Ableitung.
static const float4 WAVES[4] = {
    float4(0.87, 0.50, 0.45, 0.085), float4(-0.42, 0.91, 0.80, 0.050),
    float4(0.21, -0.98, 1.55, 0.026), float4(-0.95, -0.31, 2.90, 0.012)
};

float hash12(float2 p)
{
    float3 p3 = frac(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return frac((p3.x + p3.y) * p3.z);
}

// Ableitung von valueNoise nach p
float2 valueNoiseGrad(float2 p)
{
    float2 i = floor(p);
    float2 fr = frac(p);
    float2 u = fr * fr * (3.0 - 2.0 * fr);
    float2 du = 6.0 * fr * (1.0 - fr);
    float a = hash12(i), b = hash12(i + float2(1, 0)), c = hash12(i + float2(0, 1)), d = hash12(i + float2(1, 1));
    float k = a - b - c + d;
    return du * float2((b - a) + k * u.y, (c - a) + k * u.x);
}

// Gradient von waveHeight (dieselben Terme, abgeleitet)
float2 waveGradient(float2 p, float t, float rain)
{
    float2 g = float2(0.0);
    for (uint i = 0; i < 4; i++)
    {
        float2 d = WAVES[i].xy;
        float k = WAVES[i].z;
        g += d * (WAVES[i].w * k * cos(dot(d, p) * k + t * sqrt(9.81 * k) * 0.55));
    }
    g += valueNoiseGrad(p * 0.55 + float2(t * 0.35, t * 0.12)) * (0.55 * 0.16);
    g += valueNoiseGrad(p * 1.6 + float2(-t * 0.45, t * 0.3)) * (1.6 * (0.06 + rain * 0.08));
    return g;
}

float causticPattern(float2 p, float t)
{
    float2 q = p * 0.9;
    float v = 0.0;
    for (uint i = 0; i < 3; i++)
    {
        q += float2(sin(q.y * 1.7 + t * 0.9), cos(q.x * 1.4 - t * 0.8)) * 0.45;
        v += abs(sin(q.x) + sin(q.y));
    }
    return pow(saturate(1.25 - v * 0.28), 3.0) * 2.5;
}

// ---------- Hilfen ----------
float3 reconstruct(FrameUniforms f, float2 uv, float depth)
{
    float4 p = mul(f.invViewProj, float4(uv * 2.0 - 1.0, max(depth, 1e-7), 1.0));
    return p.xyz / p.w;
}

// HDR -> gleiche Kurve wie das Terrain, zurueck in display-linearen Raum zum Mischen
float3 display(float3 hdr, FrameUniforms f)
{
    return toLinear(tonemap(hdr, f.exposure));
}

// Screen-Space-Reflexion: Weltraum-Marsch mit wachsender Schrittweite, binaere Verfeinerung
bool traceReflection(FrameUniforms f, float3 wp, float3 R, out float3 color, out float fade)
{
    color = float3(0.0);
    fade = 0.0;
    float t = 0.35;
    float prevT = 0.0;
    // Clip-Koordinaten sind entlang des Strahls linear: c(t) = c0 + t * cR
    float4 c0 = mul(f.viewProj, float4(wp, 1.0));
    float4 cR = mul(f.viewProj, float4(R, 0.0));
    for (uint i = 0; i < 28; i++)
    {
        float3 p = wp + R * t;
        float4 c = c0 + cR * t;
        if (c.w <= 0.05)
            return false;
        float3 ndc = c.xyz / c.w;
        float2 uv = ndc.xy * 0.5 + 0.5;
        if (any(uv < 0.0) || any(uv > 1.0))
            return false;
        float sd = gSceneDepth.Load(int3(int2(uv * f.screenSize), 0));
        if (sd > ndc.z) // Szene naeher als der Strahlpunkt -> Strahl ist dahinter
        {
            float3 sp = reconstruct(f, uv, sd);
            if (distance(f.camPos, p) - distance(f.camPos, sp) > max(t * 0.4, 1.5))
                return false; // hinter duennem Objekt vorbei -> keine gueltige Reflexion
            float lo = prevT, hi = t;
            for (uint j = 0; j < 5; j++)
            {
                float mid = (lo + hi) * 0.5;
                float4 cm = c0 + cR * mid;
                float3 nm = cm.xyz / cm.w;
                float2 um = nm.xy * 0.5 + 0.5;
                if (gSceneDepth.Load(int3(int2(saturate(um) * (f.screenSize - 1.0)), 0)) > nm.z) hi = mid;
                else lo = mid;
            }
            float4 ch = c0 + cR * hi;
            float2 uh = (ch.xy / ch.w) * 0.5 + 0.5;
            color = toLinear(gSceneColor.SampleLevel(gLinear, uh, 0).rgb);
            float2 edge = min(uh, 1.0 - uh);
            fade = saturate(min(edge.x, edge.y) * 12.0) * saturate(1.0 - float(i) / 28.0 * 0.5);
            return true;
        }
        prevT = t;
        t = t * 1.32 + 0.15;
    }
    return false;
}

[shader("fragment")]
float4 waterFrag(WaterVertOut input) : SV_Target0
{
    FrameUniforms f = gFrame;
    if (f.debugView == 5)
        return float4(1.0, 0.0, 1.0, 1.0); // Debug: Wasserflaechen magenta
    float2 suv = input.pos.xy / f.screenSize;
    int2 px = int2(input.pos.xy);
    float3 wp = input.worldPos;
    float3 V = normalize(wp - f.camPos);          // Kamera -> Punkt
    float dist = length(wp - f.camPos);
    float3 faceN = normalize(input.faceNormal);
    // Oben/unten an der KAMERA festmachen, nicht an der Windung: Vanillas Fluid-Renderer
    // liefert die Oberseite zusaetzlich als Rueckseite (umgekehrte Windung, gleiche Tiefe).
    bool top = abs(faceN.y) > 0.5;
    bool below = top ? f.camPos.y < wp.y : f.fogType == 1;
    float3 Nf = top ? float3(0.0, below ? -1.0 : 1.0, 0.0) : faceN * (dot(V, faceN) > 0.0 ? -1.0 : 1.0);

    // Wellen nur auf waagerechten Flaechen, in der Ferne flacher (kein Flimmern)
    float3 N = Nf;
    if (top)
    {
        float2 g = waveGradient(wp.xz, f.time, f.rainFactor) / (1.0 + dist * 0.06);
        N = normalize(float3(-g.x, 1.0, -g.y));
        if (below) N = -N;
    }

    float3 tintLin = toLinear(input.tint.rgb / max(max(input.tint.r, max(input.tint.g, input.tint.b)), 0.05));
    float skyL = input.light.y;
    float lightFade = length(f.lightDir);
    float3 L = f.lightDir / max(lightFade, 1e-4);
    float shadow = 1.0;
    if (f.dimension == 0 && f.shadowDistance > 0.0 && dist < f.shadowDistance)
        shadow = sampleShadowMap(gShadowMap, gShadowCmp, f, wp, Nf, float2(px));
    else
        shadow = smoothstep(0.35, 0.95, skyL);

    // ---------- Wasserkoerper: Brechung + Absorption ----------
    float sceneD = gSceneDepth.Load(int3(px, 0));
    float3 scenePos = reconstruct(f, suv, sceneD);
    float thickness = sceneD <= 0.0 ? 64.0 : max(distance(scenePos, wp), 0.0);
    if (f.debugView == 6)
        return float4(toGamma(float3(saturate(thickness / 8.0))), 1.0); // Debug: Wassertiefe 0..8 Bloecke
    float2 ruv = suv + N.xz * 0.035 * saturate(thickness * 0.5) / (1.0 + dist * 0.05);
    float rd = gSceneDepth.Load(int3(int2(saturate(ruv) * (f.screenSize - 1.0)), 0));
    if (rd > input.pos.z) // versetzter Punkt liegt VOR dem Wasser -> nicht brechen
        ruv = suv;
    float3 refr = toLinear(gSceneColor.SampleLevel(gLinear, ruv, 0).rgb);
    if (f.debugView == 7)
        return float4(toGamma(refr), 1.0);                            // Debug: gebrochene Szene
    if (f.debugView == 8)
        return float4(skyL, lightFade, shadow, 1.0);                  // Debug: Himmelslicht/Lichtstaerke/Schatten

    float3 color;
    if (!below)
    {
        // Kaustiken auf dem beleuchteten Grund
        if (sceneD > 0.0 && f.dimension == 0)
        {
            float c = causticPattern(scenePos.xz + scenePos.y * 0.3, f.time);
            refr *= 1.0 + (c - 0.35) * 0.55 * shadow * lightFade * saturate(thickness) * exp(-thickness * 0.25);
        }
        // Klar in Ufernaehe, Rot/Gruen verschwinden mit der Tiefe; Streulicht leicht tuerkis
        float3 absorb = (1.0 - tintLin) * 0.10 + float3(0.020, 0.012, 0.008);
        float3 trans = exp(-absorb * thickness);
        float3 scatterTint = normalize(tintLin + float3(0.05, 0.45, 0.30));
        float3 scatterHdr = scatterTint * 0.50 * (ambientColor(f, float3(0, 1, 0)) * skyL * skyL
                          + directColor(f) * lightFade * shadow * 0.35 + blockLightColor(input.light.x) * 0.5);
        float3 body = refr * trans + display(scatterHdr, f) * (1.0 - trans);
        // Wellen sichtbar machen: zur Sonne geneigte Flanken leicht heller
        float2 Lxz = L.xz / max(length(L.xz), 1e-3);
        body *= 1.0 + dot(N.xz, Lxz) * 0.8 * lightFade * shadow;

        // ---------- Oberflaeche: Reflexion ----------
        float3 R = reflect(V, N);
        R.y = abs(R.y); // Wellen duerfen nicht in den Boden reflektieren
        float3 skyRefl = f.dimension == 0 ? display(skyRadiance(f, R, true) * (skyL * skyL), f) : body * 0.5;
        float3 refl;
        float fade;
        if (traceReflection(f, wp, R, refl, fade))
            refl = lerp(skyRefl, refl, fade);
        else
            refl = skyRefl;
        float cosV = saturate(dot(-V, N));
        // Fresnel (Schlick, leicht stilisiert: mehr Spiegelung als physikalisch)
        float fresnel = 0.07 + 0.93 * pow(1.0 - cosV, 4.0);
        color = lerp(body, refl, fresnel);

        // Sonnenglanz (eng + breiter Schimmer), mit Schatten
        float3 H = normalize(L - V);
        float nh = saturate(dot(N, H));
        // Enger, heller Glanzpunkt + schwacher Schimmer; Fresnel begrenzt ihn bei steilem Blick
        float spec = (pow(nh, 1400.0) * 5.0 + pow(nh, 160.0) * 0.12) * saturate(fresnel * 4.0);
        color += display(directColor(f) * spec * shadow * lightFade * (1.0 - f.rainFactor), f);
    }
    else
    {
        // Unter Wasser nach oben: Snell-Fenster, ausserhalb Totalreflexion
        float cosV = saturate(dot(-V, N));
        float3 deep = display(tintLin * 0.08 * (ambientColor(f, float3(0, 1, 0)) * skyL * skyL + directColor(f) * lightFade * 0.2), f);
        color = lerp(deep, refr, smoothstep(0.62, 0.72, cosV));
    }

    // Fog: Unterwasser-Kamera bzw. Border-Fog wie das Terrain
    if (f.fogType == 1)
    {
        float3 wc = toLinear(f.fogColor) * 0.6;
        color = lerp(color, display(wc, f), 1.0 - exp(-dist * 0.075));
    }
    else if (f.dimension == 0)
    {
        float t = saturate((dist / max(f.renderDistance, 16.0) - 0.8) / 0.2);
        color = lerp(color, display(skyRadiance(f, V, false), f), t * t * (3.0 - 2.0 * t));
    }
    return float4(toGamma(color), 1.0);
}

// ---------- Transluzente Bloecke (Glas, getoentes Glas, Eis, Slime) ----------
// Gleiche Meshlet-Daten, eigene Sichtbarkeitsliste (Binding 6 = Glas-Liste).
// Reihenfolgeunabhaengig UND exakt: jedes Fragment wird schattiert und in eine
// Pro-Pixel-Liste gehaengt (Kopf-Zeiger per Atomik-Tausch); oit.slang sortiert
// die Liste spaeter nach Tiefe und komponiert von hinten nach vorn. Die Draw-
// Reihenfolge (vom Cull per Atomik, jeden Frame anders) spielt damit keine Rolle.
[[vk::binding(12, 0)]] [format("r32ui")] RWTexture2D<uint> gOitHead; // 0xFFFFFFFF = leer
[[vk::binding(13, 0)]] RWStructuredBuffer<uint> gOitNodes;          // 3 Worte: rgba8, Tiefe, next
[[vk::binding(14, 0)]] RWStructuredBuffer<uint> gOitCounter;        // [0] belegt, [1] Kapazitaet
struct TransVertOut
{
    float4 pos : SV_Position;
    float3 worldPos;
    float3 faceNormal;
    float2 uv;
    float4 color;   // Tint*AO (Gamma)
    float2 light;
    nointerpolation uint mat;
};

[shader("mesh")]
[NumThreads(64, 1, 1)]
[outputtopology("triangle")]
void translucentMesh(
    in uint3 gid : SV_GroupID,
    in uint3 ltid : SV_GroupThreadID,
    out vertices TransVertOut verts[64],
    out indices uint3 tris[124])
{
    uint lt = ltid.x;
    MeshletHeader mh = gMeshlets[gVisibleMeshlets[gid.x]];
    FrameUniforms f = gFrame;
    uint vCount = min(mh.vertexCount, 64);
    uint tCount = min(mh.triCount, 124);
    SetMeshOutputCounts(vCount, tCount);
    computeQuadNormals(mh, lt, vCount);
    if (lt < vCount)
    {
        TerrainVertex tv;
        float3 wp, n;
        loadVertex(mh, lt, f, tv, wp, n);
        verts[lt].pos = mul(f.viewProj, float4(wp, 1.0));
        verts[lt].worldPos = wp;
        verts[lt].faceNormal = n;
        verts[lt].uv = tvUv(tv);
        verts[lt].color = tvColor(tv);
        verts[lt].light = tvLight(tv);
        verts[lt].mat = (tv.color >> 24) & 0xF;
    }
    for (uint t = lt; t < tCount; t += MESH_THREADS)
        tris[t] = unpackTri(gTris[mh.indexStart + t]);
}

float4 shadeTranslucent(TransVertOut input, float4 tex)
{
    FrameUniforms f = gFrame;
    bool ice = input.mat == MAT_ICE;
    float3 wp = input.worldPos;
    float3 V = normalize(wp - f.camPos);
    float3 n = normalize(input.faceNormal);
    if (dot(n, V) > 0.0) n = -n; // Glas ist von beiden Seiten sichtbar
    float dist = length(wp - f.camPos);
    float skyL = input.light.y;

    // Beleuchtung wie im Deferred-Pass (Sonne/Mond mit Schatten, Himmel, Blocklicht)
    float lightFade = length(f.lightDir);
    float3 L = f.lightDir / max(lightFade, 1e-4);
    float shadow = f.dimension == 0 && f.shadowDistance > 0.0 && dist < f.shadowDistance
        ? sampleShadowMap(gShadowMap, gShadowCmp, f, wp, n, input.pos.xy) : smoothstep(0.35, 0.95, skyL);
    float3 albedo = toLinear(tex.rgb * input.color.rgb);
    float3 lightSum = (f.dimension == 0 ? directColor(f) * saturate(dot(n, L)) * lightFade * shadow : float3(0.0))
                    + (f.dimension == 0 ? ambientColor(f, n) * skyL * skyL : float3(0.12, 0.09, 0.08))
                    + blockLightColor(input.light.x) + MIN_LIGHT;
    float3 lit = display(albedo * lightSum, f);

    // Spiegelung (Himmel) per Fresnel; Eis glaenzt staerker und ist etwas klarer
    float cosV = saturate(dot(-V, n));
    float fresnel = (ice ? 0.06 : 0.04) + (ice ? 0.94 : 0.96) * pow(1.0 - cosV, 5.0);
    float3 R = reflect(V, n);
    float3 sky = f.dimension == 0 ? display(skyRadiance(f, R, false) * (skyL * skyL), f) : lit;
    float3 H = normalize(L - V);
    float spec = pow(saturate(dot(n, H)), ice ? 500.0 : 300.0) * (ice ? 6.0 : 3.0) * fresnel;
    float3 color = lerp(lit, sky, fresnel) + display(directColor(f) * spec * shadow * lightFade, f);
    float alpha = saturate(lerp(tex.a * (ice ? 0.8 : 1.0), 1.0, fresnel));

    // Border-Fog wie das Terrain
    if (f.fogType == 1)
        color = lerp(color, display(toLinear(f.fogColor) * 0.6, f), 1.0 - exp(-dist * 0.075));
    else if (f.dimension == 0)
    {
        float t = saturate((dist / max(f.renderDistance, 16.0) - 0.8) / 0.2);
        color = lerp(color, display(skyRadiance(f, V, false), f), t * t * (3.0 - 2.0 * t));
    }
    return float4(toGamma(color), alpha);
}

uint packRgba8(float4 c)
{
    uint4 u = uint4(round(saturate(c) * 255.0));
    return u.r | (u.g << 8) | (u.b << 16) | (u.a << 24);
}

// Frühe Tiefentests: verdeckte Fragmente haengen sich gar nicht erst ein
[shader("fragment")]
[earlydepthstencil]
void translucentRecordFrag(TransVertOut input)
{
    float4 tex = gAtlas.Sample(gAtlasSamp, input.uv);
    if (tex.a < 0.004)
        return;
    float4 c = shadeTranslucent(input, tex);
    if (c.a < 0.002)
        return;
    uint idx;
    InterlockedAdd(gOitCounter[0], 1, idx);
    if (idx >= gOitCounter[1])
        return; // Pool voll (extrem viele Schichten): Fragment faellt weg statt Speicher zu ueberschreiben
    uint prev;
    InterlockedExchange(gOitHead[int2(input.pos.xy)], idx, prev);
    gOitNodes[idx * 3] = packRgba8(c);
    gOitNodes[idx * 3 + 1] = asuint(input.pos.z);
    gOitNodes[idx * 3 + 2] = prev;
}
