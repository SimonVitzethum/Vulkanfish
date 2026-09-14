// motion.slang – Bewegungsvektoren fuer DLSS (eigene Implementierung): je Pixel aus der Tiefe
// (Main-Depth inkl. Vanillas Entities, Reverse-Z) die Welt-Position rekonstruieren und mit der
// Vorframe-Matrix zurueckprojizieren. Vektor in Pixeln von aktuell nach vorher, ohne Jitter
// (DLSS bekommt den Jitter getrennt). Bewegte Entities zaehlen als Kamerabewegung.
[[vk::binding(0, 0)]] Texture2D<float> gDepth;
[[vk::binding(1, 0)]] [format("rg16f")] RWTexture2D<float2> gMotion;

struct MotionUniforms
{
    float4x4 invViewProj;       // aktueller Frame (mit Jitter): Tiefe -> relativer Raum
    float4x4 prevViewProj;      // Vorframe ohne Jitter, im relativen Raum dieses Frames
    float2 screenSize;
    uint historyValid;
    float pad;
    float4x4 viewProjUnjittered; // aktueller Frame ohne Jitter
};
[[vk::binding(2, 0)]] ConstantBuffer<MotionUniforms> gU;

[shader("compute")]
[NumThreads(8, 8, 1)]
void motionMain(in uint3 tid : SV_DispatchThreadID)
{
    MotionUniforms u = gU;
    int2 p = int2(tid.xy);
    if (p.x >= int(u.screenSize.x) || p.y >= int(u.screenSize.y))
        return;
    float d = max(gDepth.Load(int3(p, 0)), 1e-7); // Himmel: sehr fern (dreht korrekt mit)
    float2 uv = (float2(p) + 0.5) / u.screenSize;
    float4 w = mul(u.invViewProj, float4(uv * 2.0 - 1.0, d, 1.0));
    w /= w.w;
    float4 pc = mul(u.prevViewProj, w);
    float4 cc = mul(u.viewProjUnjittered, w);
    float2 mv = float2(0.0);
    if (u.historyValid != 0 && pc.w > 0.0 && cc.w > 0.0)
        mv = (pc.xy / pc.w - cc.xy / cc.w) * 0.5 * u.screenSize;
    gMotion[p] = mv;
}
