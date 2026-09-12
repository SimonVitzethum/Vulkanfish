// final.slang – Compute: HDR + Bloom -> filmische Kurve -> RGBA8 (Gamma),
// mit Dithering gegen Banding im Himmelsverlauf. Wird ins Main-Target kopiert.
#include "gpu_scene.slang"
#include "look.slang"

[[vk::binding(0, 0)]] Texture2D<float4> gHdr;
[[vk::binding(1, 0)]] Texture2D<float4> gBloom;   // Mip 0 = halbe Aufloesung
[[vk::binding(2, 0)]] [format("rgba8")] RWTexture2D<float4> gOut;
[[vk::binding(3, 0)]] ConstantBuffer<FrameUniforms> gFrame;
[[vk::binding(4, 0)]] SamplerState gLinear;

static const float BLOOM_STRENGTH = 0.07;

[shader("compute")]
[NumThreads(8, 8, 1)]
void finalMain(in uint3 tid : SV_DispatchThreadID)
{
    FrameUniforms f = gFrame;
    if (tid.x >= uint(f.screenSize.x) || tid.y >= uint(f.screenSize.y))
        return;
    float2 uv = (float2(tid.xy) + 0.5) / f.screenSize;
    float3 hdr = gHdr.Load(int3(tid.xy, 0)).rgb;
    float3 bloom = gBloom.SampleLevel(gLinear, uv, 0).rgb;
    float3 c = tonemap(lerp(hdr, bloom, BLOOM_STRENGTH), f.exposure);
    float n = frac(52.9829189 * frac(0.06711056 * float(tid.x) + 0.00583715 * float(tid.y)));
    c += (n - 0.5) / 255.0;
    gOut[tid.xy] = float4(saturate(c), 1.0);
}
