// worldgen_core.slang – Interpreter der Density-Programme (DensityProgram.java) fuer die
// LOD-Generierung (lod_gen.slang). Noise in float mit Knoten-Anker (NoiseTerms.java). Der Includer
// definiert die Adressierung der Stufen-Zwischenwerte: ctxLoadFlat, ctxLoadInterp, ctxStore
// und den Typ Ctx (mit Feld block = Weltkoordinate des Punkts).
#ifndef VULKANFISH_WORLDGEN_CORE
#define VULKANFISH_WORLDGEN_CORE
[[vk::binding(0, 0)]] StructuredBuffer<uint> gProg;
[[vk::binding(1, 0)]] StructuredBuffer<float> gConst;
[[vk::binding(2, 0)]] StructuredBuffer<uint> gPerm;       // 64 Worte (256 Byte) je ImprovedNoise
[[vk::binding(3, 0)]] StructuredBuffer<double> gOffs;     // xo, yo, zo je ImprovedNoise
struct Octave
{
    uint improved;
    float valueFactor;
    double inputFactor;
};
[[vk::binding(4, 0)]] StructuredBuffer<Octave> gOct;
[[vk::binding(5, 0)]] StructuredBuffer<uint4> gNormal;    // firstStart, firstCount, secondStart, secondCount
[[vk::binding(6, 0)]] StructuredBuffer<double> gNormalFactor;
[[vk::binding(7, 0)]] StructuredBuffer<double> gBlendD;   // xzMul, yMul, xzFac, yFac je BlendedNoise
[[vk::binding(8, 0)]] StructuredBuffer<int> gBlendOct;    // minStart, minCount, maxStart, maxCount, mainStart, mainCount

static const uint OP_END = 0, OP_CONST = 1, OP_COORD = 2, OP_ADD = 3, OP_MUL = 4, OP_MIN = 5, OP_MAX = 6,
    OP_ADDK = 7, OP_MULK = 8, OP_MAP = 9, OP_CLAMP = 10, OP_YGRAD = 11, OP_NOISE = 12, OP_SNOISE = 13,
    OP_SHIFTA = 14, OP_SHIFTB = 15, OP_SHIFT = 16, OP_RANGE = 17, OP_INTERVAL = 18, OP_SPLINE = 19,
    OP_BLENDED = 20, OP_LOADF = 21, OP_LOADI = 22, OP_STORE = 23, OP_SUB = 24, OP_DIV = 25, OP_LERP = 26;
static const uint CONST_REF = 0x80000000u;
static const uint MAX_REGS = 32;

static const int3 GRAD[16] = { int3(1, 1, 0), int3(-1, 1, 0), int3(1, -1, 0), int3(-1, -1, 0), int3(1, 0, 1), int3(-1, 0, 1),
    int3(1, 0, -1), int3(-1, 0, -1), int3(0, 1, 1), int3(0, -1, 1), int3(0, 1, -1), int3(0, -1, -1),
    int3(1, 1, 0), int3(0, -1, 1), int3(-1, 1, 0), int3(0, -1, -1) };

uint perm(uint n, int i)
{
    uint idx = uint(i) & 255u;
    return (gPerm[n * 64 + (idx >> 2)] >> ((idx & 3u) * 8u)) & 255u;
}

float grad(uint h, float x, float y, float z)
{
    int3 g = GRAD[h & 15u];
    return float(g.x) * x + float(g.y) * y + float(g.z) * z;
}

float smooth(float t) { return t * t * t * (t * (t * 6.0 - 15.0) + 10.0); }

// ---------- Noise ohne double (NoiseTerms.java) ----------
// Der Includer liefert wgLocal(c) = Punkt relativ zum Knotenursprung (y absolut) und
// wgJobTerm0(c) = erster Eintrag des Knotens in gJobTerm.
struct NoiseTerm
{
    float4 kxy;   // Kx hoch, Kx tief, Ky hoch, Ky tief
    float4 kzs;   // Kz hoch, Kz tief, Verschiebungsfaktor, yScale
    uint4 info;   // ImprovedNoise, Quellbits (2 je Achse: x, y, z, keine)
};
[[vk::binding(21, 0)]] StructuredBuffer<NoiseTerm> gTerm;
[[vk::binding(22, 0)]] StructuredBuffer<uint> gTermBase;   // je Programmwort
[[vk::binding(23, 0)]] StructuredBuffer<float4> gJobTerm;  // je Knoten und Term: (Ursprung * K + Versatz) mod 256

