// lod_mesher.slang – Greedy-Meshing der LOD-Knoten auf der GPU (eigene Implementierung,
// gleicher Algorithmus wie LodMesher.java): Knotengitter [34][H][34] (Material | Biom << 16,
// Bodendecker) -> Quads (2 x uint, siehe LodMesher) je Seite in Zwischenpuffer; nach dem
// Zuteilen der Pool-Bereiche (CPU liest nur die Anzahlen) kopiert COMMIT sie zusammen
// und baut die Meshlet-Header. Hohe Welten: X/Z-Schichten in Kacheln zu 64 Zeilen.

[[vk::binding(0, 0)]] StructuredBuffer<uint2> gGrid;
[[vk::binding(1, 0)]] StructuredBuffer<uint4> gMat;        // top, side, bottom (rgb | Toenung << 24), info
[[vk::binding(2, 0)]] StructuredBuffer<uint> gMatTint;     // feste Toenung je Material
[[vk::binding(3, 0)]] StructuredBuffer<uint4> gBiomeCol;   // Gras, Laub, Trockenlaub, Wasser
[[vk::binding(4, 0)]] RWStructuredBuffer<int> gTop;        // je Job 34x34 oberster verdeckender Voxel
[[vk::binding(5, 0)]] RWStructuredBuffer<uint> gCount;     // je Job: 6 Seiten, Ueberlauf, yMin, yMax, frei
[[vk::binding(6, 0)]] RWStructuredBuffer<uint2> gTmpQuads; // je Job 6 Bereiche a TMP_PER_DIR
struct MeshJob
{
    int level;
    int nx;
    int nz;
    uint pad;
};
[[vk::binding(7, 0)]] StructuredBuffer<MeshJob> gMJobs;
// Commit
struct CommitJob
{
    uint tmpJob;       // Job-Index im Zwischenpuffer (dieser Slot)
    uint quadStart;    // Pool
    uint meshletStart; // Pool
    uint cluster;
    int level, nx, nz, minY;
    uint dirQuads[6];  // Anzahl je Seite
    uint dirMeshletStart[6]; // relativ
};
[[vk::binding(8, 0)]] StructuredBuffer<CommitJob> gCommit;
[[vk::binding(9, 0)]] RWStructuredBuffer<uint2> gPoolQuads;
struct MeshletHeader
{
    uint vertexStart, vertexCount, indexStart, triCount, clusterIdx;
    int originX, originY, originZ;
    float3 boundCenter;
    float boundRadius;
    float3 coneAxis;
    float coneCutoff;
};
[[vk::binding(10, 0)]] RWStructuredBuffer<MeshletHeader> gPoolMeshlets;

struct MeshParams
{
    uint pass;       // 0 Oberkanten, 1 Greedy, 2 Commit
    uint jobCount;
    uint height;     // Welthoehe (Bloecke)
    uint gridPerJob;
    uint tmpPerDir;
    uint commitCount;
    uint pad0, pad1;
};
[[vk::push_constant]] ConstantBuffer<MeshParams> gM;

static const uint W = 34;
static const uint TILE = 64;
static const uint KIND_AIR = 0, KIND_SOLID = 1, KIND_LEAVES = 2, KIND_COVER = 3, KIND_WATER = 4, KIND_LAVA = 5, KIND_GLASS = 6;
static const int3 DIRS[6] = { int3(1, 0, 0), int3(-1, 0, 0), int3(0, 1, 0), int3(0, -1, 0), int3(0, 0, 1), int3(0, 0, -1) };

uint hv(int level) { return gM.height >> uint(level); }
uint gidx(uint job, int gx, int y, int gz) { return job * gM.gridPerJob + (uint(y) * W + uint(gz)) * W + uint(gx); }
uint kindOf(uint mat) { return gMat[mat].w & 7u; }
bool occludes(uint k) { return k == KIND_SOLID || k == KIND_LEAVES || k == KIND_LAVA || k == KIND_GLASS; }

uint mulRgb(uint a, uint b)
{
    uint r = ((a >> 16) & 255u) * ((b >> 16) & 255u) / 255u;
    uint g = ((a >> 8) & 255u) * ((b >> 8) & 255u) / 255u;
    uint bl = (a & 255u) * (b & 255u) / 255u;
    return (r << 16) | (g << 8) | bl;
}

// Seitenfarbe inkl. Biom-Toenung (LodMaterials.faceColor)
uint faceColor(uint mat, uint face, uint biome)
{
    uint4 m = gMat[mat];
    uint c = face == 0 ? m.x : face == 1 ? m.y : m.z;
    uint tint = c >> 24;
    c &= 0xFFFFFFu;
    if (tint == 0) return c;
    uint4 bc = gBiomeCol[biome];
    uint t = tint == 1 ? bc.x : tint == 2 ? bc.y : tint == 3 ? bc.z : tint == 4 ? bc.w : gMatTint[mat];
    return mulRgb(c, t & 0xFFFFFFu);
}

