// meshlet_cull.slang – Compute (PRODUKTIONSPFAD): cullt jedes Meshlet
// (Frustum + Backface-Ebene + Hi-Z) und kompaktiert sichtbare in
// gVisibleMeshlets. Der Zaehler IST das x des Indirect-Commands
// (vkCmdDrawMeshTasksIndirectEXT), die CPU setzt ihn vor dem Dispatch auf
// {0,1,1} (vkCmdFillBuffer) – kein Workgroup-uebergreifendes Reset-Race.
//
// Hintergrund: slangc 2026.17.1 stuerzt bei der Task/Amplification-Stage im
// SPIR-V-Backend ab (segfault, auch bei trivialem DispatchMesh; HLSL-Target
// funktioniert). Bis das gefixt ist, ersetzt dieser Compute-Pass die Task-
// Stage 1:1 – das Culling bleibt zu 100 % auf der GPU, Mesh-Shader-only.
#include "gpu_scene.slang"

[[vk::binding(0, 0)]] StructuredBuffer<Cluster> gClusters;
[[vk::binding(1, 0)]] StructuredBuffer<MeshletHeader> gMeshlets;
[[vk::binding(2, 0)]] ConstantBuffer<FrameUniforms> gFrame;
[[vk::binding(3, 0)]] Texture2D<float> gHiz;                 // R32F, min-Reduktion, Reverse-Z
[[vk::binding(4, 0)]] SamplerState gHizSamp;                 // (Load-basiert, Binding bleibt)
[[vk::binding(5, 0)]] StructuredBuffer<uint> gMeshletCount;  // 1 uint: belegte Cluster-Slots (Dispatch-Umfang)
[[vk::binding(6, 0)]] RWStructuredBuffer<uint> gVisible;     // kompaktiert
[[vk::binding(7, 0)]] RWStructuredBuffer<uint> gVisibleCount;// 1 uint (Statistik)
[[vk::binding(8, 0)]] RWStructuredBuffer<uint> gIndirectOut; // {x, y, z}, x = Atomik-Zaehler
[[vk::binding(9, 0)]] RWStructuredBuffer<uint> gVisibleWater; // kompaktierte Wasser-Meshlets
[[vk::binding(10, 0)]] RWStructuredBuffer<uint> gWaterIndirect;
[[vk::binding(11, 0)]] RWStructuredBuffer<uint> gVisibleTrans;  // Glas/Eis
[[vk::binding(12, 0)]] RWStructuredBuffer<uint> gTransIndirect;
[[vk::binding(13, 0)]] RWStructuredBuffer<uint> gVisBits;      // pro Meshlet-Slot: im letzten Frame sichtbar
[[vk::binding(14, 0)]] StructuredBuffer<uint> gNearMask;       // LOD: je Chunk (128x128 toroidal) gezeichnete Sections

// Zwei-Phasen-Occlusion-Culling (Kamera):
//  Phase 1: nur, was im LETZTEN Frame sichtbar war (Frustum + Backface) -> G-Buffer -> Hi-Z
//           aus dieser AKTUELLEN Tiefe.
//  Phase 2: alle Meshlets gegen die aktuelle Hi-Z; neu sichtbare nachzeichnen, Sichtbarkeit
//           fuer den naechsten Frame merken. Wasser/Glas nur hier.
//  -> keine Loecher bei Disocclusion (Vorframe-Tiefe als Occluder waere falsch, sobald
//     sich die Kamera bewegt). Phase 0 = Schatten-Cull (ohne Hi-Z, wie bisher).
struct CullPush
{
    uint phase;
    uint nearMode;         // LOD: Bit 0 = Meshlets in gezeichneten Nahfeld-Sections verwerfen, darueber minSectionY + 2048
    int camChunkX, camChunkZ;
    float minTopY;         // Meshlets ganz unterhalb verwerfen (LOD-Schattenwerfer: nur Luecken des Nahfelds)
    float maxDistXZ;       // ... und waagerecht weiter als das von der Kamera (Chunk camChunkX/Z) entfernt
    uint pad1, pad2;
};
[[vk::push_constant]] ConstantBuffer<CullPush> gCull;

static const uint WATER_BIT = 0x80000000u;
static const uint TRANS_BIT = 0x40000000u;

bool sphereFrustum(float3 c, float r, float4 planes[6])
{
    for (uint i = 0; i < 6; i++)
    {
        if (dot(planes[i].xyz, c) + planes[i].w < -r)
            return false;
    }
    return true;
}

