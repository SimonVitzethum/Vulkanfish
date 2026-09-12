// entity_task.slang – Task-Stage fuer Entities: verteilt sichtbare Instanzen
// (aus entity_cull kompaktiert) auf Mesh-Gruppen, je 1 Instanz pro Gruppe.
// Konvention: 1 Task-Gruppe (32 Threads) = 32 Instanzen. Uniformer Dispatch.
//
// STATUS: siehe terrain_task.slang (slangc-SPIR-V-Task-Bug). Produktion nutzt
// entityMeshDirect (Mesh-only, gid.x = Slot). Datei bleibt als Referenz,
// bis der Slang-Bug gefixt ist.
#include "gpu_scene.slang"

[[vk::binding(0, 0)]] StructuredBuffer<uint> gVisibleCount; // gCounter aus entity_cull

[shader("amplification")]
[NumThreads(32, 1, 1)]
void entityTask(
    in uint3 gid : SV_GroupID,
    in uint3 ltid : SV_GroupThreadID,
    out EntityPayload payload)
{
    uint total = gVisibleCount[0];
    uint base = gid.x * 32;
    uint remaining = total > base ? (total - base) : 0;
    uint n = min(remaining, 32);

    if (ltid.x < n)
        payload.instanceIndices[ltid.x] = base + ltid.x;
    if (ltid.x == 0)
        payload.count = n;
    GroupMemoryBarrierWithGroupSync();

    DispatchMesh(n, 1, 1, payload);
}