uint mixRgb(uint a, uint b)
{
    uint r = (((a >> 16) & 255u) + ((b >> 16) & 255u)) >> 1;
    uint g = (((a >> 8) & 255u) + ((b >> 8) & 255u)) >> 1;
    uint bl = ((a & 255u) + (b & 255u)) >> 1;
    return (r << 16) | (g << 8) | bl;
}

// 0 = keine Flaeche, sonst Schluessel (wie LodMesher.faceKey, Gueltigkeitsbit 1 in bit 31 der Rueckgabe separat)
uint faceKey(uint job, int x, int y, int z, uint dir, uint h, out bool valid)
{
    valid = false;
    int gx = x + 1, gz = z + 1;
    uint2 v = gGrid[gidx(job, gx, y, gz)];
    uint m = v.x & 0xFFFFu;
    if (m == 0) return 0;
    uint k = kindOf(m);
    if (k == KIND_AIR || k == KIND_COVER) return 0;
    int3 d = DIRS[dir];
    int nx = gx + d.x, ny = y + d.y, nz = gz + d.z;
    bool visible;
    uint nm = 0;
    if (ny < 0) return 0;
    if (ny >= int(h)) visible = true;
    else
    {
        nm = gGrid[gidx(job, nx, ny, nz)].x & 0xFFFFu;
        uint nk = nm == 0 ? KIND_AIR : kindOf(nm);
        if (nk == KIND_AIR || nk == KIND_COVER) visible = true;
        else
        {
            visible = false;
            bool border = nx == 0 || nx == int(W) - 1 || nz == 0 || nz == int(W) - 1;
            if (border && k != KIND_WATER && y >= gTop[job * W * W + uint(gz) * W + uint(gx)] - 1) visible = true;
        }
    }
    if (!visible) return 0;
    if (k == KIND_WATER && dir != 2 && ny < int(h) && nm != 0 && kindOf(nm) != KIND_COVER) return 0;
    uint face = dir == 2 ? 0u : dir == 3 ? 2u : 1u;
    uint biome = (v.x >> 16) & 255u;
    uint rgb = faceColor(m, face, biome);
    if (dir == 2 && v.y != 0)
    {
        // Bodendecker: Schneeschicht/Teppich (info bit 8) ersetzt die Oberseite, Pflanzen mischen
        uint cov = v.y & 0xFFFFu;
        uint cc = faceColor(cov, 0, biome);
        rgb = ((gMat[cov].w >> 8) & 1u) != 0 ? cc : mixRgb(rgb, cc);
    }
    uint sky;
    if (ny >= int(h)) sky = 15;
    else
    {
        int cx = clamp(nx, 0, int(W) - 1), cz = clamp(nz, 0, int(W) - 1);
        sky = ny > gTop[job * W * W + uint(cz) * W + uint(cx)] ? 15u : 3u;
    }
    uint emi = (gMat[m].w >> 4) & 15u;
    if (k == KIND_WATER && dir == 2)
    {
        // Wasseroberflaeche: statt Leuchtkraft die Tiefe bis zum Grund (1..15 -> c*c/4 Bloecke,
        // 0 = unbekannt/tief) fuer die Wasserfarbe im Deferred-Pass
        int bed = gTop[job * W * W + uint(gz) * W + uint(gx)];
        float depth = bed < 0 ? 1e6 : float(y - bed) * float(1u << uint(gMJobs[job].level));
        emi = uint(clamp(ceil(sqrt(depth * 4.0)), 1.0, 15.0));
        if (bed < 0) emi = 0;
    }
    uint r7 = (rgb >> 17) & 0x7Fu, g7 = (rgb >> 9) & 0x7Fu, b7 = (rgb >> 1) & 0x7Fu;
    valid = true;
    return r7 | (g7 << 7) | (b7 << 14) | (sky << 21) | (emi << 25) | ((k & 7u) << 29);
}

groupshared uint sKey[32 * TILE];
groupshared uint sValid[32 * TILE / 32];
groupshared uint2 sQuads[1024];
groupshared uint sQuadN;
groupshared uint sBase;

