// entity_mesh.slang – Mesh-Stage fuer Entities.
// Eine Mesh-Gruppe = eine Instanz. Archetyp-Geometrie kommt aus demselben
// Meshlet-Speicher (pro Archetyp ein Meshlet-Block); hier als parametrischer
// Pfad mit Instanz-Transform + Complementary-Tint. 30k Instanzen = 1 Draw.
#include "gpu_scene.slang"
#include "look.slang"

[[vk::binding(0, 0)]] StructuredBuffer<EntityInstance> gInstances;
[[vk::binding(1, 0)]] StructuredBuffer<uint> gVisible;
[[vk::binding(2, 0)]] StructuredBuffer<MeshletHeader> gArchMeshlets;
[[vk::binding(3, 0)]] StructuredBuffer<PackedVertex> gArchVertices;
[[vk::binding(4, 0)]] StructuredBuffer<uint> gArchIndices;
[[vk::binding(5, 0)]] ConstantBuffer<FrameUniforms> gFrame;

struct EntVertOut
{
    float4 pos : SV_Position;
    float3 normal;
    float4 tint;
    float emissive;
};

[shader("mesh")]
[NumThreads(32, 1, 1)]
[outputtopology("triangle")]
void entityMesh(
    in EntityPayload payload,
    in uint3 meshTaskId : SV_GroupID,
    out vertices EntVertOut verts[24],
    out indices uint3 tris[12])
{
    uint instIdx = gVisible[payload.instanceIndices[meshTaskId.x]];
    EntityInstance inst = gInstances[instIdx];
    FrameUniforms f = gFrame;

    // Archetyp-Box (Platzhalter-Geometrie; echte Archetyp-Meshlets folgen
    // demselben Meshlet-Layout, Index = inst.archetype)
    float3x4 m = inst.model;
    float3 corners[8];
    corners[0] = float3(inst.aabbMin.x, inst.aabbMin.y, inst.aabbMin.z);
    corners[1] = float3(inst.aabbMax.x, inst.aabbMin.y, inst.aabbMin.z);
    corners[2] = float3(inst.aabbMax.x, inst.aabbMax.y, inst.aabbMin.z);
    corners[3] = float3(inst.aabbMin.x, inst.aabbMax.y, inst.aabbMin.z);
    corners[4] = float3(inst.aabbMin.x, inst.aabbMin.y, inst.aabbMax.z);
    corners[5] = float3(inst.aabbMax.x, inst.aabbMin.y, inst.aabbMax.z);
    corners[6] = float3(inst.aabbMax.x, inst.aabbMax.y, inst.aabbMax.z);
    corners[7] = float3(inst.aabbMin.x, inst.aabbMax.y, inst.aabbMax.z);
    SetMeshOutputCounts(8, 12);
    for (uint i = 0; i < 8; i++)
    {
        float3 wp = mul(m, float4(corners[i], 1.0));
        verts[i].pos = mul(f.viewProj, float4(wp, 1.0));
        verts[i].normal = float3(0.0, 1.0, 0.0);
        verts[i].tint = inst.tint_flags;
        verts[i].emissive = inst.emissive;
    }
    // 12 Wuerfel-Tris ueber 8 Ecken (vereinfachte Box-Topologie)
    tris[0] = uint3(0, 1, 2); tris[1] = uint3(0, 2, 3);
    tris[2] = uint3(4, 6, 5); tris[3] = uint3(4, 7, 6);
    tris[4] = uint3(0, 4, 5); tris[5] = uint3(0, 5, 1);
    tris[6] = uint3(2, 6, 7); tris[7] = uint3(2, 7, 3);
    tris[8] = uint3(0, 3, 7); tris[9] = uint3(0, 7, 4);
    tris[10] = uint3(1, 5, 6); tris[11] = uint3(1, 6, 2);
}

struct EntFragIn
{
    float4 pos : SV_Position;
    float3 normal;
    float4 tint;
    float emissive;
};

[shader("fragment")]
float4 entityFrag(EntFragIn input) : SV_Target0
{
    float3 c = input.tint.rgb + input.emissive * float3(1.0, 0.9, 0.7);
    return float4(c, input.tint.a);
}

// Fragment zum Mesh-only-Archetyp-Pfad (Varyings muessen zum Mesh-Output passen)
struct ArchFragIn
{
    float4 pos : SV_Position;
    float3 normal;
    float4 color;
};

[shader("fragment")]
float4 entityFragDirect(ArchFragIn input) : SV_Target0
{
    return float4(input.color.rgb, 1.0);
}

// ---- Mesh-only-Produktionspfad: gid.x = Slot in kompaktierter Liste ----
// Geometrie kommt aus dem Archetyp-Speicher (ein Meshlet pro Archetyp, v1),
// Transform + Tint aus der Instanz. Echte EntityModel-Boxen werden per
// ArchetypeStore.bakeFromBoxes CPU-seitig gebacken und per Transfer-Queue
// hochgeladen (einmalig, kein Per-Frame-CPU).
[[vk::binding(6, 0)]] StructuredBuffer<uint2> gArchRanges; // pro Archetyp: (meshletStart, count)

struct ArchVertOut
{
    float4 pos : SV_Position;
    float3 normal;
    float4 color;
};

[shader("mesh")]
[NumThreads(64, 1, 1)]
[outputtopology("triangle")]
void entityMeshDirect(
    in uint3 gid : SV_GroupID,
    in uint3 ltid : SV_GroupThreadID,
    out vertices ArchVertOut verts[64],
    out indices uint3 tris[124])
{
    uint instIdx = gVisible[gid.x];
    EntityInstance inst = gInstances[instIdx];
    FrameUniforms f = gFrame;
    uint meshletIdx = gArchRanges[inst.archetype].x; // v1: 1 Meshlet pro Archetyp
    MeshletHeader mh = gArchMeshlets[meshletIdx];
    uint vCount = min(mh.vertexCount, 64);
    uint tCount = min(mh.triCount, 124);
    SetMeshOutputCounts(vCount, tCount);

    float3x4 m = inst.model;
    float3 nrmMat0 = normalize(float3(m[0][0], m[0][1], m[0][2]));
    float3 nrmMat1 = normalize(float3(m[1][0], m[1][1], m[1][2]));
    float3 nrmMat2 = normalize(float3(m[2][0], m[2][1], m[2][2]));
    uint flat = ltid.y * 64 + ltid.x; // 0..63 bei 64 Threads
    for (uint v = flat; v < vCount; v += 64)
    {
        PackedVertex pv = gArchVertices[mh.vertexStart + v];
        float3 wp = mul(m, float4(pv.pos, 1.0));
        verts[v].pos = mul(f.viewProj, float4(wp, 1.0));
        verts[v].normal = normalize(nrmMat0 * pv.normal.x + nrmMat1 * pv.normal.y + nrmMat2 * pv.normal.z);
        verts[v].color = float4(pv.color.rgb * inst.tint_flags.rgb
                                + inst.emissive * float3(1.0, 0.9, 0.7), 1.0);
    }
    GroupMemoryBarrierWithGroupSync();
    for (uint t = flat; t < tCount; t += 64)
    {
        uint i0 = gArchIndices[mh.indexStart + t * 3];
        uint i1 = gArchIndices[mh.indexStart + t * 3 + 1];
        uint i2 = gArchIndices[mh.indexStart + t * 3 + 2];
        tris[t] = uint3(i0, i1, i2);
    }
}
