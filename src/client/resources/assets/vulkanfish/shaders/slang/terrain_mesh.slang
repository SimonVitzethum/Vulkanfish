// terrain_mesh.slang – Mesh-Stage fuer Terrain: G-Buffer-Pass (Deferred) und
// Schatten-Pass (Tiefe aus Lichtsicht, radial verzerrt). Geometrie kommt vom
// CPU-Mesher (Vanilla-Quads -> TerrainVertex, 16 Byte).
#include "terrain_common.slang"

[[vk::binding(4, 0)]] Texture2D<float4> gAtlas;          // Vanillas Block-Atlas
[[vk::binding(5, 0)]] SamplerState gAtlasSamp;

struct MeshVertOut
{
    float4 pos : SV_Position;
    float2 uv;
    float4 color;          // rgb = Tint*AO (Gamma), a unbenutzt
    float3 normal;
    float2 light;          // x = Block, y = Himmel (0..1)
    nointerpolation uint mat;
};

[shader("mesh")]
[NumThreads(64, 1, 1)]
[outputtopology("triangle")]
void terrainMeshDirect(
    in uint3 gid : SV_GroupID,
    in uint3 ltid : SV_GroupThreadID,
    out vertices MeshVertOut verts[64],
    out indices uint3 tris[124])
{
    uint lt = ltid.x;
    MeshletHeader mh = gMeshlets[gVisibleMeshlets[gid.x]];
    FrameUniforms f = gFrame;
    uint vCount = min(mh.vertexCount, 64);
    uint tCount = min(mh.triCount, 124);
    SetMeshOutputCounts(vCount, tCount);
    computeQuadNormals(mh, lt, vCount);

    if (lt < vCount)
    {
        TerrainVertex tv;
        float3 wp, n;
        loadVertex(mh, lt, f, tv, wp, n);
        verts[lt].pos = mul(f.viewProj, float4(wp, 1.0));
        verts[lt].uv = tvUv(tv);
        verts[lt].color = tvColor(tv);
        verts[lt].normal = n;
        verts[lt].light = tvLight(tv);
        verts[lt].mat = tv.color >> 24; // Material | Emission << 4
    }
    for (uint t = lt; t < tCount; t += MESH_THREADS)
        tris[t] = unpackTri(gTris[mh.indexStart + t]);
}

struct FragIn
{
    float4 pos : SV_Position;
    float2 uv;
    float4 color;
    float3 normal;
    float2 light;
    nointerpolation uint mat;
};

struct GBufferOut
{
    float4 albedoMat : SV_Target0;   // RGBA8: Albedo (Gamma) | Material+Emission
    float4 normalLight : SV_Target1; // RGBA8: Oktaeder-Normale xy | Blocklicht | Himmelslicht
};

[shader("fragment")]
GBufferOut terrainFrag(FragIn input)
{
    float4 tex = gAtlas.Sample(gAtlasSamp, input.uv);
    if (tex.a < 0.5)
        discard; // Cutout wie Vanilla (Blaetter, Gras, Blumen)
    GBufferOut o;
    o.albedoMat = float4(tex.rgb * input.color.rgb, float(input.mat) / 255.0);
    o.normalLight = float4(octEncode(normalize(input.normal)), saturate(input.light));
    return o;
}

// ---------- Schatten-Pass ----------
// gFrame ist hier der Schatten-UBO: viewProj = Ortho-Lichtraum.
struct ShadowVertOut
{
    float4 pos : SV_Position;
    float2 uv;
};

[shader("mesh")]
[NumThreads(64, 1, 1)]
[outputtopology("triangle")]
void terrainShadowMesh(
    in uint3 gid : SV_GroupID,
    in uint3 ltid : SV_GroupThreadID,
    out vertices ShadowVertOut verts[64],
    out indices uint3 tris[124])
{
    uint lt = ltid.x;
    MeshletHeader mh = gMeshlets[gVisibleMeshlets[gid.x]];
    FrameUniforms f = gFrame;
    uint vCount = min(mh.vertexCount, 64);
    uint tCount = min(mh.triCount, 124);
    SetMeshOutputCounts(vCount, tCount);
    computeQuadNormals(mh, lt, vCount);

    if (lt < vCount)
    {
        TerrainVertex tv;
        float3 wp, n;
        loadVertex(mh, lt, f, tv, wp, n);
        float4 clip = mul(f.viewProj, float4(wp, 1.0));
        clip.xy = distortShadow(clip.xy);
        verts[lt].pos = clip;
        verts[lt].uv = tvUv(tv);
    }
    for (uint t = lt; t < tCount; t += MESH_THREADS)
        tris[t] = unpackTri(gTris[mh.indexStart + t]);
}

struct ShadowFragIn
{
    float4 pos : SV_Position;
    float2 uv;
};

[shader("fragment")]
void terrainShadowFrag(ShadowFragIn input)
{
    if (gAtlas.SampleLevel(gAtlasSamp, input.uv, 0).a < 0.5)
        discard;
}