// Hi-Z stammt aus dem Vorframe -> im Vorframe-Clipraum testen. Reverse-Z:
// groesser = naeher, die Pyramide haelt pro Texel die FERNSTE (kleinste) Tiefe.
// Verdeckt, wenn der naechste Punkt der Box ferner ist als der fernste Occluder.
bool hizVisible(float3 c, float r, FrameUniforms f)
{
    if (f.hizMipCount == 0)
        return true;
    float2 mn = float2(1e9, 1e9);
    float2 mx = float2(-1e9, -1e9);
    float zNearest = 0.0;
    for (uint i = 0; i < 8; i++)
    {
        float3 corner = c + r * float3((i & 1) != 0 ? 1.0 : -1.0,
                                       (i & 2) != 0 ? 1.0 : -1.0,
                                       (i & 4) != 0 ? 1.0 : -1.0);
        float4 cp = mul(f.prevViewProj, float4(corner, 1.0));
        if (cp.w <= 0.05)
            return true; // schneidet die Near-Plane
        float3 ndc = cp.xyz / cp.w;
        float2 uv = ndc.xy * 0.5 + 0.5;
        mn = min(mn, uv);
        mx = max(mx, uv);
        zNearest = max(zNearest, ndc.z);
    }
    // Teilweise ausserhalb des Vorframe-Bildes: keine Aussage -> sichtbar
    if (mn.x < 0.0 || mn.y < 0.0 || mx.x > 1.0 || mx.y > 1.0)
        return true;
    float2 sizePx = (mx - mn) * f.screenSize;
    uint mip = uint(clamp(ceil(log2(max(max(sizePx.x, sizePx.y), 1.0))), 0.0, float(f.hizMipCount - 1)));
    uint w, h, levels;
    gHiz.GetDimensions(mip, w, h, levels);
    int2 lim = int2(w, h) - 1;
    // Texel ueber die PIXEL-Koordinate waehlen (Texel i in Mip m deckt Pixel [i*2^m, (i+1)*2^m)
    // ab, der letzte bei ungerader Groesse eine Spalte mehr). uv*Mipgroesse waere bei
    // Nicht-Zweierpotenzen versetzt -> falscher Texel -> faelschlich verdeckt (Loecher).
    int2 px0 = int2(floor(mn * f.screenSize));
    int2 px1 = int2(floor(mx * f.screenSize));
    int2 p0 = clamp(px0 >> mip, int2(0, 0), lim);
    int2 p1 = clamp(px1 >> mip, int2(0, 0), lim);
    float d = min(min(gHiz.Load(int3(p0, mip)), gHiz.Load(int3(p1.x, p0.y, mip))),
                  min(gHiz.Load(int3(p0.x, p1.y, mip)), gHiz.Load(int3(p1, mip))));
    return zNearest >= d;
}

// LOD: liegt die Box (relativer Raum) komplett in Sections, die das Nahfeld zeichnet? (dann bringt LOD nichts)
bool nearCoveredBox(FrameUniforms f, float3 mn, float3 mx)
{
    if ((gCull.nearMode & 1u) == 0)
        return false;
    int2 c0 = worldChunk(f, mn.xz), c1 = worldChunk(f, mx.xz);
    if (any(c1 - c0 > 3))
        return false;
    int minSY = int(gCull.nearMode >> 1) - 2048;
    int s0 = max(worldSectionY(f, mn.y) - minSY, 0);
    int s1 = min(worldSectionY(f, mx.y) - minSY, 31);
    if (s1 < s0)
        return true; // ganz unter/ueber der Welt
    uint need = (s1 >= 31 ? 0xFFFFFFFFu : ((1u << uint(s1 + 1)) - 1u)) & ~((1u << uint(s0)) - 1u);
    int2 cam = int2(gCull.camChunkX, gCull.camChunkZ);
    for (int z = c0.y; z <= c1.y; z++)
    {
        for (int x = c0.x; x <= c1.x; x++)
        {
            if (abs(x - cam.x) >= 64 || abs(z - cam.y) >= 64)
                return false;
            if ((gNearMask[uint((z & 127) * 128 + (x & 127))] & need) != need)
                return false;
        }
    }
    return true;
}

groupshared uint sClusterPass;
groupshared uint sMeshletStart;
groupshared uint sMeshletCount;

