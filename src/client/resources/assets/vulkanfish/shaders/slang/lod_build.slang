// lod_build.slang – GPU-LOD-Builder: erzeugt aus Bobby-/Vanilla-Ferndaten
// (Heightfield 16x16 + Blockfarben) dezimierte LOD-Meshlets direkt auf der GPU.
// Kein CPU-Meshing: CPU meldet nur "Chunk x,z, LOD-Stufe", der Rest (Verts,
// Indices, Cluster-AABB, Cone) entsteht hier und landet per Transfer-Queue
// im Meshlet-Speicher. Skirts gegen LOD-Risse: v2 (aktuell Fog kaschiert).
#include "gpu_scene.slang"

[[vk::binding(0, 0)]] StructuredBuffer<float> gHeights;   // 16x16 pro Chunk, Welt-Y
[[vk::binding(1, 0)]] StructuredBuffer<float4> gColors;   // 16x16 Blockfarbe+tint
[[vk::binding(2, 0)]] RWStructuredBuffer<PackedVertex> gVertOut;
[[vk::binding(3, 0)]] RWStructuredBuffer<uint> gIndexOut;
[[vk::binding(4, 0)]] RWStructuredBuffer<MeshletHeader> gMeshletOut;
[[vk::binding(5, 0)]] RWStructuredBuffer<Cluster> gClusterOut;
[[vk::binding(6, 0)]] RWStructuredBuffer<uint> gAllocator; // [vPos, iPos, mPos, cPos] Atomik-Zaehler

struct LodPush
{
    int chunkX;
    int chunkZ;
    int lod;            // 0 = voll (16x16), 1 = 8x8, 2 = 4x4, 3 = 2x2
    uint heightBase;    // Offset in gHeights/gColors
    float minY;
    uint outCluster;    // Ziel-Cluster-Slot (CPU vergeben, kein Search auf GPU)
};

[[vk::push_constant]] LodPush gPush;

groupshared uint sV0;
groupshared int sGrid;
groupshared int sStep;

float lodHeight(int ix, int iz)
{
    ix = clamp(ix, 0, 15);
    iz = clamp(iz, 0, 15);
    return gHeights[gPush.heightBase + iz * 16 + ix];
}

[shader("compute")]
[NumThreads(8, 8, 1)]
void lodBuild(in uint3 tid : SV_DispatchThreadID, in uint3 ltid : SV_GroupThreadID)
{
    int step = 1 << gPush.lod;               // 1, 2, 4, 8
    int grid = 16 / step;                    // Zellen pro Achse
    uint flat = ltid.y * 8 + ltid.x;         // 0..63
    uint vTotal = (grid + 1) * (grid + 1);   // max 289, Threads loopen mit Stride

    // Erster Thread reserviert Meshlet+Cluster und schreibt Header + Indices
    if (flat == 0)
    {
        uint vSlot, iSlot, mSlot;
        uint tCount = grid * grid * 2;
        InterlockedAdd(gAllocator[0], vTotal, vSlot);
        InterlockedAdd(gAllocator[1], tCount * 3, iSlot);
        InterlockedAdd(gAllocator[2], 1, mSlot);
        MeshletHeader mh;
        mh.vertexStart = vSlot;
        mh.vertexCount = vTotal;
        mh.indexStart = iSlot;
        mh.triCount = tCount;
        mh.clusterIdx = gPush.outCluster;
        mh.originX = 0;
        mh.originY = 0;
        mh.originZ = 0;
        mh.boundCenter = float3(gPush.chunkX * 16 + 8, 64, gPush.chunkZ * 16 + 8);
        mh.boundRadius = 32.0;
        mh.coneAxis = float3(0, 0, 0); // kein Backface-Test (Heightfield)
        mh.coneCutoff = 0.0;
        gMeshletOut[mSlot] = mh;
        Cluster cl;
        cl.center = mh.boundCenter;
        cl.lodError = float(step) * 2.0;
        cl.extent = float3(8, 64, 8);
        cl.meshletStart = mSlot;
        cl.coneAxis = float3(0, 1, 0);
        cl.meshletCount = 1;
        cl.coneCutoff = -1.0;
        cl.flags = 2; // Fernfeld-LOD
        gClusterOut[gPush.outCluster] = cl;
        // Indices (Topologie haengt nicht von Hoehen ab)
        for (int gz = 0; gz < grid; gz++)
            for (int gx = 0; gx < grid; gx++)
            {
                uint q = gz * grid + gx;
                uint v0 = vSlot + gz * (grid + 1) + gx;
                gIndexOut[iSlot + q * 6 + 0] = v0;
                gIndexOut[iSlot + q * 6 + 1] = v0 + grid + 1;
                gIndexOut[iSlot + q * 6 + 2] = v0 + 1;
                gIndexOut[iSlot + q * 6 + 3] = v0 + 1;
                gIndexOut[iSlot + q * 6 + 4] = v0 + grid + 1;
                gIndexOut[iSlot + q * 6 + 5] = v0 + grid + 2;
            }
        sV0 = vSlot;
        sGrid = grid;
        sStep = step;
    }
    GroupMemoryBarrierWithGroupSync();

    // Vertices kooperativ: jeder Thread mehrere (Stride 64)
    for (uint vi = flat; vi < vTotal; vi += 64)
    {
        int gx = int(vi % (sGrid + 1));
        int gz = int(vi / (sGrid + 1));
        int sx = gx * sStep;
        int sz = gz * sStep;
        float h = lodHeight(sx, sz);
        float hx = lodHeight(sx + sStep, sz) - lodHeight(sx - sStep, sz);
        float hz = lodHeight(sx, sz + sStep) - lodHeight(sx, sz - sStep);
        float3 n = normalize(float3(-hx / (2.0 * sStep), 1.0, -hz / (2.0 * sStep)));
        float4 c = gColors[gPush.heightBase + clamp(sz, 0, 15) * 16 + clamp(sx, 0, 15)];
        PackedVertex pv;
        pv.pos = float3(gPush.chunkX * 16 + sx, h, gPush.chunkZ * 16 + sz);
        pv.normal = n;
        pv.uv = float2(sx / 16.0, sz / 16.0);
        pv.color = float4(c.rgb, 0.0);
        pv.light = float2(0.0, 1.0);
        pv.ao = 1.0;
        gVertOut[sV0 + vi] = pv;
    }
}
