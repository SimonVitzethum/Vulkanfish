// shadow.slang – Schatten-Sampling (verzerrte Lichtraum-Map, rotiertes 8-Tap-PCF
// mit Hardware-Vergleich). Von Deferred- und Wasser-Pass genutzt.
#ifndef VULKANFISH_SHADOW_SLANG
#define VULKANFISH_SHADOW_SLANG

#include "look.slang"

// Interleaved Gradient Noise: rotiert die PCF-Taps pro Pixel
float ign(float2 p, uint frame)
{
    p += float(frame & 63) * 5.588238;
    return frac(52.9829189 * frac(0.06711056 * p.x + 0.00583715 * p.y));
}

static const float2 POISSON[8] = {
    float2(-0.613, 0.617), float2(0.170, -0.040), float2(-0.299, -0.792), float2(0.645, 0.493),
    float2(-0.651, -0.024), float2(0.421, -0.628), float2(0.036, 0.931), float2(0.975, -0.100)
};

// offsetDir: Normale (Akne-Schutz) bzw. Lichtrichtung bei Laub
float sampleShadowMap(Texture2D<float> map, SamplerComparisonState cmp, FrameUniforms f,
                      float3 wp, float3 offsetDir, float2 pixel)
{
    float dist = length(wp - f.camPos);
    // Normal-Offset gegen Akne, waechst mit Entfernung (Texel werden durch die Verzerrung groesser)
    float bias = 0.03 + dist / f.shadowDistance * 0.12;
    float4 sc = mul(f.shadowViewProj, float4(wp + offsetDir * bias, 1.0));
    float2 center = sc.xy;
    float2 uv = distortShadow(center) * 0.5 + 0.5;
    if (any(uv <= 0.0) || any(uv >= 1.0) || sc.z >= 1.0)
        return 1.0;
    uint sw, sh, levels;
    map.GetDimensions(0, sw, sh, levels);
    float radius = 1.5 / float(sw); // ~1.5 Texel im verzerrten Raum
    float a = ign(pixel, f.frameIndex) * 2.0 * PI;
    float2x2 rot = float2x2(cos(a), -sin(a), sin(a), cos(a));
    float ref = sc.z - 0.0004;
    float lit = 0.0;
    for (uint i = 0; i < 8; i++)
        lit += map.SampleCmpLevelZero(cmp, uv + mul(rot, POISSON[i]) * radius, ref);
    lit /= 8.0;
    // Rand der Schattendistanz weich ausblenden
    return lerp(lit, 1.0, saturate((length(center) - 0.85) / 0.15));
}

#endif
