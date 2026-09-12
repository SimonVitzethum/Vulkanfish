// lod_mesh.slang – Fernfeld (LOD-Voxel): ein Meshlet = bis zu 32 Quads a 8 Byte, der
// Mesh-Shader spannt daraus 4 Vertices / 2 Dreiecke je Quad auf und schreibt in denselben
// G-Buffer wie das Nahfeld -> identisches Licht, Nebel, Himmel (gleicher Look).
// Quads in vom Nahfeld gezeichneten Chunks werden verworfen (Abdeckungsmaske).
#include "gpu_scene.slang"
#include "look.slang"

[[vk::binding(0, 0)]] StructuredBuffer<MeshletHeader> gMeshlets; // vertexStart = erstes Quad, indexStart = Stufe
[[vk::binding(1, 0)]] StructuredBuffer<uint2> gQuads;
[[vk::binding(2, 0)]] StructuredBuffer<uint> gNearMask;          // je Chunk (& 127, toroidal) Section-Bits ab minSectionY
[[vk::binding(3, 0)]] ConstantBuffer<FrameUniforms> gFrame;
[[vk::binding(6, 0)]] StructuredBuffer<uint> gVisibleMeshlets;

struct LodPush
{
    int2 camChunk;
    int maskValid;
    int minSectionY;
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
    float3 inner : TEXCOORD5;      // Punkt im Voxel hinter der Flaeche (Abdeckungstest je Pixel)
};

struct LodPrimOut
{
    bool cull : SV_CullPrimitive;
};

static const float3 FACE_N[6] = { float3(1, 0, 0), float3(-1, 0, 0), float3(0, 1, 0),
                                  float3(0, -1, 0), float3(0, 0, 1), float3(0, 0, -1) };

// Vom Nahfeld gezeichnete Sections des Chunks (0 = nichts / ausserhalb des Fensters)
uint nearBits(int2 c)
{
    if (gLod.maskValid == 0 || any(abs(c - gLod.camChunk) >= 64))
        return 0u;
    return gNearMask[uint((c.y & 127) * 128 + (c.x & 127))];
}

// Section-Bits von y0..y1 (Bloecke); unter/ueber dem Bereich liegt nichts -> gilt als abgedeckt
uint sectionRange(float y0, float y1)
{
    int s0 = max(int(floor(y0 / 16.0)) - gLod.minSectionY, 0);
    int s1 = min(int(floor(y1 / 16.0)) - gLod.minSectionY, 31);
    if (s1 < s0)
        return 0u;
    uint hi = s1 >= 31 ? 0xFFFFFFFFu : ((1u << uint(s1 + 1)) - 1u);
    return hi & ~((1u << uint(s0)) - 1u);
}

// Box komplett in gezeichneten Sections? (Quad samt dahinterliegendem Voxel)
bool nearCoveredBox(float3 mn, float3 mx)
{
    if (gLod.maskValid == 0)
        return false;
    int2 c0 = int2(floor(mn.xz / 16.0)), c1 = int2(floor(mx.xz / 16.0));
    if (any(c1 - c0 > 3))
        return false;
    uint need = sectionRange(mn.y, mx.y);
    for (int z = c0.y; z <= c1.y; z++)
        for (int x = c0.x; x <= c1.x; x++)
            if ((nearBits(int2(x, z)) & need) != need)
                return false;
    return true;
}

// Punkt in einer gezeichneten Section?
bool nearCoveredPoint(float3 p)
{
    uint b = nearBits(int2(floor(p.xz / 16.0)));
    int s = int(floor(p.y / 16.0)) - gLod.minSectionY;
    return s >= 0 && s < 32 && ((b >> uint(s)) & 1u) != 0;
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
    float3 back = -n * min(0.5, scale * 0.5); // in den Voxel, dem die Flaeche gehoert
    for (uint c = 0; c < 4; c++)
    {
        uint vi = lt * 4 + c;
        verts[vi].pos = mul(f.viewProj, float4(corners[c], 1.0));
        verts[vi].inner = corners[c] + back;
        verts[vi].color = color;
        verts[vi].normal = n;
        verts[vi].light = float2(kind == LOD_KIND_WATER ? 0.0 : float(emission) / 15.0, sky); // Wasser: Bits = Tiefe
        verts[vi].mat = mat | (emission << 4);
    }
    uint b = lt * 4;
    tris[lt * 2] = uint3(b, b + 1, b + 2);
    tris[lt * 2 + 1] = uint3(b, b + 2, b + 3);
    // ganz vom Nahfeld gezeichnet -> verwerfen; teilweise -> je Pixel im Fragment-Shader
    float3 qa = p0 - n * scale, qb = p0 + du + dv;
    float3 qmn = min(qa, qb) + 0.01, qmx = max(qa, qb) - 0.01;
    bool cull = nearCoveredBox(qmn, qmx);
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
    float3 inner : TEXCOORD5;
};

struct LodGBufferOut
{
    float4 albedoMat : SV_Target0;
    float4 normalLight : SV_Target1;
};

[shader("fragment")]
LodGBufferOut lodFrag(LodFragIn input)
{
    // Pixel in einer vom Nahfeld gezeichneten Section: dort gilt das Nahfeld (keine Doppelflaechen,
    // keine Loecher an Chunk-Grenzen, die ein Quad nur teilweise ueberdeckt)
    if (nearCoveredPoint(input.inner))
        discard;
    LodGBufferOut o;
    o.albedoMat = float4(input.color, float(input.mat) / 255.0);
    o.normalLight = float4(octEncode(input.normal), input.light.x, input.light.y);
    return o;
}
