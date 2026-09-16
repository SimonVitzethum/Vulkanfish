// drawcmd.slang – Classic-Raster-Fallback (ohne Mesh-Shader): aus kompaktierten
// Sichtbarkeitslisten (Cull, Compute) VkDrawIndirectCommand-Eintraege bauen
// (non-indexed, Vertex-Pulling). Ein Draw je sichtbarem Meshlet, firstInstance =
// Listenplatz. Dispatch-Raster = Cull-Raster (Cluster-Anzahl, garantiert >=
// Meshlet-Kapazitaet), Rest per Zaehler-Fruehausstieg. Barrieren wie beim Cull.
#include "gpu_scene.slang"

[[vk::binding(0, 0)]] StructuredBuffer<uint> gVisible;      // kompaktierte Meshlet-Slots
[[vk::binding(1, 0)]] StructuredBuffer<MeshletHeader> gHeaders;
[[vk::binding(2, 0)]] StructuredBuffer<uint> gCountIn;      // x des Cull-Indirect (Anzahl)
[[vk::binding(3, 0)]] RWStructuredBuffer<uint> gCmds;       // je 4 uints: count, 1, 0, Listenplatz

struct DrawPush
{
    uint mode; // 0 = Dreiecks-Suppe (triCount*3), 1 = Quads (qCount*6)
    uint pad0, pad1, pad2;
};
[[vk::push_constant]] ConstantBuffer<DrawPush> gP;

[shader("compute")]
[NumThreads(64, 1, 1)]
void buildDrawCommands(in uint3 tid : SV_DispatchThreadID)
{
    uint s = tid.x;
    if (s >= gCountIn[0])
        return;
    uint slot = gVisible[s];
    MeshletHeader mh = gHeaders[slot];
    uint n = gP.mode == 0 ? min(mh.triCount, 124u) * 3u : min(mh.vertexCount, 32u) * 6u;
    gCmds[s * 4] = n;
    gCmds[s * 4 + 1] = 1u;
    gCmds[s * 4 + 2] = 0u;
    gCmds[s * 4 + 3] = s;
}
