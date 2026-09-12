// rt_lights.slang – sortiert die Lichtquellen um die Kamera in ein 16^3-Zellgitter
// (8 Bloecke pro Zelle). Ein Thread pro Licht, Atomik-Einfuegen in alle Zellen, die
// seine Reichweite beruehrt. Das Deferred-Licht liest pro Pixel nur seine Zelle.
#include "rt_common.slang"

[[vk::binding(0, 0)]] StructuredBuffer<RtLight> gLights;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint> gCellCount;
[[vk::binding(2, 0)]] RWStructuredBuffer<uint> gCellLights;

struct BinPush
{
    int4 grid; // xyz = Ursprung (Zellen), w = Anzahl Lichter
};
[[vk::push_constant]] ConstantBuffer<BinPush> gBin;

[shader("compute")]
[NumThreads(64, 1, 1)]
void lightBin(in uint3 tid : SV_DispatchThreadID)
{
    if (tid.x >= uint(gBin.grid.w))
        return;
    RtLight l = gLights[tid.x];
    float range = rtLightEmission(l.packed) + 0.5;
    int3 origin = gBin.grid.xyz;
    int3 lo = max(int3(floor((l.pos - range) / float(LIGHT_CELL))) - origin, int3(0, 0, 0));
    int3 hi = min(int3(floor((l.pos + range) / float(LIGHT_CELL))) - origin, int3(LIGHT_GRID - 1, LIGHT_GRID - 1, LIGHT_GRID - 1));
    for (int z = lo.z; z <= hi.z; z++)
    {
        for (int y = lo.y; y <= hi.y; y++)
        {
            for (int x = lo.x; x <= hi.x; x++)
            {
                float3 bmin = float3((int3(x, y, z) + origin) * LIGHT_CELL);
                float3 q = clamp(l.pos, bmin, bmin + float(LIGHT_CELL));
                if (distance(q, l.pos) > range)
                    continue; // Kugel beruehrt die Zelle nicht
                uint cell = uint((z * LIGHT_GRID + y) * LIGHT_GRID + x);
                uint slot;
                InterlockedAdd(gCellCount[cell], 1, slot);
                if (slot < LIGHT_CAP)
                    gCellLights[cell * LIGHT_CAP + slot] = tid.x;
            }
        }
    }
}
