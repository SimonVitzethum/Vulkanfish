package simon.vulkanfish.client.lod.gen;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

import static simon.vulkanfish.client.lod.gen.DensityProgram.*;

/**
 * CPU-Interpreter der Density-Programme – dieselben Tabellen und Opcodes wie der GPU-Shader
 * (worldgen.slang). Dient zur Pruefung: einstufiges Programm gegen Vanillas compute().
 */
public final class DensityCpu {
    private static final int[][] GRADIENT = {{1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0}, {1, 0, 1}, {-1, 0, 1}, {1, 0, -1},
            {-1, 0, -1}, {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1}, {1, 1, 0}, {0, -1, 1}, {-1, 1, 0}, {0, -1, -1}};

    private final DensityProgram p;
    private final byte[][] perm;
    private final double[][] offs;

    public DensityCpu(DensityProgram p) {
        this.p = p;
        perm = new byte[p.improved.size()][];
        offs = new double[p.improved.size()][];
        for (int i = 0; i < p.improved.size(); i++) {
            PerlinNoise n = p.improved.get(i);
            perm[i] = NoiseTables.perms(n);
            offs[i] = NoiseTables.offsets(n);
        }
    }

    /** Einstufiges Programm (compileDirect) an einem Blockpunkt. */
    public double evalDirect(int x, int y, int z) {
        double[] r = new double[Math.max(1, p.regCount[STAGE_V])];
        IntArrayList c = p.code[STAGE_V];
        for (int pc = 0; ; ) {
            int op = c.getInt(pc);
            if (op == OP_END) break;
            int d = c.getInt(pc + 1);
            switch (op) {
                case OP_CONST -> r[d] = k(c.getInt(pc + 2));
                case OP_ADD -> r[d] = r[c.getInt(pc + 2)] + r[c.getInt(pc + 3)];
                case OP_MUL -> {
                    double a = r[c.getInt(pc + 2)];
                    r[d] = a == 0.0 ? 0.0 : a * r[c.getInt(pc + 3)];
                }
                case OP_MIN -> r[d] = Math.min(r[c.getInt(pc + 2)], r[c.getInt(pc + 3)]);
                case OP_MAX -> r[d] = Math.max(r[c.getInt(pc + 2)], r[c.getInt(pc + 3)]);
                case OP_SUB -> r[d] = r[c.getInt(pc + 2)] - r[c.getInt(pc + 3)];
                case OP_DIV -> r[d] = r[c.getInt(pc + 2)] / r[c.getInt(pc + 3)];
                case OP_LERP -> {
                    double a = r[c.getInt(pc + 2)];
                    r[d] = a == 0.0 ? r[c.getInt(pc + 3)]
                            : a == 1.0 ? r[c.getInt(pc + 4)]
                            : r[c.getInt(pc + 3)] + a * (r[c.getInt(pc + 4)] - r[c.getInt(pc + 3)]);
                }
                case OP_ADDK -> r[d] = r[c.getInt(pc + 2)] + k(c.getInt(pc + 3));
                case OP_MULK -> r[d] = r[c.getInt(pc + 2)] * k(c.getInt(pc + 3));
                case OP_MAP -> r[d] = map(c.getInt(pc + 3), r[c.getInt(pc + 2)]);
                case OP_CLAMP -> r[d] = Math.max(k(c.getInt(pc + 3)), Math.min(k(c.getInt(pc + 4)), r[c.getInt(pc + 2)]));
                case OP_YGRAD -> r[d] = clampedMap(y, k(c.getInt(pc + 2)), k(c.getInt(pc + 3)), k(c.getInt(pc + 4)), k(c.getInt(pc + 5)));
                case OP_NOISE -> {
                    double xz = k(c.getInt(pc + 3)), ys = k(c.getInt(pc + 4));
                    r[d] = normal(c.getInt(pc + 2), x * xz, y * ys, z * xz);
                }
                case OP_SNOISE -> {
                    double xz = k(c.getInt(pc + 6)), ys = k(c.getInt(pc + 7));
                    r[d] = normal(c.getInt(pc + 2), x * xz + r[c.getInt(pc + 3)], y * ys + r[c.getInt(pc + 4)], z * xz + r[c.getInt(pc + 5)]);
                }
                case OP_SHIFTA -> r[d] = normal(c.getInt(pc + 2), x * 0.25, 0.0, z * 0.25) * 4.0;
                case OP_SHIFTB -> r[d] = normal(c.getInt(pc + 2), z * 0.25, x * 0.25, 0.0) * 4.0;
                case OP_SHIFT -> r[d] = normal(c.getInt(pc + 2), x * 0.25, y * 0.25, z * 0.25) * 4.0;
                case OP_RANGE -> {
                    double v = r[c.getInt(pc + 2)];
                    r[d] = v >= k(c.getInt(pc + 3)) && v < k(c.getInt(pc + 4)) ? r[c.getInt(pc + 5)] : r[c.getInt(pc + 6)];
                }
                case OP_INTERVAL -> {
                    double v = r[c.getInt(pc + 2)];
                    int n = c.getInt(pc + 3), kOff = c.getInt(pc + 4);
                    int sel = n - 1;
                    for (int i = 0; i < n - 1; i++) {
                        if (v < p.consts.getFloat(kOff + i)) {
                            sel = i;
                            break;
                        }
                    }
                    r[d] = r[c.getInt(pc + 5 + sel)];
                }
                case OP_SPLINE -> r[d] = spline(c, pc, r);
                case OP_BLENDED -> r[d] = blended(c.getInt(pc + 2), x, y, z);
                default -> throw new IllegalStateException("Opcode " + op + " im Direktprogramm");
            }
            pc += DensityProgram.length(c, pc);
        }
        return r[p.resultReg];
    }

