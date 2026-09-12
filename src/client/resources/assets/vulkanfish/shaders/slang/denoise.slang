// denoise.slang – SVGF-lite: Temporal-Akkumulation + A-Trous-Wavelet.
// Look-Ziel: Complementary-ruhiges GI ohne Flimmern bei 0.5 SPP.
// Zwei Entries, zwei Dispatches pro Frame (temporal -> gTemporal, dann 3x atrous
// ping-pong; Iteration per Push, Kernel waechst 2^i).
#include "gpu_scene.slang"

[[vk::binding(0, 0)]] Texture2D<float4> gNoisy;      // RTGI roh (halb)
[[vk::binding(1, 0)]] Texture2D<float4> gHistory;    // Moments Vorjahr (rgb=mean, a=var)
[[vk::binding(2, 0)]] Texture2D<float4> gMotion;     // Bewegungsvektoren (UV-Delta)
[[vk::binding(3, 0)]] Texture2D<float4> gNormalDepth;// rgb=Normal*0.5+0.5, a=Depth
[[vk::binding(4, 0)]] RWTexture2D<float4> gTemporalOut;
[[vk::binding(5, 0)]] ConstantBuffer<FrameUniforms> gFrame;
[[vk::binding(6, 0)]] SamplerState gSamp;

[shader("compute")]
[NumThreads(8, 8, 1)]
void denoiseTemporal(in uint3 tid : SV_DispatchThreadID)
{
    uint2 size;
    gTemporalOut.GetDimensions(size.x, size.y);
    if (tid.x >= size.x || tid.y >= size.y)
        return;
    float2 uv = (float2(tid.xy) + 0.5) / float2(size);
    float3 noisy = gNoisy.Load(int3(tid.xy, 0)).rgb;
    float2 motion = gMotion.Load(int3(tid.xy, 0)).rg;
    float2 huv = uv - motion;
    float4 hist = gHistory.SampleLevel(gSamp, huv, 0);
    float4 nd = gNormalDepth.Load(int3(tid.xy * 2, 0));
    // Disokklusion: Normal-/Tiefen-Diskontinuitaet -> Historie verwerfen
    float3 hn = gNormalDepth.SampleLevel(gSamp, huv * 2.0, 0).rgb * 2.0 - 1.0;
    float3 n = nd.rgb * 2.0 - 1.0;
    float disocc = (1.0 - saturate(dot(normalize(n), normalize(hn)))) * 4.0
                 + abs(nd.a - gNormalDepth.SampleLevel(gSamp, huv * 2.0, 0).a) * 8.0;
    float alpha = saturate(0.05 + disocc); // 0.05 = 20 Frames Historie
    // Clamp-Box gegen Flimmern (3x3-Nachbarschaft im Noisy)
    float3 nmin = noisy, nmax = noisy;
    for (int oy = -1; oy <= 1; oy++)
        for (int ox = -1; ox <= 1; ox++)
        {
            float3 s = gNoisy.Load(int3(tid.xy + int2(ox, oy), 0)).rgb;
            nmin = min(nmin, s);
            nmax = max(nmax, s);
        }
    float3 hclamped = clamp(hist.rgb, nmin, nmax);
    float3 mean = lerp(hclamped, noisy, alpha);
    float var = lerp(hist.a, dot(noisy - mean, noisy - mean), alpha);
    gTemporalOut[tid.xy] = float4(mean, var);
}

// --- A-Trous ---
// Eigener Binding-Block (10+), da beide Entries in einer TU liegen;
// zur Laufzeit sind es ohnehin getrennte Pipelines/Descriptor-Sets.
[[vk::binding(10, 0)]] Texture2D<float4> gAtrousSrc;   // Moments (temporal oder iterativ)
[[vk::binding(11, 0)]] Texture2D<float4> gAtrousND;    // Normal+Depth (halb)
[[vk::binding(12, 0)]] RWTexture2D<float4> gAtrousDst;

struct AtrousPush
{
    int step;          // 1, 2, 4
    float phiNormal;   // z.B. 0.1
    float phiDepth;    // z.B. 0.02 * Szene
    float phiLuma;     // aus Varianz
};

[[vk::push_constant]] AtrousPush gAtrous;

float atrousWeight(float3 n0, float3 n1, float d0, float d1, float l0, float l1, float var)
{
    float wn = pow(saturate(dot(n0, n1)), gAtrous.phiNormal * 64.0);
    float wd = exp(-abs(d0 - d1) / max(gAtrous.phiDepth, 1e-5));
    float wl = exp(-abs(l0 - l1) / sqrt(max(var, 1e-6)) * gAtrous.phiLuma);
    return wn * wd * wl;
}

float luma(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }

[shader("compute")]
[NumThreads(8, 8, 1)]
void denoiseAtrous(in uint3 tid : SV_DispatchThreadID)
{
    uint2 size;
    gAtrousDst.GetDimensions(size.x, size.y);
    if (tid.x >= size.x || tid.y >= size.y)
        return;
    float4 c0 = gAtrousSrc.Load(int3(tid.xy, 0));
    float4 nd0 = gAtrousND.Load(int3(tid.xy, 0));
    float3 n0 = normalize(nd0.rgb * 2.0 - 1.0);
    float3 sum = c0.rgb;
    float wsum = 1.0;
    // 5x5 sparse, Schrittweite = step (A-Trous)
    for (int oy = -2; oy <= 2; oy++)
        for (int ox = -2; ox <= 2; ox++)
        {
            if (ox == 0 && oy == 0) continue;
            int2 p = int2(tid.xy) + int2(ox, oy) * gAtrous.step;
            p = clamp(p, int2(0, 0), int2(size) - 1);
            float4 cs = gAtrousSrc.Load(int3(p, 0));
            float4 nds = gAtrousND.Load(int3(p, 0));
            float3 ns = normalize(nds.rgb * 2.0 - 1.0);
            float w = atrousWeight(n0, ns, nd0.a, nds.a, luma(c0.rgb), luma(cs.rgb), c0.a + 1e-4);
            // Tent-Filter 5x5: [1 2 3 2 1]^2 normiert ~ ueber Distanz
            float tent = (3 - abs(ox)) * (3 - abs(oy)) / 81.0 * 9.0;
            sum += cs.rgb * (w * tent);
            wsum += w * tent;
        }
    gAtrousDst[tid.xy] = float4(sum / max(wsum, 1e-5), c0.a);
}
