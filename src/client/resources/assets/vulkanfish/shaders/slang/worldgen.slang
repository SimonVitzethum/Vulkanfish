// worldgen.slang – GPU-Dichteauswertung fuer das LOD (eigene Implementierung).
// Fuehrt die vom DensityProgram-Compiler erzeugten Register-Programme aus – das ist
// der echte (geseedete, datapack-bewusste) DensityFunction-Baum der Welt. Drei Stufen
// wie Vanillas NoiseChunk: F (flat_cache, Quart-Raster y=0), C (interpolated, Zellecken
// 4x8x4), V (pro Block: trilinear aus C + Rest). Ausgabe pro Block 2 Bit:
// 0 Luft, 1 Stein (Standardblock), 2 Standardfluessigkeit, 3 Lava.
// Noise-Koordinaten in double (wie Vanilla), Werte in float.

#include "worldgen_core.slang"

[[vk::binding(9, 0)]] StructuredBuffer<int2> gJobs;       // Chunk-Koordinaten
[[vk::binding(10, 0)]] RWStructuredBuffer<float> gFlat;   // [Chunk][25 Quarts][flatSlots]
[[vk::binding(11, 0)]] RWStructuredBuffer<float> gInterp; // [Chunk][Ecken][interpSlots]
[[vk::binding(12, 0)]] RWStructuredBuffer<uint> gOut;     // [Chunk][y][z] je 16 Bloecke x 2 Bit

struct GenPush
{
    uint stage;       // 0 F, 1 C, 2 V
    uint progOffset;
    uint jobCount;
    uint flatSlots;
    uint interpSlots;
    int minY;
    uint height;
    int seaLevel;
    int lavaLevel;
    uint resultReg;
    uint pad0, pad1;
};
[[vk::push_constant]] ConstantBuffer<GenPush> gGen;

static const uint CORNERS_XZ = 5;

// Kontext fuer LOADF/LOADI (Stufe C/V)
struct Ctx
{
    int3 block;      // Weltkoordinate des Punkts
    uint chunk;      // Job-Index
    uint quart;      // F-Gitterindex (qx + qz*5) des Punkts
    uint3 cell;      // Zelle (V): x 0..3, y, z 0..3
    float3 f;        // Zell-Anteile (V)
};

float ctxLoadInterp(Ctx c, uint slot)
{
    uint ny = gGen.height / 8 + 1;
    uint base = c.chunk * (CORNERS_XZ * ny * CORNERS_XZ);
    uint slots = gGen.interpSlots;
    // Ecke (cx, cy, cz) -> Index (cy * 5 + cz) * 5 + cx
    #define CORNER(dx, dy, dz) gInterp[(base + ((c.cell.y + dy) * CORNERS_XZ + (c.cell.z + dz)) * CORNERS_XZ + (c.cell.x + dx)) * slots + slot]
    float n000 = CORNER(0, 0, 0), n100 = CORNER(1, 0, 0), n010 = CORNER(0, 1, 0), n110 = CORNER(1, 1, 0);
    float n001 = CORNER(0, 0, 1), n101 = CORNER(1, 0, 1), n011 = CORNER(0, 1, 1), n111 = CORNER(1, 1, 1);
    #undef CORNER
    float3 f = c.f;
    return lerp(lerp(lerp(n000, n100, f.x), lerp(n010, n110, f.x), f.y), lerp(lerp(n001, n101, f.x), lerp(n011, n111, f.x), f.y), f.z);
}


float ctxLoadFlat(Ctx c, uint slot)
{
    return gFlat[(c.chunk * 25 + c.quart) * gGen.flatSlots + slot];
}

void ctxStore(Ctx c, uint slot, float v)
{
    if (gGen.stage == 0)
    {
        gFlat[(c.chunk * 25 + c.quart) * gGen.flatSlots + slot] = v;
        return;
    }
    uint ny = gGen.height / 8 + 1;
    uint corner = (c.cell.y * CORNERS_XZ + c.cell.z) * CORNERS_XZ + c.cell.x;
    gInterp[(c.chunk * (CORNERS_XZ * ny * CORNERS_XZ) + corner) * gGen.interpSlots + slot] = v;
}

float run(Ctx c)
{
    float r[MAX_REGS];
    wgExec(c, gGen.progOffset, gGen.stage, r);
    return gGen.stage == 2 ? r[gGen.resultReg] : 0.0;
}

[shader("compute")]
[NumThreads(64, 1, 1)]
void worldgenMain(in uint3 tid : SV_DispatchThreadID)
{
    uint i = tid.x;
    Ctx c;
    c.f = float3(0.0);
    c.cell = uint3(0);
    if (gGen.stage == 0)
    {
        // F: 25 Quarts pro Chunk (5x5, inkl. Rand bei x/z = 16), y = 0
        if (i >= gGen.jobCount * 25) return;
        c.chunk = i / 25;
        c.quart = i % 25;
        int2 ch = gJobs[c.chunk];
        c.block = int3(ch.x * 16 + int(c.quart % 5) * 4, 0, ch.y * 16 + int(c.quart / 5) * 4);
        run(c);
        return;
    }
    if (gGen.stage == 1)
    {
        // C: Zellecken 5 x (Hoehe/8+1) x 5
        uint ny = gGen.height / 8 + 1;
        uint per = CORNERS_XZ * ny * CORNERS_XZ;
        if (i >= gGen.jobCount * per) return;
        c.chunk = i / per;
        uint k = i % per;
        c.cell = uint3(k % CORNERS_XZ, k / (CORNERS_XZ * CORNERS_XZ), (k / CORNERS_XZ) % CORNERS_XZ);
        int2 ch = gJobs[c.chunk];
        c.block = int3(ch.x * 16 + int(c.cell.x) * 4, gGen.minY + int(c.cell.y) * 8, ch.y * 16 + int(c.cell.z) * 4);
        c.quart = c.cell.z * 5 + c.cell.x;
        run(c);
        return;
    }
    // V: eine Zeile (16 Bloecke in x) pro Thread -> ein Ausgabewort
    uint rows = 16 * gGen.height;
    if (i >= gGen.jobCount * rows) return;
    c.chunk = i / rows;
    uint rr = i % rows;
    uint lz = rr % 16, ly = rr / 16;
    int2 ch = gJobs[c.chunk];
    int y = gGen.minY + int(ly);
    uint word = 0;
    for (uint lx = 0; lx < 16; lx++)
    {
        c.block = int3(ch.x * 16 + int(lx), y, ch.y * 16 + int(lz));
        c.cell = uint3(lx >> 2, ly >> 3, lz >> 2);
        c.f = float3(float(lx & 3) / 4.0, float(ly & 7) / 8.0, float(lz & 3) / 4.0);
        c.quart = (lz >> 2) * 5 + (lx >> 2);
        float density = run(c);
        uint code = density > 0.0 ? 1u : (y < gGen.lavaLevel ? 3u : (y < gGen.seaLevel ? 2u : 0u));
        word |= code << (lx * 2);
    }
    gOut[i] = word;
}
