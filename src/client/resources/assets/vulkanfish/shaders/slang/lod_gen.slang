// lod_gen.slang – vereinfachte GPU-Generierung fuer LOD-Knoten (eigene Implementierung).
// Pro Knoten (Stufe l, 32x32 Voxel + Rand, volle Hoehe) wird nur so viel gerechnet, wie
// die Stufe braucht:
//   F  flat_cache am Quart-Raster (Schrittweite max(4, 2^l))
//   C  interpolated an den Zellecken (x/z wie F, y max(8, 2^l))
//   S  Dichte an den Abtastpunkten (Stufe 0: jeder Block, sonst Voxel-Ecken) -> Bits
//   COL Voxel (belegt, sobald eine Ecke fest ist – nichts verschwindet), Oberflaeche,
//      Klima + Biom (exakte Vanilla-Suche), Material aus der Biom-Tabelle
//   DECO steile Haenge + vereinfachte Baeume (Dichte/Laub/Stamm je Biom)
//   RUNS echte Daten (Laeufe von der CPU) ueberschreiben ihre Saeulen
// Ergebnis: Knotengitter [34][H][34] uint2 (Material | Biom << 16, Bodendecker) fuer lod_mesher.
#include "worldgen_core.slang"

struct GenJob
{
    int level;
    int nx;
    int nz;
    uint flags;     // bit0: generieren, bit1: echte Laeufe ueberlagern
    uint runBase;   // Index der Saeulenkoepfe in gRunHead (bit1)
    uint pad0, pad1, pad2;
};
[[vk::binding(9, 0)]] StructuredBuffer<GenJob> gJobs;
[[vk::binding(10, 0)]] RWStructuredBuffer<float> gFlat;
[[vk::binding(11, 0)]] RWStructuredBuffer<float> gInterp;
[[vk::binding(12, 0)]] RWStructuredBuffer<uint> gSamples;   // Bits je Job
[[vk::binding(13, 0)]] RWStructuredBuffer<int4> gColumn;    // je Job 34x34: oberster fester Voxel, Biom, Flags
[[vk::binding(14, 0)]] RWStructuredBuffer<uint2> gGrid;     // je Job 34*H*34
[[vk::binding(15, 0)]] StructuredBuffer<int> gBiomeTable;   // 256 x BT_FIELDS (LodBiomeTable.FIELDS)
static const int BT_FIELDS = 12; // low, high, wet, steep, filler, leaves, log, density, temp, snow, ice, flags
[[vk::binding(16, 0)]] StructuredBuffer<double> gClimate;   // je Punkt 14 double
[[vk::binding(17, 0)]] StructuredBuffer<int> gClimateBiome;
[[vk::binding(18, 0)]] StructuredBuffer<uint2> gRunHead;    // je Saeule: Start, Anzahl
[[vk::binding(19, 0)]] StructuredBuffer<uint4> gRuns;       // y | len << 16, Material | Biom << 16, Decker, 0
[[vk::binding(20, 0)]] StructuredBuffer<float4> gClimateNode; // Huellquader-Baum (ClimateTree): je Knoten 4 x float4

struct LodGenParams
{
    uint pass;
    uint jobCount;
    uint progF, progC, progV, progClimate;
    uint flatSlots, interpSlots, resultReg;
    int minY;
    uint height;
    int seaLevel;
    int lavaLevel;
    uint climatePoints;
    uint stoneMat, waterMat, lavaMat;
    uint fallbackBiome;
    uint climateReg0, climateReg1, climateReg2, climateReg3, climateReg4, climateReg5;
    uint samplesPerJob;   // uints
    uint interpPerJob;    // floats
    uint flatPerJob;      // floats
    uint gridPerJob;      // uint2
    uint termCount;       // Noise-Terme je Knoten in gJobTerm
    uint pad1;
    uint jobFirst;        // F/C/S: erster Knoten dieses Aufrufs (Teilmengen ueber Frames)
    uint pad2;
};
[[vk::push_constant]] ConstantBuffer<LodGenParams> gP;

static const uint GRID_W = 34;
static const uint LAT = 37;   // max. Gitterpunkte je Achse (F/C)

