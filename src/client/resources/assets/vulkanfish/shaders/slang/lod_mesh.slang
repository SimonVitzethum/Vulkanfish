// lod_mesh.slang – Fernfeld (LOD-Voxel): ein Meshlet = bis zu 32 Quads a 8 Byte, der
// Mesh-Shader spannt daraus 4 Vertices / 2 Dreiecke je Quad auf und schreibt in denselben
// G-Buffer wie das Nahfeld -> identisches Licht, Nebel, Himmel (gleicher Look).
// Quads in vom Nahfeld gezeichneten Chunks werden verworfen (Abdeckungsmaske).
#include "gpu_scene.slang"
#include "look.slang"

[[vk::binding(0, 0)]] StructuredBuffer<MeshletHeader> gMeshlets; // vertexStart = erstes Quad, indexStart = Stufe
[[vk::binding(1, 0)]] StructuredBuffer<uint2> gQuads;
[[vk::binding(2, 0)]] StructuredBuffer<uint> gNearMask;          // 128x128 Bit, toroidal (Chunk & 127)
[[vk::binding(3, 0)]] ConstantBuffer<FrameUniforms> gFrame;
[[vk::binding(6, 0)]] StructuredBuffer<uint> gVisibleMeshlets;

struct LodPush
{
    int2 camChunk;
    int maskValid;
    int pad;
};
[[vk::push_constant]] ConstantBuffer<LodPush> gLod;

static const uint LOD_KIND_LEAVES = 2;
static const uint LOD_KIND_WATER = 4;
static const uint LOD_KIND_LAVA = 5;

struct LodVertOut
{
    float4 pos : SV_Position;
    nointerpolation float3 color;  // Gamma
    nointerpolation float3 normal;
    nointerpolation float2 light;  // Emission, Himmel (0..1)
    nointerpolation uint mat;      // Material | Emission << 4 (wie das Nahfeld)
};

struct LodPrimOut
{
    bool cull : SV_CullPrimitive;
};

static const float3 FACE_N[6] = { float3(1, 0, 0), float3(-1, 0, 0), float3(0, 1, 0),
                                  float3(0, -1, 0), float3(0, 0, 1), float3(0, 0, -1) };

bool nearCovered(float2 worldXZ)
{
    if (gLod.maskValid == 0)
        return false;
    int2 c = int2(floor(worldXZ / 16.0));
    int2 d = c - gLod.camChunk;
    if (any(abs(d) >= 64))
        return false;
    uint bit = uint((c.y & 127) * 128 + (c.x & 127));
    return (gNearMask[bit >> 5] & (1u << (bit & 31))) != 0;
}

[shader("mesh")]
[NumThreads(32, 1, 1)]
[outputtopology("triangle")]
void lodMesh(
    in uint3 gid : SV_GroupID,
    in uint3 ltid : SV_GroupThreadID,
    out vertices LodVertOut verts[128],
    out indices uint3 tris[64],
    out primitives LodPrimOut prims[64])
{
    uint lt = ltid.x;
    MeshletHeader mh = gMeshlets[gVisibleMeshlets[gid.x]];
    FrameUniforms f = gFrame;
    uint qCount = min(mh.vertexCount, 32);
    SetMeshOutputCounts(qCount * 4, qCount * 2);
    if (lt >= qCount)
        return;
    uint2 q = gQuads[mh.vertexStart + lt];
    uint level = mh.indexStart;
    float scale = float(1u << level);
    float3 origin = float3(mh.originX, mh.originY, mh.originZ);

    uint px = q.x & 31, pz = (q.x >> 5) & 31, py = (q.x >> 10) & 511;
    uint face = (q.x >> 19) & 7;
    float su = float(((q.x >> 22) & 31) + 1), sv = float(((q.x >> 27) & 31) + 1);
    uint axis = face >> 1;
    bool positive = (face & 1) == 0;

    // Ebene auf der Aussenseite des Voxels, Rechteck in (u, v)
    float3 base = float3(px, py, pz);
    float3 du, dv;
    if (axis == 0) { if (positive) base.x += 1.0; du = float3(0, 0, su); dv = float3(0, sv, 0); }
    else if (axis == 1) { if (positive) base.y += 1.0; du = float3(su, 0, 0); dv = float3(0, 0, sv); }
    else { if (positive) base.z += 1.0; du = float3(su, 0, 0); dv = float3(0, sv, 0); }
    float3 p0 = origin + base * scale;
    du *= scale;
    dv *= scale;

    uint rgb = q.y & 0x1FFFFF;
    float3 color = float3(float(rgb & 0x7F), float((rgb >> 7) & 0x7F), float((rgb >> 14) & 0x7F)) / 127.0;
    float sky = float((q.y >> 21) & 0xF) / 15.0;
    uint emission = (q.y >> 25) & 0xF;
    uint kind = (q.y >> 29) & 0x7;
    uint mat = kind == LOD_KIND_LEAVES ? MAT_LEAVES : kind == LOD_KIND_LAVA ? MAT_LAVA : kind == LOD_KIND_WATER ? MAT_WATER : MAT_DEFAULT;
    float3 n = FACE_N[face];

    float3 corners[4] = { p0, p0 + du, p0 + du + dv, p0 + dv };
    for (uint c = 0; c < 4; c++)
    {
        uint vi = lt * 4 + c;
        verts[vi].pos = mul(f.viewProj, float4(corners[c], 1.0));
        verts[vi].color = color;
        verts[vi].normal = n;
        verts[vi].light = float2(float(emission) / 15.0, sky);
        verts[vi].mat = mat | (emission << 4);
    }
    uint b = lt * 4;
    tris[lt * 2] = uint3(b, b + 1, b + 2);
    tris[lt * 2 + 1] = uint3(b, b + 2, b + 3);
    bool cull = nearCovered((p0 + (du + dv) * 0.5).xz);
    prims[lt * 2].cull = cull;
    prims[lt * 2 + 1].cull = cull;
}

struct LodFragIn
{
    float4 pos : SV_Position;
    nointerpolation float3 color;
    nointerpolation float3 normal;
    nointerpolation float2 light;
    nointerpolation uint mat;
};

struct LodGBufferOut
{
    float4 albedoMat : SV_Target0;
    float4 normalLight : SV_Target1;
};

[shader("fragment")]
LodGBufferOut lodFrag(LodFragIn input)
{
    LodGBufferOut o;
    o.albedoMat = float4(input.color, float(input.mat) / 255.0);
    o.normalLight = float4(octEncode(input.normal), input.light.x, input.light.y);
    return o;
}
