// rt_lights.slang – sortiert die Lichtquellen um die Kamera in ein 16^3-Zellgitter
// (8 Bloecke pro Zelle). Ein Thread pro Zelle: prueft alle Lichter und behaelt die LIGHT_CAP
// staerksten (unverschattete Staerke am naechsten Punkt der Zelle). Deterministisch – vorher
// fuegte je Licht ein Thread per Atomik ein; bei vollen Zellen fielen dann jedes Bild andere
// Lichter heraus (Flackern, Kanten im Zellraster bei vielen Fackeln).
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
    uint cell = tid.x;
    if (cell >= uint(LIGHT_GRID * LIGHT_GRID * LIGHT_GRID))
        return;
    int3 c = int3(int(cell % uint(LIGHT_GRID)), int((cell / uint(LIGHT_GRID)) % uint(LIGHT_GRID)), int(cell / uint(LIGHT_GRID * LIGHT_GRID)));
    float3 bmin = float3((c + gBin.grid.xyz) * LIGHT_CELL);
    float3 bmax = bmin + float(LIGHT_CELL);
    uint idx[LIGHT_CAP];
    float wgt[LIGHT_CAP];
    uint n = 0;
    uint lightCount = uint(gBin.grid.w);
    for (uint i = 0; i < lightCount; i++)
    {
        RtLight l = gLights[i];
        float e = rtLightEmission(l.packed);
        float3 q = clamp(l.pos, bmin, bmax);
        float d = distance(q, l.pos);
        if (d > e + 0.5)
            continue; // Kugel beruehrt die Zelle nicht
        float w = rtLightFalloff(d, e) + 1e-4; // auch Randlichter vor ganz fernen
        if (n < LIGHT_CAP)
        {
            // einsortieren (absteigend)
            uint k = n++;
            while (k > 0 && wgt[k - 1] < w)
            {
                wgt[k] = wgt[k - 1];
                idx[k] = idx[k - 1];
                k--;
            }
            wgt[k] = w;
            idx[k] = i;
        }
        else if (w > wgt[LIGHT_CAP - 1])
        {
            uint k = LIGHT_CAP - 1;
            while (k > 0 && wgt[k - 1] < w)
            {
                wgt[k] = wgt[k - 1];
                idx[k] = idx[k - 1];
                k--;
            }
            wgt[k] = w;
            idx[k] = i;
        }
    }
    gCellCount[cell] = n;
    for (uint j = 0; j < n; j++)
        gCellLights[cell * LIGHT_CAP + j] = idx[j];
}