float mod256(float v) { return v - floor(v * (1.0 / 256.0)) * 256.0; }

int termSrc(uint bits, int3 d)
{
    return bits == 0 ? d.x : bits == 1 ? d.y : bits == 2 ? d.z : 0;
}

float3 termCoord(uint jt0, uint t, int3 d, float3 s, out NoiseTerm m)
{
    m = gTerm[t];
    float4 a = gJobTerm[jt0 + t];
    uint sb = m.info.y;
    float dx = float(termSrc(sb & 3u, d)), dy = float(termSrc((sb >> 2) & 3u, d)), dz = float(termSrc((sb >> 4) & 3u, d));
    float sh = m.kzs.z;
    return float3(a.x + mod256(dx * m.kxy.x) + dx * m.kxy.y + s.x * sh,
                  a.y + mod256(dy * m.kxy.z) + dy * m.kxy.w + s.y * sh,
                  a.z + mod256(dz * m.kzs.x) + dz * m.kzs.y + s.z * sh);
}

// wie improved(), Koordinate bereits inklusive Versatz und modulo 256
float improvedF(uint n, float3 v, float yScale, float yFudge)
{
    float3 fl = floor(v);
    int xf = int(fl.x), yf = int(fl.y), zf = int(fl.z);
    float xr = v.x - fl.x, yr = v.y - fl.y, zr = v.z - fl.z;
    float yy = yr;
    if (yScale != 0.0)
    {
        float fudgeLimit = (yFudge >= 0.0 && yFudge < yr) ? yFudge : yr;
        yy = yr - floor(fudgeLimit / yScale + 1.0000000116860974e-7) * yScale;
    }
    uint x0 = perm(n, xf), x1 = perm(n, xf + 1);
    uint xy00 = perm(n, int(x0) + yf), xy01 = perm(n, int(x0) + yf + 1);
    uint xy10 = perm(n, int(x1) + yf), xy11 = perm(n, int(x1) + yf + 1);
    float d000 = grad(perm(n, int(xy00) + zf), xr, yy, zr);
    float d100 = grad(perm(n, int(xy10) + zf), xr - 1.0, yy, zr);
    float d010 = grad(perm(n, int(xy01) + zf), xr, yy - 1.0, zr);
    float d110 = grad(perm(n, int(xy11) + zf), xr - 1.0, yy - 1.0, zr);
    float d001 = grad(perm(n, int(xy00) + zf + 1), xr, yy, zr - 1.0);
    float d101 = grad(perm(n, int(xy10) + zf + 1), xr - 1.0, yy, zr - 1.0);
    float d011 = grad(perm(n, int(xy01) + zf + 1), xr, yy - 1.0, zr - 1.0);
    float d111 = grad(perm(n, int(xy11) + zf + 1), xr - 1.0, yy - 1.0, zr - 1.0);
    float xa = smooth(xr), ya = smooth(yr), za = smooth(zr);
    return lerp(lerp(lerp(d000, d100, xa), lerp(d010, d110, xa), ya), lerp(lerp(d001, d101, xa), lerp(d011, d111, xa), ya), za);
}

float normalNoiseF(uint idx, uint t0, uint jt0, int3 d, float3 s)
{
    uint4 n = gNormal[idx];
    float v = 0.0;
    uint t = t0;
    NoiseTerm m;
    for (uint i = n.x; i < n.x + n.y; i++, t++)
        v += gOct[i].valueFactor * improvedF(gOct[i].improved, termCoord(jt0, t, d, s, m), 0.0, 0.0);
    for (uint i = n.z; i < n.z + n.w; i++, t++)
        v += gOct[i].valueFactor * improvedF(gOct[i].improved, termCoord(jt0, t, d, s, m), 0.0, 0.0);
    return v * float(gNormalFactor[idx]);
}

float blendStack(uint octStart, uint octCount, uint tBase, uint jt0, int3 d, float by)
{
    float v = 0.0;
    for (uint j = 0; j < octCount; j++)
    {
        uint o = octStart + j;
        NoiseTerm m;
        float3 vv = termCoord(jt0, tBase + j, d, float3(0.0), m);
        v += gOct[o].valueFactor * improvedF(gOct[o].improved, vv, m.kzs.w, by * (m.kxy.z + m.kxy.w));
    }
    return v;
}

