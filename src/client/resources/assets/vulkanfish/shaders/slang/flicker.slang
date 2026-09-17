// flicker.slang – Flacker-Diagnose (NUR Diagnose-Builds: -PvfFlickerDebug).
// Vergleicht das fertige LDR-Terrainbild mit dem Vorframe (sauber, vor Overlay):
// pixelnahes Flackern -> Magenta-Heatmap + Atomik-Zaehler (Anteil, Host-lesbar).
// Modus per Push: 0 Heatmap (Szene + Magenta), 1 Isolation (Schwarz + Weiss).
// Release-Builds enthalten weder dieses SPIR-V noch den Manifest-Eintrag.
[[vk::binding(0, 0)]] Texture2D<float4> gCur;                    // aktuelles LDR
[[vk::binding(1, 0)]] Texture2D<float4> gPrev;                   // Vorframe-LDR (sauber)
[[vk::binding(2, 0)]] [format("rgba8")] RWTexture2D<float4> gOut; // eigenes Ausgabebild
[[vk::binding(3, 0)]] RWStructuredBuffer<uint> gCount;            // [0] flackernde Pixel

struct FlickerPush
{
    uint mode;
    uint imgW;
    uint imgH;
    uint pad;
};
[[vk::push_constant]] ConstantBuffer<FlickerPush> gP;

[shader("compute")]
[NumThreads(8, 8, 1)]
void flickerOverlay(in uint3 tid : SV_DispatchThreadID)
{
    if (tid.x >= gP.imgW || tid.y >= gP.imgH)
        return;
    int2 p = int2(tid.xy);
    float3 cur = gCur.Load(int3(p, 0)).rgb;
    float3 prv = gPrev.Load(int3(p, 0)).rgb;
    float d = length(cur - prv); // Gamma-Raum-Distanz
    float heat = smoothstep(0.03, 0.20, d);
    if (heat > 0.001)
    {
        uint idx;
        InterlockedAdd(gCount[0], 1, idx);
    }
    float3 o = gP.mode == 0
        ? lerp(cur, float3(1.0, 0.0, 1.0), heat)
        : (heat > 0.5 ? float3(1.0, 1.0, 1.0) : float3(0.0, 0.0, 0.0));
    gOut[p] = float4(o, 1.0);
}
