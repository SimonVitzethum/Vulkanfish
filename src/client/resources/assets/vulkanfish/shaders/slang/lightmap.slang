// lightmap.slang – Vanillas Entity-Lightmap im Vulkanfish-Look als Compute (16x16):
// Entities, Block-Entities, Partikel und Hand beleuchtet Vanilla ueber diese Textur
// (Blocklicht x Himmelslicht). Dieselbe Rechnung wie im Deferred-Pass fuer eine weisse
// Flaeche mittlerer Ausrichtung, durch dieselbe Tonemap-Kurve – frueher auf der CPU
// (EntityLightmap) plus Upload pro Frame, jetzt ein 2x2-Dispatch auf der GPU.
#include "gpu_scene.slang"
#include "look.slang"

[[vk::binding(0, 0)]] ConstantBuffer<FrameUniforms> gFrame;
[[vk::binding(1, 0)]] [format("rgba8")] RWTexture2D<float4> gOut;

static const float3 LM_TORCH = float3(1.00, 0.58, 0.28);
static const float LM_AVG_UP = 0.75; // mittlere Ausrichtung einer Entity (n.y*0.5+0.5)
static const float LM_AVG_NDL = 0.6; // mittleres N.L zur Sonne (wie entity_shade.slang)

float3 lmAmbient(FrameUniforms f)
{
    if (f.dimension == 1)
        return float3(0.14, 0.07, 0.05);
    if (f.dimension != 0)
        return float3(0.09, 0.08, 0.12);
    float3 dayUp = lerp(float3(0.40, 0.55, 0.88), pow(max(f.skyColor, 0.0), 2.2) * 1.1, 0.4) * 0.55;
    float3 dusk = float3(0.50, 0.38, 0.42) * 0.35;
    float noonT = smoothstep(0.0, 0.45, f.noonFactor);
    float3 day = lerp(dusk, dayUp, noonT);
    float nightK = 0.7 + 0.6 * f.moonBrightness;
    float3 night = float3(0.040, 0.055, 0.100) * nightK;
    float3 amb = lerp(night, day, f.sunVisibility);
    float l = luma(amb);
    amb = lerp(amb, float3(l * 0.85, l * 0.9, l), f.rainFactor * 0.6) * lerp(0.45, 1.0, LM_AVG_UP);
    return amb;
}

float3 lmDirect(FrameUniforms f)
{
    float noonT = smoothstep(0.0, 0.55, f.noonFactor);
    float rainSun = lerp(1.0, 0.12, f.rainFactor);
    float moonK = (0.10 + 0.18 * f.moonBrightness) * lerp(1.0, 0.25, f.rainFactor);
    float3 sun = lerp(float3(1.00, 0.50, 0.20) * 1.7, float3(1.00, 0.93, 0.82) * 2.3, noonT) * rainSun;
    return lerp(float3(0.36, 0.48, 0.78) * moonK, sun, f.sunVisibility);
}

[shader("compute")]
[NumThreads(8, 8, 1)]
void lightmapMain(in uint3 tid : SV_DispatchThreadID)
{
    if (tid.x >= 16 || tid.y >= 16)
        return;
    FrameUniforms f = gFrame;
    float s = float(tid.y) / 15.0; // Himmelslicht
    float b = float(tid.x) / 15.0; // Blocklicht
    float skyGate = smoothstep(0.35, 0.95, s);
    float sunVis = f.dimension == 0 ? lerp(skyGate, 1.0, 0.75) * smoothstep(0.02, 0.33, s) : 0.0;
    float bl = b * b * (0.55 + 0.45 * b * b) * 2.4;
    float fade = length(f.lightDir);
    float3 amb = lmAmbient(f);
    float3 dir = lmDirect(f);
    float fb = f.fullbright;
    float fbs = fb * sqrt(fb) * lerp(0.7, 1.0, LM_AVG_UP);
    float3 a = f.dimension == 0 ? amb * s * s : amb;
    float3 sun = f.dimension == 0 ? dir * LM_AVG_NDL * fade * sunVis : float3(0.0, 0.0, 0.0);
    float3 fill = fbs * float3(0.62, 0.66, 0.72);
    float3 c = sun + a + LM_TORCH * bl + fill + float3(0.010, 0.011, 0.014);
    gOut[tid.xy] = float4(tonemap(c, f.exposure), 1.0);
}