struct Ctx
{
    int3 block;
    uint job;
    uint flatIdx;    // Gitterpunkt (F)
    int3 lat;        // Zell-Index (C/V)
    float3 f;        // Anteile (V)
    uint nL;         // Gitterpunkte je Achse
};

// Geometrie des Knotens
int stepOf(int level) { return 1 << level; }
int latStep(int level) { return max(4, 1 << level); }
int latStepY(int level) { return max(8, 1 << level); }
int blockX0(GenJob j) { return (j.nx * 32 - 1) << j.level; }
int blockZ0(GenJob j) { return (j.nz * 32 - 1) << j.level; }
int latOrigin(int b0, int cs) { return (b0 >= 0 ? b0 / cs : -((-b0 + cs - 1) / cs)) * cs; }
uint latCount(GenJob j)
{
    int s = stepOf(j.level), cs = latStep(j.level);
    int x0 = blockX0(j), x1 = x0 + 34 * s;
    return uint((x1 - latOrigin(x0, cs)) / cs + 2);
}
uint latCountY(GenJob j) { return gP.height / uint(latStepY(j.level)) + 1; }

int3 wgLocal(Ctx c)
{
    GenJob j = gJobs[c.job];
    return int3(c.block.x - blockX0(j), c.block.y, c.block.z - blockZ0(j));
}
uint wgJobTerm0(Ctx c) { return c.job * gP.termCount; }

float ctxLoadFlat(Ctx c, uint slot)
{
    return gFlat[c.job * gP.flatPerJob + c.flatIdx * gP.flatSlots + slot];
}

float ctxLoadInterp(Ctx c, uint slot)
{
    uint nL = c.nL;
    uint ny = gP.height / 8 + 1; // Obergrenze; Index unten mit der echten Zeilenzahl
    uint base = c.job * gP.interpPerJob;
    uint slots = gP.interpSlots;
    GenJob j = gJobs[c.job];
    uint nY = latCountY(j);
    #define CORNER(dx, dy, dz) gInterp[base + ((uint(c.lat.y + dy) * nL + uint(c.lat.z + dz)) * nL + uint(c.lat.x + dx)) * slots + slot]
    float n000 = CORNER(0, 0, 0), n100 = CORNER(1, 0, 0), n010 = CORNER(0, 1, 0), n110 = CORNER(1, 1, 0);
    float n001 = CORNER(0, 0, 1), n101 = CORNER(1, 0, 1), n011 = CORNER(0, 1, 1), n111 = CORNER(1, 1, 1);
    #undef CORNER
    float3 f = c.f;
    return lerp(lerp(lerp(n000, n100, f.x), lerp(n010, n110, f.x), f.y), lerp(lerp(n001, n101, f.x), lerp(n011, n111, f.x), f.y), f.z);
}

void ctxStore(Ctx c, uint slot, float v)
{
    if (gP.pass == 0)
    {
        gFlat[c.job * gP.flatPerJob + c.flatIdx * gP.flatSlots + slot] = v;
        return;
    }
    gInterp[c.job * gP.interpPerJob + ((uint(c.lat.y) * c.nL + uint(c.lat.z)) * c.nL + uint(c.lat.x)) * gP.interpSlots + slot] = v;
}

int floorDivI(int a, int b) { return a >= 0 ? a / b : -((-a + b - 1) / b); }

// Kontext fuer einen Punkt (V-Stufe): Quart- und Zell-Index im Knotengitter
Ctx sampleCtx(uint job, GenJob j, int3 b)
{
    int cs = latStep(j.level), cy = latStepY(j.level);
    int lx0 = latOrigin(blockX0(j), cs), lz0 = latOrigin(blockZ0(j), cs);
    Ctx c;
    c.block = b;
    c.job = job;
    c.nL = latCount(j);
    int ix = floorDivI(b.x - lx0, cs), iz = floorDivI(b.z - lz0, cs);
    int iy = (b.y - gP.minY) / cy;
    c.flatIdx = uint(iz) * c.nL + uint(ix);
    c.lat = int3(ix, min(iy, int(latCountY(j)) - 2), iz);
    c.f = float3(float(b.x - lx0 - ix * cs) / float(cs), float(b.y - gP.minY - c.lat.y * cy) / float(cy), float(b.z - lz0 - iz * cs) / float(cs));
    return c;
}

