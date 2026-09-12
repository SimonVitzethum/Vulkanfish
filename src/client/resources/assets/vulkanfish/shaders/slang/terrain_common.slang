// terrain_common.slang – gemeinsamer Teil aller Terrain-Mesh-Shader (G-Buffer,
// Schatten, Wasser): Bindings 0/1/2/3/6 sind in allen Terrain-Layouts gleich.
#ifndef VULKANFISH_TERRAIN_COMMON_SLANG
#define VULKANFISH_TERRAIN_COMMON_SLANG

#include "gpu_scene.slang"
#include "look.slang"

[[vk::binding(0, 0)]] StructuredBuffer<MeshletHeader> gMeshlets;
[[vk::binding(1, 0)]] StructuredBuffer<TerrainVertex> gVertices;
[[vk::binding(2, 0)]] StructuredBuffer<uint> gTris;      // gepackte Dreiecke
[[vk::binding(3, 0)]] ConstantBuffer<FrameUniforms> gFrame;
[[vk::binding(6, 0)]] StructuredBuffer<uint> gVisibleMeshlets; // kompaktiert vom Cull

static const uint MESH_THREADS = 64;
groupshared float3 sQuadNormal[16];

// Welt-Position inkl. Wind, Normale aus dem Quad-Cache.
void loadVertex(MeshletHeader mh, uint lt, FrameUniforms f, out TerrainVertex tv, out float3 wp, out float3 n)
{
    float3 origin = float3(mh.originX, mh.originY, mh.originZ);
    tv = gVertices[mh.vertexStart + lt];
    wp = tvPos(tv, origin);
    n = sQuadNormal[lt >> 2];
    uint mat = (tv.color >> 24) & 0xF;
    if (isFoliage(mat))
    {
        float amount = mat == MAT_PLANT_TOP ? 0.10 : (mat == MAT_PLANT ? 0.0 : 0.035);
        wp += windOffset(wp, f.time, amount, f.rainFactor);
    }
}

// Normalen pro Quad einmal rechnen (statt 4 Vertex-Loads pro Vertex)
void computeQuadNormals(MeshletHeader mh, uint lt, uint vCount)
{
    if (lt < vCount && (lt & 3) == 0)
    {
        float3 origin = float3(mh.originX, mh.originY, mh.originZ);
        uint q0 = mh.vertexStart + lt;
        float3 p0 = tvPos(gVertices[q0], origin);
        float3 p1 = tvPos(gVertices[q0 + 1], origin);
        float3 p2 = tvPos(gVertices[q0 + 2], origin);
        float3 n = cross(p1 - p0, p2 - p0);
        if (dot(n, n) < 1e-10)
            n = cross(p2 - p0, tvPos(gVertices[q0 + 3], origin) - p0);
        sQuadNormal[lt >> 2] = dot(n, n) < 1e-10 ? float3(0, 1, 0) : normalize(n);
    }
    GroupMemoryBarrierWithGroupSync();
}

#endif
