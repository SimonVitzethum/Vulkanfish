// terrain_task.slang – Task/Amplification: Frustum + Hi-Z + Cone + LOD Culling.
// Konvention: 1 Task-Gruppe (32 Threads) = 1 Cluster.
// Thread 0 macht Cluster-Tests, alle Threads testen je ein Meshlet und
// kompaktieren Treffer. DispatchMesh wird UNIFORM aufgerufen. 0 CPU im Hot Path.
//
// STATUS: valide Slang-Quelle (HLSL-Target kompiliert, DispatchMesh mit Payload
// verifiziert), aber slangc 2026.17.1 segfaultet im SPIR-V-Backend bei JEDER
// Amplification-Stage (minimaler Repro: 5-Zeiler mit DispatchMesh). Darum ist
// diese Datei NICHT im shaders.json; Produktion nutzt meshlet_cull.slang
// (Compute) + terrainMeshDirect (Mesh-only) + IndirectCount. Sobald der
// Slang-Bug gefixt ist, diese Stage wieder einklinken (1 Gruppe = 1 Cluster,
// Indirect.x = Clusterzahl).
#include "gpu_scene.slang"

[[vk::binding(0, 0)]] StructuredBuffer<Cluster> gClusters;
[[vk::binding(1, 0)]] StructuredBuffer<MeshletHeader> gMeshlets;
[[vk::binding(2, 0)]] ConstantBuffer<FrameUniforms> gFrame;
[[vk::binding(3, 0)]] Texture2D<float> gHiz;
[[vk::binding(4, 0)]] SamplerState gHizSamp;
[[vk::binding(5, 0)]] StructuredBuffer<uint> gClusterCount; // 1 uint

groupshared uint sVisible;   // Cluster bestanden?
groupshared uint sWritePos;  // Kompaktierungs-Zaehler

bool sphereFrustum(float3 c, float r, float4 planes[6])
{
    for (uint i = 0; i < 6; i++)
    {
        if (dot(planes[i].xyz, c) + planes[i].w < -r)
            return false;
    }
    return true;
}

// Hi-Z-Test gegen Vorframe-Pyramide (1 Frame Latenz, dafuer keine Bubble)
bool hizVisible(float3 c, float r, FrameUniforms f)
{
    float4 clip = mul(f.viewProj, float4(c, 1.0));
    if (clip.w <= 0.01)
        return true; // hinter Kamera -> konservativ behalten
    float3 ndc = clip.xyz / clip.w;
    if (ndc.x < -1.3 || ndc.x > 1.3 || ndc.y < -1.3 || ndc.y > 1.3)
        return false;
    float2 uv = ndc.xy * 0.5 + 0.5;
    float radiusPx = r * f.screenSize.y / max(clip.w, 1e-3);
    float mip = clamp(ceil(log2(max(radiusPx, 1.0))), 0.0, float(f.hizMipCount - 1));
    float occluder = gHiz.SampleLevel(gHizSamp, uv, mip);
    float depth = ndc.z * 0.5 + 0.5;
    return depth - 0.002 <= occluder;
}

[shader("amplification")]
[NumThreads(32, 1, 1)]
void terrainTask(
    in uint3 tid : SV_DispatchThreadID,
    in uint3 gid : SV_GroupID,
    in uint3 ltid : SV_GroupThreadID,
    out TerrainPayload payload)
{
    uint clusterIdx = gid.x;
    bool inRange = clusterIdx < gClusterCount[0];

    if (ltid.x == 0)
    {
        sVisible = 0;
        sWritePos = 0;
    }
    GroupMemoryBarrierWithGroupSync();

    // --- Cluster-Ebene (Thread 0) ---
    Cluster cl;
    if (ltid.x == 0 && inRange)
    {
        cl = gClusters[clusterIdx];
        FrameUniforms f = gFrame;
        float boundR = length(cl.extent) + cl.lodError;
        bool pass = true;
        // 1. Frustum
        pass = pass && sphereFrustum(cl.center, boundR, f.frustumPlanes);
        // 2. LOD per Screen-Space-Error (Fernfeld-Cluster duenner)
        if (pass)
        {
            float dist = max(distance(cl.center, f.camPos), 0.001);
            float sse = cl.lodError * f.screenSize.y / (2.0 * dist * 0.866);
            float limit = ((cl.flags & 2) != 0) ? f.lodErrorThreshold * 4.0 : f.lodErrorThreshold;
            pass = pass && sse <= limit;
        }
        // 3. Normal-Cone
        if (pass && cl.coneCutoff > -1.0)
            pass = pass && dot(normalize(f.camPos - cl.center), cl.coneAxis) >= cl.coneCutoff;
        // 4. Hi-Z
        if (pass)
            pass = pass && hizVisible(cl.center, boundR, f);
        if (pass)
            sVisible = 1;
        // Cluster fuer Meshlet-Phase in groupshared? -> neu laden unten
    }
    GroupMemoryBarrierWithGroupSync();

    // --- Meshlet-Ebene (alle Threads, je ein Meshlet) ---
    if (sVisible != 0 && ltid.x < MAX_PAYLOAD_MESHLETS)
    {
        Cluster cl2 = gClusters[clusterIdx];
        if (ltid.x < cl2.meshletCount)
        {
            MeshletHeader mh = gMeshlets[cl2.meshletStart + ltid.x];
            FrameUniforms f2 = gFrame;
            bool mpass = sphereFrustum(mh.boundCenter, mh.boundRadius, f2.frustumPlanes);
            if (mpass && mh.coneCutoff > -1.0)
                mpass = dot(normalize(f2.camPos - mh.boundCenter), mh.coneAxis) >= mh.coneCutoff;
            if (mpass)
            {
                uint slot;
                InterlockedAdd(sWritePos, 1, slot);
                if (slot < MAX_PAYLOAD_MESHLETS)
                    payload.meshletIndices[slot] = cl2.meshletStart + ltid.x;
            }
        }
    }
    GroupMemoryBarrierWithGroupSync();
    if (ltid.x == 0)
        payload.count = min(sWritePos, MAX_PAYLOAD_MESHLETS);
    GroupMemoryBarrierWithGroupSync();

    DispatchMesh(payload.count, 1, 1, payload);
}