float density(uint job, GenJob j, int3 b)
{
    Ctx c = sampleCtx(job, j, b);
    float r[MAX_REGS];
    wgExec(c, gP.progV, 2, r);
    return r[gP.resultReg];
}

// Abtastgitter: Stufe 0 je Block (34 x H x 34), sonst Voxel-Ecken (35 x H/s+1 x 35)
uint samplesX(int level) { return level == 0 ? 34u : 35u; }
uint samplesY(int level) { return level == 0 ? gP.height : gP.height / uint(1 << level) + 1; }
bool sampleBit(uint job, int level, uint sx, uint sy, uint sz)
{
    uint nx = samplesX(level);
    uint row = sy * nx + sz;                     // eine Zeile = x-Richtung, 2 Worte
    uint w = gSamples[job * gP.samplesPerJob + row * 2 + (sx >> 5)];
    return ((w >> (sx & 31)) & 1u) != 0;
}

// ---------- Klima + Biom (exakte Vanilla-Suche: kleinster quadratischer Abstand) ----------
double paramDist(double v, double mn, double mx)
{
    double above = v - mx, below = mn - v;
    return above > 0.0 ? above : max(below, 0.0);
}

// untere Schranke des quadratischen Abstands zum Knotenquader (float genuegt, Rand in der Pruefung)
float nodeBound(uint node, float tf[6])
{
    float4 a = gClimateNode[node * 4], b = gClimateNode[node * 4 + 1], c = gClimateNode[node * 4 + 2];
    float4 h = gClimateNode[node * 4 + 3];
    float e0 = max(max(tf[0] - a.y, a.x - tf[0]), 0.0);
    float e1 = max(max(tf[1] - a.w, a.z - tf[1]), 0.0);
    float e2 = max(max(tf[2] - b.y, b.x - tf[2]), 0.0);
    float e3 = max(max(tf[3] - b.w, b.z - tf[3]), 0.0);
    float e4 = max(max(tf[4] - c.y, c.x - tf[4]), 0.0);
    float e5 = max(max(tf[5] - c.w, c.z - tf[5]), 0.0);
    return e0 * e0 + e1 * e1 + e2 * e2 + e3 * e3 + e4 * e4 + e5 * e5 + h.x * h.x;
}

void searchLeaf(uint leaf, double t[6], inout double best, inout uint bestIdx)
{
    float4 lh = gClimateNode[leaf * 4 + 3];
    uint p0 = asuint(lh.y), pn = asuint(lh.z);
    for (uint i = p0; i < p0 + pn; i++)
    {
        double d = 0.0;
        for (uint k = 0; k < 6; k++)
        {
            double e = paramDist(t[k], gClimate[i * 14 + k * 2], gClimate[i * 14 + k * 2 + 1]);
            d += e * e;
        }
        double o = gClimate[i * 14 + 12];
        d += o * o;
        if (d < best) { best = d; bestIdx = i; }
    }
}

void searchGroup(uint g, uint groups, float tf[6], double t[6], inout double best, inout uint bestIdx)
{
    uint leafBase = 1 + groups;
    float4 gh = gClimateNode[(1 + g) * 4 + 3];
    uint l0 = asuint(gh.y), ln = asuint(gh.z);
    uint firstLeaf = l0;
    float bl = 3.4e38;
    for (uint l = l0; l < l0 + ln; l++)
    {
        float lb = nodeBound(leafBase + l, tf);
        if (lb < bl) { bl = lb; firstLeaf = l; }
    }
    if (double(bl) * 0.9999 > best)
        return;
    searchLeaf(leafBase + firstLeaf, t, best, bestIdx);
    for (uint l = l0; l < l0 + ln && best > 0.0; l++)
    {
        if (l == firstLeaf || double(nodeBound(leafBase + l, tf)) * 0.9999 > best) continue;
        searchLeaf(leafBase + l, t, best, bestIdx);
    }
}