    private double k(int i) {
        return p.consts.getFloat(i);
    }

    private static double clampedMap(double v, double a, double b, double c, double d) {
        double t = (v - a) / (b - a);
        t = Math.max(0.0, Math.min(1.0, t));
        return c + t * (d - c);
    }

    static double map(int type, double v) {
        return switch (type) {
            case 0 -> Math.abs(v);
            case 1 -> v * v;
            case 2 -> v * v * v;
            case 3 -> v > 0 ? v : v * 0.5;
            case 4 -> v > 0 ? v : v * 0.25;
            case 5 -> 1.0 / v;
            case 7 -> -v;
            case 8 -> Math.sqrt(v);
            case 9 -> Math.log(v);
            case 10 -> Math.signum(v);
            default -> {
                double c = Math.max(-1.0, Math.min(1.0, v));
                yield c / 2.0 - c * c * c / 24.0;
            }
        };
    }

    private double spline(IntArrayList c, int pc, double[] r) {
        float in = (float) r[c.getInt(pc + 2)];
        int n = c.getInt(pc + 3), kOff = c.getInt(pc + 4);
        // Mth.binarySearch(0, n, i -> in < loc[i]) - 1
        int start = -1;
        for (int i = 0; i < n; i++) {
            if (in < p.consts.getFloat(kOff + i)) break;
            start = i;
        }
        if (start < 0) return linearExtend(in, kOff, n, value(c, pc, 0, r), 0);
        if (start == n - 1) return linearExtend(in, kOff, n, value(c, pc, n - 1, r), n - 1);
        float x1 = p.consts.getFloat(kOff + start), x2 = p.consts.getFloat(kOff + start + 1);
        float t = (in - x1) / (x2 - x1);
        float d1 = p.consts.getFloat(kOff + n + start), d2 = p.consts.getFloat(kOff + n + start + 1);
        float y1 = value(c, pc, start, r), y2 = value(c, pc, start + 1, r);
        float a = d1 * (x2 - x1) - (y2 - y1);
        float b = -d2 * (x2 - x1) + (y2 - y1);
        return (y1 + t * (y2 - y1)) + t * (1.0f - t) * (a + t * (b - a));
    }

    private float value(IntArrayList c, int pc, int i, double[] r) {
        int v = c.getInt(pc + 5 + i);
        return (v & CONST_REF) != 0 ? p.consts.getFloat(v & ~CONST_REF) : (float) r[v];
    }

    private float linearExtend(float in, int kOff, int n, float value, int index) {
        float der = p.consts.getFloat(kOff + n + index);
        return der == 0.0f ? value : value + der * (in - p.consts.getFloat(kOff + index));
    }

    /** 26.3: flache Lagen (Frequenz + Amplitude fix und fertig, Fudge je Oktave). */
    double normal(int idx, double x, double y, double z) {
        int start = p.normals.getInt(idx * 2), count = p.normals.getInt(idx * 2 + 1);
        double v = 0.0;
        for (int i = start; i < start + count; i++) {
            double[] o = p.octaves.get(i);
            v += o[2] * improved((int) o[0], wrap(x * o[1]), wrap(y * o[1]), wrap(z * o[1]), o[3], y * o[1]);
        }
        return v;
    }