// 26.3: lerp(clamp(main + 0.5), min, max); Stapel als Oktav-Bereiche, Smear je Lage (Term).
float blendedNoiseF(uint idx, uint t0, uint jt0, int3 d)
{
    float by = float(d.y);
    uint minS = uint(gBlendOct[idx * 6]), minC = uint(gBlendOct[idx * 6 + 1]);
    uint maxS = uint(gBlendOct[idx * 6 + 2]), maxC = uint(gBlendOct[idx * 6 + 3]);
    uint mainS = uint(gBlendOct[idx * 6 + 4]), mainC = uint(gBlendOct[idx * 6 + 5]);
    float mainV = blendStack(mainS, mainC, t0 + minC + maxC, jt0, d, by);
    float choice = clamp(mainV + 0.5, 0.0, 1.0);
    if (choice >= 1.0)
        return blendStack(maxS, maxC, t0 + minC, jt0, d, by);
    if (choice <= 0.0)
        return blendStack(minS, minC, t0, jt0, d, by);
    return lerp(blendStack(minS, minC, t0, jt0, d, by), blendStack(maxS, maxC, t0 + minC, jt0, d, by), choice);
}

float mapOp(uint type, float v)
{
    switch (type)
    {
    case 0: return abs(v);
    case 1: return v * v;
    case 2: return v * v * v;
    case 3: return v > 0.0 ? v : v * 0.5;
    case 4: return v > 0.0 ? v : v * 0.25;
    case 5: return 1.0 / v;
    case 7: return -v;
    case 8: return sqrt(v);
    case 9: return log(v);
    case 10: return sign(v);
    default:
    {
        float c = clamp(v, -1.0, 1.0);
        return c / 2.0 - c * c * c / 24.0;
    }
    }
}

#define SPLINE_VALUE(v) (((v) & CONST_REF) != 0 ? gConst[(v) & ~CONST_REF] : r[(v)])