uint biomeAt(uint job, GenJob j, int3 b)
{
    if (gP.climatePoints == 0)
        return gP.fallbackBiome;
    Ctx c = sampleCtx(job, j, b);
    float r[MAX_REGS];
    wgExec(c, gP.progClimate, 2, r);
    // Climate.quantizeCoord: (long)(v * 10000f)
    double t[6];
    t[0] = double(int(r[gP.climateReg0] * 10000.0));
    t[1] = double(int(r[gP.climateReg1] * 10000.0));
    t[2] = double(int(r[gP.climateReg2] * 10000.0));
    t[3] = double(int(r[gP.climateReg3] * 10000.0));
    t[4] = double(int(r[gP.climateReg4] * 10000.0));
    t[5] = double(int(r[gP.climateReg5] * 10000.0));
    float tf[6];
    for (uint k = 0; k < 6; k++) tf[k] = float(t[k]);
    // wie ClimateTree.search: naechste Gruppe (darin naechstes Blatt) zuerst, dann alles,
    // dessen Schranke noch gewinnen kann; Abstand 0 beendet die Suche
    uint groups = asuint(gClimateNode[3].y);
    double best = 1e300;
    uint bestIdx = 0;
    uint first = 0;
    {
        float bl = 3.4e38;
        for (uint g = 0; g < groups; g++)
        {
            float lb = nodeBound(1 + g, tf);
            if (lb < bl) { bl = lb; first = g; }
        }
    }
    for (uint gi = 0; gi <= groups && best > 0.0; gi++)
    {
        uint g = gi == 0 ? first : gi - 1;
        if (gi > 0 && (g == first || double(nodeBound(1 + g, tf)) * 0.9999 > best)) continue;
        searchGroup(g, groups, tf, t, best, bestIdx);
    }
    return uint(gClimateBiome[bestIdx]);
}

uint hash3(int x, int y, int z)
{
    uint h = uint(x) * 73856093u ^ uint(y) * 19349663u ^ uint(z) * 83492791u;
    h ^= h >> 13;
    h *= 0x5bd1e995u;
    h ^= h >> 15;
    return h;
}

uint gridIndex(uint job, uint gx, uint vy, uint gz)
{
    return job * gP.gridPerJob + (vy * GRID_W + gz) * GRID_W + gx;
}