    private double perlin(int start, int count, double x, double y, double z) {
        double v = 0.0;
        for (int i = start; i < start + count; i++) {
            double[] o = p.octaves.get(i);
            double fac = o[1];
            v += o[2] * improved((int) o[0], wrap(x * fac), wrap(y * fac), wrap(z * fac), o[3], y * fac);
        }
        return v;
    }

    static double wrap(double x) {
        return x - (double) lfloor(x / 3.3554432E7 + 0.5) * 3.3554432E7;
    }

    private static long lfloor(double v) {
        long i = (long) v;
        return v < (double) i ? i - 1L : i;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < (double) i ? i - 1 : i;
    }

    double improved(int n, double _x, double _y, double _z, double yScale, double yFudge) {
        double[] o = offs[n];
        double x = _x + o[0], y = _y + o[1], z = _z + o[2];
        int xf = floor(x), yf = floor(y), zf = floor(z);
        double xr = x - xf, yr = y - yf, zr = z - zf;
        double yrFudge = 0.0;
        if (yScale != 0.0) {
            double fudgeLimit = yFudge >= 0.0 && yFudge < yr ? yFudge : yr;
            yrFudge = (double) floor(fudgeLimit / yScale + (double) 1.0E-7f) * yScale;
        }
        byte[] pm = perm[n];
        int x0 = pm[xf & 255] & 255, x1 = pm[(xf + 1) & 255] & 255;
        int xy00 = pm[(x0 + yf) & 255] & 255, xy01 = pm[(x0 + yf + 1) & 255] & 255;
        int xy10 = pm[(x1 + yf) & 255] & 255, xy11 = pm[(x1 + yf + 1) & 255] & 255;
        double yy = yr - yrFudge;
        double d000 = grad(pm[(xy00 + zf) & 255], xr, yy, zr);
        double d100 = grad(pm[(xy10 + zf) & 255], xr - 1, yy, zr);
        double d010 = grad(pm[(xy01 + zf) & 255], xr, yy - 1, zr);
        double d110 = grad(pm[(xy11 + zf) & 255], xr - 1, yy - 1, zr);
        double d001 = grad(pm[(xy00 + zf + 1) & 255], xr, yy, zr - 1);
        double d101 = grad(pm[(xy10 + zf + 1) & 255], xr - 1, yy, zr - 1);
        double d011 = grad(pm[(xy01 + zf + 1) & 255], xr, yy - 1, zr - 1);
        double d111 = grad(pm[(xy11 + zf + 1) & 255], xr - 1, yy - 1, zr - 1);
        double xa = smooth(xr), ya = smooth(yr), za = smooth(zr);
        return lerp(za, lerp(ya, lerp(xa, d000, d100), lerp(xa, d010, d110)), lerp(ya, lerp(xa, d001, d101), lerp(xa, d011, d111)));
    }

    private static double grad(byte h, double x, double y, double z) {
        int[] g = GRADIENT[h & 15];
        return g[0] * x + g[1] * y + g[2] * z;
    }

    private static double smooth(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    /**
     * BlendedNoise (26.3): lerp(clamp(main + 0.5), min, max); Stapel als Oktav-Bereiche,
     * Smear je Lage (Fudge). d = [xzMul, yMul, xzFac, yFac, limitSmear, mainSmear,
     * minStart, minCount, maxStart, maxCount, mainStart, mainCount].
     */
    private double blended(int idx, int bx, int by, int bz) {
        double[] d = p.blended.get(idx);
        double xzMul = d[0], yMul = d[1], xzFac = d[2], yFac = d[3];
        double minS = stack(d, 6, bx * xzMul, by * yMul, bz * xzMul);
        double maxS = stack(d, 8, bx * xzMul, by * yMul, bz * xzMul);
        double mainS = stack(d, 10, bx * xzMul / xzFac, by * yMul / yFac, bz * xzMul / xzFac);
        double choice = Math.max(0.0, Math.min(1.0, mainS + 0.5));
        return minS + choice * (maxS - minS);
    }

    private double stack(double[] d, int rangeOff, double x, double y, double z) {
        int start = (int) d[rangeOff], count = (int) d[rangeOff + 1];
        double v = 0.0;
        for (int i = start; i < start + count; i++) {
            double[] o = p.octaves.get(i);
            v += o[2] * improved((int) o[0], wrap(x * o[1]), wrap(y * o[1]), wrap(z * o[1]), o[3], y * o[1]);
        }
        return v;
    }
}
