// entity_shade.slang – Sonnenschatten AUF Entities (eigene Implementierung). Vanilla beleuchtet
// Entities, Block-Entities und Partikel selbst (Lightmap + feste Richtungen) und kennt unsere
// Schattenkarte nicht: im Schatten einer Wand bliebe ein Dorfbewohner hell. Nach Vanillas
// Entity-Pass: Pixel naeher als unser Terrain = Vanilla-Geometrie -> Weltposition, Schatten-
// karte, Farbe mal (Ambient + Sonne * Schatten) / (Ambient + Sonne) wie im Deferred-Licht.
#include "shadow.slang"

[[vk::binding(0, 0)]] ConstantBuffer<FrameUniforms> gFrame;
[[vk::binding(1, 0)]] Texture2D<float> gSceneDepth;    // Main-Tiefe: Terrain + Entities
[[vk::binding(2, 0)]] Texture2D<float> gTerrainDepth;  // unser G-Buffer (nur Terrain/LOD)
[[vk::binding(3, 0)]] Texture2D<float> gShadowMap;
[[vk::binding(4, 0)]] SamplerComparisonState gShadowCmp;

struct FsIn
{
    float4 pos : SV_Position;
};

float3 entityWorldPos(FrameUniforms f, int2 q, float d)
{
    float2 uv = (float2(q) + 0.5) / f.screenSize;
    float4 p = mul(f.invViewProj, float4(uv * 2.0 - 1.0, d, 1.0));
    return p.xyz / p.w;
}

// Blend: Ziel * Ausgabe (nur abdunkeln)
[shader("fragment")]
float4 entityShadeFrag(FsIn input) : SV_Target0
{
    FrameUniforms f = gFrame;
    int2 px = int2(input.pos.xy);
    float d = gSceneDepth.Load(int3(px, 0));
    float t = gTerrainDepth.Load(int3(px, 0));
    // Reverse-Z: groesser = naeher. Nur Pixel, die Vanilla vor unser Terrain gezeichnet hat
    if (f.dimension != 0 || f.shadowDistance <= 0.0 || d <= 0.0 || d <= t * 1.00002 + 1e-7)
        discard;
    float lightFade = length(f.lightDir);
    if (lightFade < 1e-3)
        discard;
    float3 wp = entityWorldPos(f, px, d);
    // Flaechennormale aus der Tiefe (je Achse der Nachbar mit der kleineren Tiefenaenderung:
    // an Kanten nicht ueber die Silhouette hinweg)
    float dxp = gSceneDepth.Load(int3(px + int2(1, 0), 0)), dxm = gSceneDepth.Load(int3(px - int2(1, 0), 0));
    float dyp = gSceneDepth.Load(int3(px + int2(0, 1), 0)), dym = gSceneDepth.Load(int3(px - int2(0, 1), 0));
    float3 ddx = abs(dxp - d) < abs(dxm - d) ? entityWorldPos(f, px + int2(1, 0), dxp) - wp : wp - entityWorldPos(f, px - int2(1, 0), dxm);
    float3 ddy = abs(dyp - d) < abs(dym - d) ? entityWorldPos(f, px + int2(0, 1), dyp) - wp : wp - entityWorldPos(f, px - int2(0, 1), dym);
    float3 n = normalize(cross(ddx, ddy));
    if (dot(n, f.camPos - wp) < 0.0)
        n = -n;
    float3 L = f.lightDir / lightFade;
    float ndl = dot(n, L);
    // Von der Sonne abgewandte Seiten schattiert Vanilla schon selbst (feste Entity-Lichtrichtungen):
    // nicht noch einmal abdunkeln – das machte Truhen-Fronten fast schwarz
    if (ndl <= 0.0)
        discard;
    float shadow = sampleShadowMap(gShadowMap, gShadowCmp, f, wp, n, input.pos.xy);
    if (shadow >= 0.999)
        discard;
    // Verhaeltnis im Bildraum (nach der Tonemap-Kurve), wie das Terrain im Schatten wirkt
    float amb = luma(ambientColor(f, n));
    float sun = luma(directColor(f)) * lightFade * ndl;
    float lit = luma(tonemap(float3(amb + sun), f.exposure));
    float shaded = luma(tonemap(float3(amb + sun * shadow), f.exposure));
    float ratio = shaded / max(lit, 1e-4);
    return float4(ratio, ratio, ratio, 1.0);
}