// Fuehrt das Programm der Stufe am Punkt aus; Rueckgabe: Ergebnisregister (V)
void wgExec(inout Ctx c, uint progOffset, uint stage, inout float r[MAX_REGS])
{
    uint pc = progOffset;
    int3 b = c.block;
    for (uint guard = 0; guard < 65536; guard++)
    {
        uint op = gProg[pc];
        if (op == OP_END)
            break;
        uint d = gProg[pc + 1];
        switch (op)
        {
        case OP_CONST: r[d] = gConst[gProg[pc + 2]]; pc += 3; break;
        case OP_COORD: { uint ax = gProg[pc + 2]; r[d] = float(ax == 0 ? b.x : ax == 1 ? b.y : b.z); pc += 3; break; }
        case OP_ADD: r[d] = r[gProg[pc + 2]] + r[gProg[pc + 3]]; pc += 4; break;
        case OP_MUL: { float a = r[gProg[pc + 2]]; r[d] = a == 0.0 ? 0.0 : a * r[gProg[pc + 3]]; pc += 4; break; }
        case OP_MIN: r[d] = min(r[gProg[pc + 2]], r[gProg[pc + 3]]); pc += 4; break;
        case OP_MAX: r[d] = max(r[gProg[pc + 2]], r[gProg[pc + 3]]); pc += 4; break;
        case OP_SUB: r[d] = r[gProg[pc + 2]] - r[gProg[pc + 3]]; pc += 4; break;
        case OP_DIV: r[d] = r[gProg[pc + 2]] / r[gProg[pc + 3]]; pc += 4; break;
        case OP_LERP:
        {
            float a = r[gProg[pc + 2]];
            r[d] = a == 0.0 ? r[gProg[pc + 3]] : a == 1.0 ? r[gProg[pc + 4]]
                : lerp(r[gProg[pc + 3]], r[gProg[pc + 4]], a);
            pc += 5;
            break;
        }
        case OP_ADDK: r[d] = r[gProg[pc + 2]] + gConst[gProg[pc + 3]]; pc += 4; break;
        case OP_MULK: r[d] = r[gProg[pc + 2]] * gConst[gProg[pc + 3]]; pc += 4; break;
        case OP_MAP: r[d] = mapOp(gProg[pc + 3], r[gProg[pc + 2]]); pc += 4; break;
        case OP_CLAMP: r[d] = clamp(r[gProg[pc + 2]], gConst[gProg[pc + 3]], gConst[gProg[pc + 4]]); pc += 5; break;
        case OP_YGRAD:
        {
            float y0 = gConst[gProg[pc + 2]], y1 = gConst[gProg[pc + 3]];
            float t = saturate((float(b.y) - y0) / (y1 - y0));
            r[d] = lerp(gConst[gProg[pc + 4]], gConst[gProg[pc + 5]], t);
            pc += 6;
            break;
        }
        case OP_NOISE: r[d] = normalNoiseF(gProg[pc + 2], gTermBase[pc], wgJobTerm0(c), wgLocal(c), float3(0.0)); pc += 5; break;
        case OP_SNOISE:
            r[d] = normalNoiseF(gProg[pc + 2], gTermBase[pc], wgJobTerm0(c), wgLocal(c), float3(r[gProg[pc + 3]], r[gProg[pc + 4]], r[gProg[pc + 5]]));
            pc += 8;
            break;
        case OP_SHIFTA:
        case OP_SHIFTB:
        case OP_SHIFT: r[d] = normalNoiseF(gProg[pc + 2], gTermBase[pc], wgJobTerm0(c), wgLocal(c), float3(0.0)) * 4.0; pc += 3; break;
        case OP_BLENDED: r[d] = blendedNoiseF(gProg[pc + 2], gTermBase[pc], wgJobTerm0(c), wgLocal(c)); pc += 3; break;
        case OP_RANGE:
        {
            float v = r[gProg[pc + 2]];
            r[d] = (v >= gConst[gProg[pc + 3]] && v < gConst[gProg[pc + 4]]) ? r[gProg[pc + 5]] : r[gProg[pc + 6]];
            pc += 7;
            break;
        }
        case OP_INTERVAL:
        {
            float v = r[gProg[pc + 2]];
            uint n = gProg[pc + 3], kOff = gProg[pc + 4];
            uint sel = n - 1;
            for (uint i = 0; i + 1 < n; i++)
            {
                if (v < gConst[kOff + i]) { sel = i; break; }
            }
            r[d] = r[gProg[pc + 5 + sel]];
            pc += 5 + n;
            break;
        }
        case OP_SPLINE:
        {
            float inV = r[gProg[pc + 2]];
            uint n = gProg[pc + 3], kOff = gProg[pc + 4];
            int start = -1;
            for (uint i = 0; i < n; i++)
            {
                if (inV < gConst[kOff + i]) break;
                start = int(i);
            }
            float result;
            if (start < 0 || start == int(n) - 1)
            {
                uint idx = start < 0 ? 0 : n - 1;
                uint vref = gProg[pc + 5 + idx];
                float val = SPLINE_VALUE(vref);
                float der = gConst[kOff + n + idx];
                result = der == 0.0 ? val : val + der * (inV - gConst[kOff + idx]);
            }
            else
            {
                uint s = uint(start);
                float x1 = gConst[kOff + s], x2 = gConst[kOff + s + 1];
                float t = (inV - x1) / (x2 - x1);
                float d1 = gConst[kOff + n + s], d2 = gConst[kOff + n + s + 1];
                uint v1 = gProg[pc + 5 + s], v2 = gProg[pc + 6 + s];
                float y1 = SPLINE_VALUE(v1), y2 = SPLINE_VALUE(v2);
                float a = d1 * (x2 - x1) - (y2 - y1);
                float bb = -d2 * (x2 - x1) + (y2 - y1);
                result = lerp(y1, y2, t) + t * (1.0 - t) * lerp(a, bb, t);
            }
            r[d] = result;
            pc += 5 + n;
            break;
        }
        case OP_LOADF: r[d] = ctxLoadFlat(c, gProg[pc + 2]); pc += 3; break;
        case OP_LOADI: r[d] = ctxLoadInterp(c, gProg[pc + 2]); pc += 3; break;
        case OP_STORE: ctxStore(c, gProg[pc + 1], r[gProg[pc + 2]]); pc += 3; break;
        default:
            pc += 1;
            break;
        }
    }
}


#endif
