// entity_cull.slang – Compute: 30k Instanzen frustum-cullen und in
// kompakten Indirect-Draw verwandeln. Ein Draw fuer alle Entities.
#include "gpu_scene.slang"

[[vk::binding(0, 0)]] StructuredBuffer<EntityInstance> gInstances;
[[vk::binding(1, 0)]] ConstantBuffer<FrameUniforms> gFrame;
[[vk::binding(2, 0)]] RWStructuredBuffer<uint> gVisible;      // kompaktierte Indices
[[vk::binding(3, 0)]] RWStructuredBuffer<uint> gCounter;      // Atomik-Zaehler (1 uint)
[[vk::binding(4, 0)]] RWStructuredBuffer<MeshTasksIndirect> gIndirectOut;
[[vk::binding(5, 0)]] StructuredBuffer<uint> gLiveCount;      // 1 uint

bool boxFrustum(float3 mn, float3 mx, float4 planes[6])
{
    for (uint i = 0; i < 6; i++)
    {
        float3 p = float3(planes[i].x > 0.0 ? mx.x : mn.x,
                          planes[i].y > 0.0 ? mx.y : mn.y,
                          planes[i].z > 0.0 ? mx.z : mn.z);
        if (dot(planes[i].xyz, p) + planes[i].w < 0.0)
            return false;
    }
    return true;
}

[shader("compute")]
[NumThreads(64, 1, 1)]
void entityCull(in uint3 tid : SV_DispatchThreadID)
{
    uint idx = tid.x;
    if (idx == 0)
        gCounter[0] = 0;
    GroupMemoryBarrierWithGroupSync();

    uint live = gLiveCount[0];
    if (idx < live)
    {
        EntityInstance inst = gInstances[idx];
        if (boxFrustum(inst.aabbMin, inst.aabbMax, gFrame.frustumPlanes))
        {
            uint slot;
            InterlockedAdd(gCounter[0], 1, slot);
            gVisible[slot] = idx;
        }
    }
    GroupMemoryBarrierWithGroupSync();
    if (idx == 0)
    {
        // 32 Instanzen pro Task-Gruppe, 1 Multi-Draw fuer alles
        gIndirectOut[0].x = (gCounter[0] + 31) / 32;
        gIndirectOut[0].y = 1;
        gIndirectOut[0].z = 1;
    }
}
