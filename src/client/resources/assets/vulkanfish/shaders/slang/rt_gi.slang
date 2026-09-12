// rt_gi.slang – Raytracing: RT-Schatten (1 SPP) + RTGI-Halbauflösung.
// TLAS: 2 Instanzen (Terrain-BLAS aus Meshlet-AABBs + Entity-BLAS pro Archetyp).
// Laeuft async; waehrend RT rechnet, baut Compute schon Frame N+1.
// Look: warme Sonne wie Complementary, Himmel aus compSkyGradient als Miss.
#include "gpu_scene.slang"
#include "look.slang"

[[vk::binding(0, 0)]] RaytracingAccelerationStructure gTlas;
[[vk::binding(1, 0)]] RWTexture2D<float4> gGiTarget;     // halbe Aufloesung
[[vk::binding(2, 0)]] RWTexture2D<float> gShadowTarget;  // Vollbild-Faktor
[[vk::binding(3, 0)]] Texture2D<float4> gAlbedo;
[[vk::binding(4, 0)]] Texture2D<float4> gNormalDepth;    // rgb=Normal, a=Depth
[[vk::binding(5, 0)]] ConstantBuffer<FrameUniforms> gFrame;

struct GiPayload { float3 gi; };
struct ShadowPayload { float hit; };

float3 worldFromDepth(float2 ndc, float depth, float4x4 invVP)
{
    float4 p = mul(invVP, float4(ndc, depth, 1.0));
    return p.xyz / max(p.w, 1e-5);
}

[shader("raygeneration")]
void rtgiRgen()
{
    uint2 idx = DispatchRaysIndex().xy;
    uint2 size = DispatchRaysDimensions().xy;
    FrameUniforms f = gFrame;
    float2 ndc = (float2(idx) + 0.5) / float2(size) * 2.0 - 1.0;
    float4 nd = gNormalDepth[idx * 2]; // G-Buffer ist vollaufgeloest
    float3 n = normalize(nd.xyz * 2.0 - 1.0);
    float3 albedo = gAlbedo[idx * 2].rgb;

    // Weltposition rueckprojizieren (invVP als Push? hier vereinfacht: Tiefe->Strahl)
    float3 sunC = directColor(f);
    float3 ambC = ambientColor(f, n);

    // Schattenstrahl zur Sonne
    float3 origin = float3(0.0, 0.0, 0.0); // Platzhalter: echte Pos aus G-Buffer
    RayDesc sray;
    sray.Origin = origin + n * 0.05;
    sray.Direction = normalize(f.sunDir);
    sray.TMin = 0.05;
    sray.TMax = 512.0;
    ShadowPayload sp; sp.hit = 1.0;
    TraceRay(gTlas, RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH, 0xFF, 0, 1, 1, sray, sp);

    // Indirekt: 1 Bounce in Normal-Halbraum (Kosinus)
    float3 tangent = normalize(abs(n.y) > 0.9 ? cross(n, float3(1, 0, 0)) : cross(n, float3(0, 1, 0)));
    float3 bitangent = cross(n, tangent);
    float2 u = float2(0.5, 0.5); // deterministisch; TAA-Jitter kommt via Dither-Textur
    float3 bounce = normalize(tangent * (u.x * 2.0 - 1.0) + bitangent * (u.y * 2.0 - 1.0) + n * 1.2);
    RayDesc gray;
    gray.Origin = origin + n * 0.05;
    gray.Direction = bounce;
    gray.TMin = 0.05;
    gray.TMax = 128.0;
    GiPayload gp; gp.gi = ambC;
    TraceRay(gTlas, RAY_FLAG_NONE, 0xFF, 0, 1, 0, gray, gp);

    gGiTarget[idx] = float4(gp.gi * albedo, 1.0);
    gShadowTarget[idx] = sp.hit;
}

[shader("miss")]
void rtgiMiss(inout GiPayload p)
{
    // Himmel als indirektes Licht (Complementary-Gradient, oben)
    p.gi = float3(0.55, 0.65, 0.85);
}

[shader("miss")]
void shadowMiss(inout ShadowPayload p)
{
    p.hit = 0.0; // kein Treffer -> volle Sonne
}

[shader("closesthit")]
void rtgiChit(inout GiPayload p, in BuiltInTriangleIntersectionAttributes attr)
{
    p.gi = float3(0.45, 0.38, 0.30); // durchschnittliche Bounce-Farbe (Tunnel-Naeherung)
}

[shader("closesthit")]
void shadowChit(inout ShadowPayload p, in BuiltInTriangleIntersectionAttributes attr)
{
    p.hit = 1.0;
}