// Hierarchisch: eine Workgroup pro Cluster (Section). Thread 0 testet den Cluster,
// nur bei Treffer lesen/testen die 64 Threads dessen Meshlets (Stride 64).
// Die meisten Cluster liegen ausserhalb des Frustums -> deren Meshlets werden nie gelesen.
[shader("compute")]
[NumThreads(64, 1, 1)]
void meshletCull(in uint3 gid : SV_GroupID, in uint3 ltid : SV_GroupThreadID)
{
    uint clusterIdx = gid.x;
    FrameUniforms f = gFrame;
    if (ltid.x == 0)
    {
        uint pass = 0;
        if (clusterIdx < gMeshletCount[0]) // Binding 5: belegte Cluster-Slots
        {
            Cluster cl = gClusters[clusterIdx];
            if (cl.meshletCount > 0)
            {
                // Cluster-Mitte liegt in Weltkoordinaten vor (float, ganzzahlig bis 16 Mio. exakt);
                // der kleine Zuschlag deckt Rundung jenseits davon
                float3 center = cl.center - float3(f.renderOrigin.xyz);
                float boundR = length(cl.extent) + cl.lodError + 2.0;
                pass = sphereFrustum(center, boundR, f.frustumPlanes) ? 1u : 0u;
                if (pass != 0 && nearCoveredBox(f, center - cl.extent, center + cl.extent))
                    pass = 0u;
                if (pass != 0)
                {
                    float dist = max(distance(center, f.camPos), 0.001);
                    float sse = cl.lodError * f.screenSize.y / (2.0 * dist * 0.866);
                    float limit = ((cl.flags & 2) != 0) ? f.lodErrorThreshold * 4.0 : f.lodErrorThreshold;
                    pass = sse <= limit ? 1u : 0u;
                }
            }
            sMeshletStart = cl.meshletStart;
            sMeshletCount = cl.meshletCount;
        }
        sClusterPass = pass;
    }
    GroupMemoryBarrierWithGroupSync();
    if (sClusterPass == 0)
        return;

    uint phase = gCull.phase;
    for (uint i = ltid.x; i < sMeshletCount; i += 64)
    {
        uint idx = sMeshletStart + i;
        MeshletHeader mh = gMeshlets[idx];
        if (mh.triCount == 0)
            continue; // freier/ersetzter Slot
        // Kugel und Ebenen-Cutoff sind relativ zum Meshlet-Ursprung -> in den relativen Raum
        float3 org = relOrigin(f, mh.originX, mh.originY, mh.originZ);
        float3 bc = org + mh.boundCenter;
        bool water = (mh.clusterIdx & WATER_BIT) != 0;
        bool trans = (mh.clusterIdx & TRANS_BIT) != 0;
        if ((water || trans) && ((f.cullFlags & 1) != 0 || phase == 1))
            continue; // Schatten-Cull: Wasser/Glas werfen keine Schatten; Phase 1: nur Opakes

        // Meshlet-Feinfilter: Frustum + Backface-Ebene (+ Hi-Z in Phase 2)
        // Abdeckung nur fuer den Teil ueber minTopY pruefen (darunter ist das Meshlet ohnehin nicht gefragt)
        float3 bmn = bc - mh.boundRadius, bmx = bc + mh.boundRadius;
        bmn.y = max(bmn.y, gCull.minTopY);
        float2 camChunkRel = float2(int2(gCull.camChunkX, gCull.camChunkZ) - (f.renderOrigin.xz >> 4));
        bool pass = sphereFrustum(bc, mh.boundRadius, f.frustumPlanes)
                 && bmx.y >= gCull.minTopY
                 && length(bc.xz - (camChunkRel + 0.5) * 16.0) - mh.boundRadius <= gCull.maxDistXZ
                 && !nearCoveredBox(f, bmn, bmx);
        if (pass && dot(mh.coneAxis, mh.coneAxis) > 0.5)
            pass = dot(f.camPos, mh.coneAxis) >= mh.coneCutoff + dot(org, mh.coneAxis) - 0.01;
        if (phase == 1)
        {
            if (!pass || gVisBits[idx] == 0)
                continue;
        }
        else if (phase == 2)
        {
            if (pass)
                pass = hizVisible(bc, mh.boundRadius, f);
            if (!water && !trans)
            {
                bool drawnInPhase1 = gVisBits[idx] != 0;
                gVisBits[idx] = pass ? 1u : 0u;
                if (drawnInPhase1)
                    continue; // steht schon im G-Buffer
            }
            if (!pass)
                continue;
        }
        else if (!pass)
        {
            continue;
        }

        uint slot;
        if (water)
        {
            InterlockedAdd(gWaterIndirect[0], 1, slot);
            gVisibleWater[slot] = idx;
        }
        else if (trans)
        {
            InterlockedAdd(gTransIndirect[0], 1, slot);
            gVisibleTrans[slot] = idx;
        }
        else
        {
            InterlockedAdd(gIndirectOut[0], 1, slot);
            gVisible[slot] = idx;
            InterlockedAdd(gVisibleCount[0], 1);
        }
    }
}
