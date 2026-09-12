// bloom.slang – Compute-Bloom: Downsample-Kette (13 Taps, erster Schritt mit
// Karis-Mittel gegen Flackern) und Tent-Upsample, das additiv zurueckfaltet.
// Alles in einer RGBA16F-Mip-Kette ab halber Aufloesung.
#include "look.slang"

[[vk::binding(0, 0)]] Texture2D<float4> gSrc;                   // HDR-Bild bzw. Bloom-Kette
[[vk::binding(1, 0)]] [format("rgba16f")] RWTexture2D<float4> gDst; // Ziel-Mip
[[vk::binding(2, 0)]] SamplerState gLinear;

struct BloomPush
{
    uint2 dstSize;
    uint srcMip;
    uint firstPass;
};

[[vk::push_constant]] BloomPush gPush;

float3 tap(float2 uv)
{
    return gSrc.SampleLevel(gLinear, uv, float(gPush.srcMip)).rgb;
}

float karis(float3 c)
{
    return 1.0 / (1.0 + luma(c));
}

[shader("compute")]
[NumThreads(8, 8, 1)]
void bloomDown(in uint3 tid : SV_DispatchThreadID)
{
    if (tid.x >= gPush.dstSize.x || tid.y >= gPush.dstSize.y)
        return;
    float2 texel = 1.0 / float2(gPush.dstSize * 2);
    float2 uv = (float2(tid.xy) + 0.5) / float2(gPush.dstSize);
    float3 a = tap(uv + texel * float2(-2, -2)), b = tap(uv + texel * float2(0, -2)), c = tap(uv + texel * float2(2, -2));
    float3 d = tap(uv + texel * float2(-1, -1)), e = tap(uv + texel * float2(1, -1));
    float3 g = tap(uv + texel * float2(-2, 0)), h = tap(uv), i = tap(uv + texel * float2(2, 0));
    float3 j = tap(uv + texel * float2(-1, 1)), k = tap(uv + texel * float2(1, 1));
    float3 l = tap(uv + texel * float2(-2, 2)), m = tap(uv + texel * float2(0, 2)), o = tap(uv + texel * float2(2, 2));
    float3 r;
    if (gPush.firstPass != 0)
    {
        // Karis-Mittel pro Gruppe: einzelne extrem helle Pixel (Sonne, Lava) flackern nicht
        float3 g0 = (d + e + j + k) * 0.25, g1 = (a + b + g + h) * 0.25, g2 = (b + c + h + i) * 0.25;
        float3 g3 = (g + h + l + m) * 0.25, g4 = (h + i + m + o) * 0.25;
        float w0 = karis(g0) * 0.5, w1 = karis(g1) * 0.125, w2 = karis(g2) * 0.125, w3 = karis(g3) * 0.125, w4 = karis(g4) * 0.125;
        r = (g0 * w0 + g1 * w1 + g2 * w2 + g3 * w3 + g4 * w4) / (w0 + w1 + w2 + w3 + w4);
    }
    else
    {
        r = (d + e + j + k) * 0.125 + (a + c + l + o) * 0.03125 + (b + g + i + m) * 0.0625 + h * 0.125;
    }
    gDst[tid.xy] = float4(r, 1.0);
}

// dst(mip) += Tent-Upsample(src mip+1)
[shader("compute")]
[NumThreads(8, 8, 1)]
void bloomUp(in uint3 tid : SV_DispatchThreadID)
{
    if (tid.x >= gPush.dstSize.x || tid.y >= gPush.dstSize.y)
        return;
    float2 texel = 1.0 / float2(gPush.dstSize);
    float2 uv = (float2(tid.xy) + 0.5) / float2(gPush.dstSize);
    float3 s = tap(uv + texel * float2(-1, -1)) + tap(uv + texel * float2(1, -1))
             + tap(uv + texel * float2(-1, 1)) + tap(uv + texel * float2(1, 1));
    s += (tap(uv + texel * float2(0, -1)) + tap(uv + texel * float2(-1, 0))
        + tap(uv + texel * float2(1, 0)) + tap(uv + texel * float2(0, 1))) * 2.0;
    s += tap(uv) * 4.0;
    gDst[tid.xy] = float4(gDst[tid.xy].rgb + s / 16.0, 1.0);
}
