// hiz_downsample.slang – Compute: baut die Hi-Z-Pyramide (R32F, Reverse-Z).
// Seed (gPush.seed=1): Mip 0 = 1:1-Kopie des Tiefenpuffers (Vorframe).
// Sonst: Min-Reduktion 2x2 (Reverse-Z: kleinste Tiefe = fernster Occluder);
// ungerade Quellgroessen nehmen die Randzeile/-spalte mit (konservativ).
[[vk::binding(0, 0)]] Texture2D<float> gSrc;                  // Tiefe (Seed) bzw. volle Hi-Z
[[vk::binding(1, 0)]] [format("r32f")] RWTexture2D<float> gDst; // Ziel-Mip
[[vk::binding(2, 0)]] SamplerState gSamp;                     // (Load-basiert, Binding bleibt)

struct HizPush
{
    uint2 dstSize;
    uint srcMip;
    uint seed;
};

[[vk::push_constant]] HizPush gPush;

[shader("compute")]
[NumThreads(8, 8, 1)]
void hizDownsample(in uint3 tid : SV_DispatchThreadID)
{
    if (tid.x >= gPush.dstSize.x || tid.y >= gPush.dstSize.y)
        return;
    int2 p = int2(tid.xy);
    if (gPush.seed != 0)
    {
        gDst[p] = gSrc.Load(int3(p, 0));
        return;
    }
    uint sw, sh, levels;
    gSrc.GetDimensions(gPush.srcMip, sw, sh, levels);
    int2 lim = int2(sw, sh) - 1;
    int2 ext = int2(
        (p.x == int(gPush.dstSize.x) - 1 && (sw & 1) != 0) ? 2 : 1,
        (p.y == int(gPush.dstSize.y) - 1 && (sh & 1) != 0) ? 2 : 1);
    float d = 1.0;
    for (int y = 0; y <= ext.y; y++)
        for (int x = 0; x <= ext.x; x++)
            d = min(d, gSrc.Load(int3(min(p * 2 + int2(x, y), lim), int(gPush.srcMip))));
    gDst[p] = d;
}