[shader("compute")]
[NumThreads(64, 1, 1)]
void lodMeshMain(in uint3 gid : SV_GroupID, in uint3 ltid : SV_GroupThreadID, in uint3 tid : SV_DispatchThreadID)
{
    uint lt = ltid.x;
    if (gM.pass == 0)
    {
        // Oberkanten + Hoehenbereich
        uint i = tid.x;
        if (i >= gM.jobCount * W * W) return;
        uint job = i / (W * W), col = i % (W * W);
        MeshJob j = gMJobs[job];
        uint h = hv(j.level);
        // Von oben: der erste nicht-leere Voxel ist die Obergrenze der Spalte (Wasser ueber dem
        // Grund eingeschlossen); je Spalte nur ein Atomic statt einem je Wasservoxel
        int top = -1, lo = 0x7FFFFFFF, hi = -1;
        for (int y = int(h) - 1; y >= 0; y--)
        {
            uint m = gGrid[job * gM.gridPerJob + uint(y) * W * W + col].x & 0xFFFFu;
            if (m == 0) continue;
            uint k = kindOf(m);
            if (k == KIND_AIR || k == KIND_COVER) continue;
            if (hi < 0) hi = y;
            lo = y;
            if (occludes(k)) { top = y; break; }
        }
        gTop[job * W * W + col] = top;
        // unter der Oberkante: tiefster nicht-leerer Voxel (Hoehlen/Ueberhaenge) fuer den Hoehenbereich
        for (int y = top - 1; y >= 0; y--)
        {
            uint m = gGrid[job * gM.gridPerJob + uint(y) * W * W + col].x & 0xFFFFu;
            if (m != 0 && kindOf(m) != KIND_AIR && kindOf(m) != KIND_COVER) lo = y;
        }
        if (hi >= 0)
        {
            InterlockedMin(gCount[job * 10 + 7], uint(lo));
            InterlockedMax(gCount[job * 10 + 8], uint(hi));
        }
        return;
    }
    if (gM.pass == 1)
    {
        // Greedy: Gruppe = (Job, Seite, Schicht, Kachel)
        uint perJob = 2 * 384 + 4 * 32 * 6;
        uint g = gid.x;
        uint job = g / perJob, r = g % perJob;
        if (job >= gM.jobCount) return;
        MeshJob j = gMJobs[job];
        uint h = hv(j.level);
        uint yLo = gCount[job * 10 + 7], yHi = gCount[job * 10 + 8];
        if (yLo > yHi) return;
        uint dir, slice, tile;
        if (r < 2 * 384) { dir = 2 + r / 384; slice = r % 384; tile = 0; }
        else { uint q = r - 2 * 384; dir = (q / (32 * 6)) < 2 ? (q / (32 * 6)) : (q / (32 * 6)) + 2; uint q2 = q % (32 * 6); slice = q2 / 6; tile = q2 % 6; }
        uint axis = dir >> 1;
        uint vCount, v0;
        if (axis == 1)
        {
            if (slice < yLo || slice > yHi || slice >= h) return;
            vCount = 32; v0 = 0;
        }
        else
        {
            v0 = yLo + tile * TILE;
            if (v0 > yHi) return;
            vCount = min(TILE, yHi - v0 + 1);
        }
        if (lt == 0) sQuadN = 0;
        // Maske fuellen: 32 x vCount Zellen, u = z (X-Seiten) bzw. x
        for (uint c = lt; c < 32 * vCount; c += 64)
        {
            uint u = c % 32, v = c / 32;
            int x, y, z;
            if (axis == 0) { x = int(slice); z = int(u); y = int(v0 + v); }
            else if (axis == 1) { y = int(slice); x = int(u); z = int(v); }
            else { z = int(slice); x = int(u); y = int(v0 + v); }
            bool ok;
            uint key = faceKey(job, x, y, z, dir, h, ok);
            sKey[c] = ok ? key : 0xFFFFFFFFu;
        }
        GroupMemoryBarrierWithGroupSync();
        if (lt == 0)
        {
            // Greedy wie auf der CPU (Breite, dann Hoehe), max. 32 x 32
            uint n = 0;
            for (uint v = 0; v < vCount; v++)
            {
                for (uint u = 0; u < 32; )
                {
                    uint key = sKey[v * 32 + u];
                    if (key == 0xFFFFFFFFu) { u++; continue; }
                    uint w = 1;
                    while (u + w < 32 && w < 32 && sKey[v * 32 + u + w] == key) w++;
                    uint hh = 1;
                    bool stop = false;
                    while (v + hh < vCount && hh < 32 && !stop)
                    {
                        for (uint k = 0; k < w; k++)
                        {
                            if (sKey[(v + hh) * 32 + u + k] != key) { stop = true; break; }
                        }
                        if (!stop) hh++;
                    }
                    for (uint dv = 0; dv < hh; dv++)
                        for (uint k = 0; k < w; k++) sKey[(v + dv) * 32 + u + k] = 0xFFFFFFFFu;
                    uint px, py, pz;
                    if (axis == 0) { px = slice; pz = u; py = v0 + v; }
                    else if (axis == 1) { py = slice; px = u; pz = v; }
                    else { pz = slice; px = u; py = v0 + v; }
                    uint2 q = uint2((px & 31u) | ((pz & 31u) << 5) | ((py & 511u) << 10) | (dir << 19) | ((w - 1) << 22) | ((hh - 1) << 27), key);
                    if (n < 1024) sQuads[n] = q;
                    else
                    {
                        uint slot;
                        InterlockedAdd(gCount[job * 10 + dir], 1, slot);
                        if (slot < gM.tmpPerDir) gTmpQuads[(job * 6 + dir) * gM.tmpPerDir + slot] = q;
                        else gCount[job * 10 + 6] = 1;
                    }
                    n++;
                    u += w;
                }
            }
            uint keep = min(n, 1024u);
            uint base;
            InterlockedAdd(gCount[job * 10 + dir], keep, base);
            sQuadN = keep;
            sBase = base;
        }
        GroupMemoryBarrierWithGroupSync();
        uint cnt = sQuadN, basePos = sBase;
        for (uint k = lt; k < cnt; k += 64)
        {
            uint slot = basePos + k;
            if (slot < gM.tmpPerDir) gTmpQuads[(job * 6 + dir) * gM.tmpPerDir + slot] = sQuads[k];
            else gCount[job * 10 + 6] = 1;
        }
        return;
    }
    if (gM.pass == 2)
    {
        // COMMIT: ein Thread je Meshlet (32 Quads): kopieren + Header (Bounds, Seitenebene)
        uint i = tid.x;
        // lineare Suche des Commit-Jobs (wenige Jobs je Frame)
        uint acc = 0, cj = 0xFFFFFFFFu, local = 0;
        for (uint c = 0; c < gM.commitCount; c++)
        {
            CommitJob job = gCommit[c];
            uint nm = job.dirMeshletStart[5] + (job.dirQuads[5] + 31) / 32;
            if (i < acc + nm) { cj = c; local = i - acc; break; }
            acc += nm;
        }
        if (cj == 0xFFFFFFFFu) return;
        CommitJob job = gCommit[cj];
        uint dir = 0;
        for (uint d = 0; d < 6; d++)
        {
            uint nm = (job.dirQuads[d] + 31) / 32;
            if (local >= job.dirMeshletStart[d] && local < job.dirMeshletStart[d] + nm) { dir = d; break; }
        }
        uint inDir = local - job.dirMeshletStart[dir];
        uint q0 = inDir * 32;
        uint n = min(32u, job.dirQuads[dir] - q0);
        uint dstQuad = job.quadStart;
        for (uint d = 0; d < dir; d++) dstQuad += job.dirQuads[d];
        dstQuad += q0;
        int s = 1 << job.level;
        float3 origin = float3(float(job.nx * 32 * s), float(job.minY), float(job.nz * 32 * s));
        float3 mn = float3(1e30), mx = float3(-1e30);
        float cutoff = 1e30;
        int3 dvec = DIRS[dir];
        for (uint k = 0; k < n; k++)
        {
            uint2 q = gTmpQuads[(job.tmpJob * 6 + dir) * gM.tmpPerDir + q0 + k];
            gPoolQuads[dstQuad + k] = q;
            uint px = q.x & 31u, pz = (q.x >> 5) & 31u, py = (q.x >> 10) & 511u;
            uint su = ((q.x >> 22) & 31u) + 1, sv = ((q.x >> 27) & 31u) + 1;
            uint axis = dir >> 1;
            float3 a = float3(px, py, pz), b = a + 1.0;
            if (axis == 0) { b.z = pz + su; b.y = py + sv; }
            else if (axis == 1) { b.x = px + su; b.z = pz + sv; }
            else { b.x = px + su; b.y = py + sv; }
            float3 wa = origin + a * float(s), wb = origin + b * float(s);
            mn = min(mn, wa);
            mx = max(mx, wb);
            float plane = dir == 0 ? wb.x : dir == 1 ? -wa.x : dir == 2 ? wb.y : dir == 3 ? -wa.y : dir == 4 ? wb.z : -wa.z;
            cutoff = min(cutoff, plane);
        }
        MeshletHeader h;
        h.vertexStart = dstQuad;
        h.vertexCount = n;
        h.indexStart = uint(job.level);
        h.triCount = n * 2;
        h.clusterIdx = job.cluster;
        h.originX = job.nx * 32 * s;
        h.originY = job.minY;
        h.originZ = job.nz * 32 * s;
        float3 hc = (mx - mn) * 0.5;
        h.boundCenter = mn + hc;
        h.boundRadius = length(hc) + 0.01;
        h.coneAxis = float3(dvec);
        h.coneCutoff = cutoff;
        gPoolMeshlets[job.meshletStart + local] = h;
        return;
    }
}