[shader("compute")]
[NumThreads(64, 1, 1)]
void lodGenMain(in uint3 tid : SV_DispatchThreadID)
{
    uint i = tid.x;
    uint pass = gP.pass;
    if (pass == 0 || pass == 1)
    {
        // F / C: Gitterpunkte (max LAT^2 bzw. LAT^2 * Zeilen je Job)
        uint perJob = pass == 0 ? LAT * LAT : LAT * LAT * (gP.height / 8 + 1);
        if (i >= gP.jobCount * perJob) return;
        uint job = gP.jobFirst + i / perJob, k = i % perJob;
        GenJob j = gJobs[job];
        if ((j.flags & 1u) == 0) return;
        uint nL = latCount(j);
        uint ix = k % LAT, iz = (k / LAT) % LAT, iy = k / (LAT * LAT);
        if (ix >= nL || iz >= nL || (pass == 1 && iy >= latCountY(j))) return;
        int cs = latStep(j.level);
        int lx0 = latOrigin(blockX0(j), cs), lz0 = latOrigin(blockZ0(j), cs);
        Ctx c;
        c.job = job;
        c.nL = nL;
        c.flatIdx = iz * nL + ix;
        c.lat = int3(int(ix), int(iy), int(iz));
        c.f = float3(0.0);
        c.block = int3(lx0 + int(ix) * cs, pass == 0 ? 0 : gP.minY + int(iy) * latStepY(j.level), lz0 + int(iz) * cs);
        float r[MAX_REGS];
        wgExec(c, pass == 0 ? gP.progF : gP.progC, pass, r);
        return;
    }
    if (pass == 2)
    {
        // S: eine Abtastzeile (x) je Thread -> 2 Bitworte
        uint rowsMax = 35 * (gP.height + 1);
        if (i >= gP.jobCount * rowsMax) return;
        uint job = gP.jobFirst + i / rowsMax, row = i % rowsMax;
        GenJob j = gJobs[job];
        if ((j.flags & 1u) == 0) return;
        uint nx = samplesX(j.level), ny = samplesY(j.level);
        uint sz = row % nx, sy = row / nx;
        if (sy >= ny) return;
        int s = stepOf(j.level);
        int y = gP.minY + int(sy) * s;
        uint w0 = 0, w1 = 0;
        for (uint sx = 0; sx < nx; sx++)
        {
            int3 b = int3(blockX0(j) + int(sx) * s, y, blockZ0(j) + int(sz) * s);
            if (density(job, j, b) > 0.0)
            {
                if (sx < 32) w0 |= 1u << sx; else w1 |= 1u << (sx - 32);
            }
        }
        gSamples[job * gP.samplesPerJob + row * 2] = w0;
        gSamples[job * gP.samplesPerJob + row * 2 + 1] = w1;
        return;
    }
    if (pass == 3 || pass == 5)
    {
        // COL (3): Voxel + Oberflaeche + Biom; RUNS (5): echte Saeulen ueberschreiben
        if (i >= gP.jobCount * GRID_W * GRID_W) return;
        uint job = i / (GRID_W * GRID_W), col = i % (GRID_W * GRID_W);
        uint gx = col % GRID_W, gz = col / GRID_W;
        GenJob j = gJobs[job];
        int s = stepOf(j.level);
        uint hv = gP.height >> uint(j.level);
        if (pass == 5)
        {
            if ((j.flags & 2u) == 0) return;
            uint2 head = gRunHead[j.runBase + col];
            if (head.y == 0xFFFFFFFFu)
            {
                // keine echten Daten: Generiertes bleibt; ohne Generierung ist die Saeule leer
                if ((j.flags & 1u) == 0)
                    for (uint vy = 0; vy < hv; vy++) gGrid[gridIndex(job, gx, vy, gz)] = uint2(0, 0);
                return;
            }
            for (uint vy = 0; vy < hv; vy++) gGrid[gridIndex(job, gx, vy, gz)] = uint2(0, 0);
            int top = -1;
            for (uint r = 0; r < head.y; r++)
            {
                uint4 run = gRuns[head.x + r];
                uint y0 = run.x & 0xFFFF, len = run.x >> 16;
                for (uint vy = y0; vy < min(y0 + len, hv); vy++) gGrid[gridIndex(job, gx, vy, gz)] = uint2(run.y, 0);
                if (len > 0 && y0 + len <= hv) gGrid[gridIndex(job, gx, y0 + len - 1, gz)] = uint2(run.y, run.z);
                top = max(top, int(y0 + len) - 1);
            }
            gColumn[job * GRID_W * GRID_W + col] = int4(top, int(gRuns[head.x + max(head.y, 1) - 1].y >> 16), 1, 0);
            return;
        }
        if ((j.flags & 1u) == 0) return;
        // Voxel von oben: belegt, sobald eine Ecke fest ist (Stufe 0: der Block selbst)
        int top = -1, water = -1;
        for (int vy = int(hv) - 1; vy >= 0; vy--)
        {
            bool solid;
            if (j.level == 0)
                solid = sampleBit(job, 0, gx, uint(vy), gz);
            else
                solid = sampleBit(job, j.level, gx, uint(vy), gz) || sampleBit(job, j.level, gx + 1, uint(vy), gz)
                     || sampleBit(job, j.level, gx, uint(vy), gz + 1) || sampleBit(job, j.level, gx + 1, uint(vy), gz + 1)
                     || sampleBit(job, j.level, gx, uint(vy) + 1, gz) || sampleBit(job, j.level, gx + 1, uint(vy) + 1, gz)
                     || sampleBit(job, j.level, gx, uint(vy) + 1, gz + 1) || sampleBit(job, j.level, gx + 1, uint(vy) + 1, gz + 1);
            int yb = gP.minY + vy * s;
            uint mat = 0;
            if (solid)
            {
                if (top < 0) top = vy;
                mat = gP.stoneMat; // Oberflaeche folgt unten
            }
            else if (yb < gP.lavaLevel) mat = gP.lavaMat;
            else if (yb < gP.seaLevel)
            {
                mat = gP.waterMat;
                if (top < 0 && water < 0) water = vy;
            }
            gGrid[gridIndex(job, gx, uint(vy), gz)] = uint2(mat, 0);
        }
        // Biom an der Oberflaeche der Saeule, auf den F-Gitterpunkt gerundet (Klima liest die F-Werte;
        // Schritt max(4, 2^l) ist ein Vielfaches der Quart-Groesse wie Vanillas Biomraster)
        int cs = latStep(j.level);
        int lx0 = latOrigin(blockX0(j), cs), lz0 = latOrigin(blockZ0(j), cs);
        int bxs = blockX0(j) + int(gx) * s + s / 2, bzs = blockZ0(j) + int(gz) * s + s / 2;
        int by = gP.minY + (top >= 0 ? top * s + s : gP.seaLevel - gP.minY);
        int3 cb = int3(lx0 + floorDivI(bxs - lx0, cs) * cs, by - (by & 3), lz0 + floorDivI(bzs - lz0, cs) * cs);
        uint biome = biomeAt(job, j, cb);
        if (top >= 0)
        {
            int surfY = gP.minY + top * s;
            bool wet = water > top;
            int tb = int(biome) * BT_FIELDS;
            uint topMat = uint(wet ? gBiomeTable[tb + 2] : (surfY > gP.seaLevel + 50 ? gBiomeTable[tb + 1] : gBiomeTable[tb]));
            gGrid[gridIndex(job, gx, uint(top), gz)] = uint2(topMat | (biome << 16), 0);
            // Fuellmaterial (Erde unter Gras) bis 3 Bloecke tief
            for (int d = 1; d * s <= 3 && top - d >= 0; d++)
            {
                uint idx = gridIndex(job, gx, uint(top - d), gz);
                if (gGrid[idx].x == gP.stoneMat) gGrid[idx] = uint2(uint(gBiomeTable[tb + 4]) | (biome << 16), 0);
            }
        }
        // Wasser/Stein bekommen das Biom (Toenung)
        for (uint vy = 0; vy < hv; vy++)
        {
            uint idx = gridIndex(job, gx, vy, gz);
            uint2 v = gGrid[idx];
            if (v.x != 0 && (v.x >> 16) == 0) gGrid[idx] = uint2(v.x | (biome << 16), 0);
        }
        gColumn[job * GRID_W * GRID_W + col] = int4(top, int(biome), water > top ? 1 : 0, 0);
        return;
    }
    if (pass == 4)
    {
        // DECO: steile Haenge + Baeume (nur generierte Saeulen oberhalb der Oberflaeche)
        if (i >= gP.jobCount * GRID_W * GRID_W) return;
        uint job = i / (GRID_W * GRID_W), col = i % (GRID_W * GRID_W);
        uint gx = col % GRID_W, gz = col / GRID_W;
        GenJob j = gJobs[job];
        if ((j.flags & 1u) == 0) return;
        int4 me = gColumn[job * GRID_W * GRID_W + col];
        int s = stepOf(j.level);
        uint hv = gP.height >> uint(j.level);
        int tb = me.y * BT_FIELDS;
        if (me.x >= 0 && me.z != 1) // Hang + Baeume nur an Land; Vereisung auch auf Wasser
        {
            // steil: Hoehenunterschied zu den Nachbarn >= 4 Bloecke
            int maxDiff = 0;
            for (int d = 0; d < 4; d++)
            {
                int nx = int(gx) + (d == 0 ? 1 : d == 1 ? -1 : 0), nz = int(gz) + (d == 2 ? 1 : d == 3 ? -1 : 0);
                if (nx < 0 || nz < 0 || nx >= int(GRID_W) || nz >= int(GRID_W)) continue;
                int4 o = gColumn[job * GRID_W * GRID_W + uint(nz) * GRID_W + uint(nx)];
                if (o.x >= 0) maxDiff = max(maxDiff, abs(o.x - me.x));
            }
            if (maxDiff * s >= 4)
                gGrid[gridIndex(job, gx, uint(me.x), gz)] = uint2(uint(gBiomeTable[tb + 3]) | (uint(me.y) << 16), 0);
            // Baeume: Weltraster 4x4 Bloecke, je Zelle hoechstens ein Baum an gehashter Stelle
            int bx0 = blockX0(j) + int(gx) * s, bz0 = blockZ0(j) + int(gz) * s;
            int reach = 2; // Kronenradius (Bloecke)
            int cx0 = floorDivI(bx0 - reach, 4), cx1 = floorDivI(bx0 + s - 1 + reach, 4);
            int cz0 = floorDivI(bz0 - reach, 4), cz1 = floorDivI(bz0 + s - 1 + reach, 4);
            for (int cz = cz0; cz <= cz1; cz++)
            {
                for (int cx = cx0; cx <= cx1; cx++)
                {
                    uint h = hash3(cx, 0x5eed, cz);
                    int tx = cx * 4 + int(h & 3u), tz = cz * 4 + int((h >> 2) & 3u);
                    // Saeule des Stamms im Knotengitter
                    int tgx = floorDivI(tx - blockX0(j), s), tgz = floorDivI(tz - blockZ0(j), s);
                    if (tgx < 0 || tgz < 0 || tgx >= int(GRID_W) || tgz >= int(GRID_W)) continue;
                    int4 base = gColumn[job * GRID_W * GRID_W + uint(tgz) * GRID_W + uint(tgx)];
                    if (base.x < 0 || base.z == 1) continue;
                    int tbb = base.y * BT_FIELDS;
                    int dens = gBiomeTable[tbb + 7];
                    if (dens <= 0 || int((h >> 8) % 1000u) >= dens * 16) continue; // Dichte je Baumzelle (16 Saeulen)
                    int trunk = 4 + int((h >> 20) % 3u);
                    int groundY = gP.minY + base.x * s + s; // erster Luftblock
                    int crownY = groundY + trunk;
                    uint leaves = uint(gBiomeTable[tbb + 5]) | (uint(base.y) << 16);
                    uint logM = uint(gBiomeTable[tbb + 6]) | (uint(base.y) << 16);
                    // Voxel dieser Saeule, die Stamm oder Krone schneiden
                    for (int vy = me.x + 1; vy < int(hv); vy++)
                    {
                        int y0 = gP.minY + vy * s, y1 = y0 + s - 1;
                        if (y0 > crownY + 1) break;
                        bool isTrunkCol = tx >= bx0 && tx < bx0 + s && tz >= bz0 && tz < bz0 + s;
                        bool trunkHere = isTrunkCol && y1 >= groundY && y0 < crownY;
                        // Krone: Abstand der Saeule zum Stamm <= Radius, Hoehe crownY-2 .. crownY+1
                        int dx = max(0, max(bx0 - tx, tx - (bx0 + s - 1))), dz = max(0, max(bz0 - tz, tz - (bz0 + s - 1)));
                        int rad = (y1 >= crownY) ? 1 : 2;
                        bool leafHere = y1 >= crownY - 2 && y0 <= crownY + 1 && dx * dx + dz * dz <= rad * rad + 1;
                        uint idx = gridIndex(job, gx, uint(vy), gz);
                        if (gGrid[idx].x != 0) continue;
                        if (trunkHere) gGrid[idx] = uint2(logM, 0);
                        else if (leafHere) gGrid[idx] = uint2(leaves, 0);
                    }
                }
            }
        }
        // Vereisung (Vanillas Freeze-Feature): oberster Voxel nach den Baeumen; Temperatur des Bioms,
        // oberhalb Meeresspiegel+17 je Block um 0,05/40 kaelter, unter 0,15 -> Eis auf Wasser, sonst Schnee
        for (int vy = int(hv) - 1; vy >= 0; vy--)
        {
            uint idx = gridIndex(job, gx, uint(vy), gz);
            uint2 cell = gGrid[idx];
            uint m = cell.x & 0xFFFFu;
            if (m == 0) continue;
            int y = gP.minY + vy * s + s - 1;
            float temp = float(gBiomeTable[tb + 8]) / 1000.0;
            if ((gBiomeTable[tb + 11] & 1) != 0) temp = min(temp, 0.0);
            temp -= float(max(0, y - (gP.seaLevel + 17))) * 0.05 / 40.0;
            if (temp < 0.15)
            {
                if (m == gP.waterMat) gGrid[idx] = uint2(uint(gBiomeTable[tb + 10]) | (cell.x & 0xFFFF0000u), 0);
                else if (m != gP.lavaMat && cell.y == 0) gGrid[idx] = uint2(cell.x, uint(gBiomeTable[tb + 9]));
            }
            break;
        }
        return;
    }
}
