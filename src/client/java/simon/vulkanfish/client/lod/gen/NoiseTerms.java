package simon.vulkanfish.client.lod.gen;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Noise-Koordinaten fuer die LOD-Generierung ohne double auf der GPU. Jede Oktave eines
 * Noise-Aufrufs ist ein "Term": Gitterkoordinate je Achse = Quelle (x, y, z oder keine) mal
 * Faktor K plus Versatz der ImprovedNoise. ImprovedNoise ist mit 256 periodisch, also reicht
 * die Koordinate modulo 256. Den grossen Anteil (Knotenursprung * K + Versatz) rechnet die CPU
 * je Knoten in double und reduziert ihn; auf der GPU bleibt nur der kleine lokale Rest in float
 * (Produkt mit auf 12 Bit gekuerztem K exakt, Rest klein).
 *
 * <p>GPU-Formate: Term = 3 x 16 Byte (Kx hoch/tief, Ky hoch/tief | Kz hoch/tief, Verschiebungs-
 * faktor, yScale | ImprovedNoise, Quellbits, 0, 0); je Knoten und Term ein float4
 * (reduzierter Anteil je Achse); je Programmwort der erste Term des Befehls dort.
 */
public final class NoiseTerms {
    public static final int SRC_X = 0, SRC_Y = 1, SRC_Z = 2, SRC_NONE = 3;
    private static final double F2 = 1.0181268882175227; // zweite Perlin-Haelfte von NormalNoise

    private record Term(int improved, int[] src, double[] k, double shift, double yScale) {
    }

    private final List<Term> terms = new ArrayList<>();
    private final double[] offs; // xo, yo, zo je PerlinNoise
    public final int[] termBase;

    public NoiseTerms(DensityProgram p) {
        offs = new double[p.improved.size() * 3];
        for (int i = 0; i < p.improved.size(); i++) {
            double[] o = simon.vulkanfish.client.lod.gen.NoiseTables.offsets(p.improved.get(i));
            if (o != null) {
                offs[i * 3] = o[0];
                offs[i * 3 + 1] = o[1];
                offs[i * 3 + 2] = o[2];
            }
        }
        int words = 0;
        for (var c : p.code) words += c.size();
        termBase = new int[words];
        int base = 0;
        for (var c : p.code) {
            for (int pc = 0; pc < c.size() && c.getInt(pc) != DensityProgram.OP_END; pc += DensityProgram.length(c, pc)) {
                int op = c.getInt(pc);
                int first = terms.size();
                switch (op) {
                    case DensityProgram.OP_NOISE, DensityProgram.OP_SNOISE -> {
                        boolean shifted = op == DensityProgram.OP_SNOISE;
                        double xz = p.consts.getFloat(c.getInt(pc + (shifted ? 6 : 3)));
                        double ys = p.consts.getFloat(c.getInt(pc + (shifted ? 7 : 4)));
                        normal(p, c.getInt(pc + 2), new int[]{SRC_X, SRC_Y, SRC_Z}, xz, ys, shifted);
                    }
                    case DensityProgram.OP_SHIFTA -> normal(p, c.getInt(pc + 2), new int[]{SRC_X, SRC_NONE, SRC_Z}, 0.25, 0.25, false);
                    case DensityProgram.OP_SHIFTB -> normal(p, c.getInt(pc + 2), new int[]{SRC_Z, SRC_X, SRC_NONE}, 0.25, 0.25, false);
                    case DensityProgram.OP_SHIFT -> normal(p, c.getInt(pc + 2), new int[]{SRC_X, SRC_Y, SRC_Z}, 0.25, 0.25, false);
                    case DensityProgram.OP_BLENDED -> blended(p, p.blended.get(c.getInt(pc + 2)));
                    default -> {
                        continue;
                    }
                }
                termBase[base + pc] = first;
            }
            base += c.size();
        }
    }

    public int count() {
        return terms.size();
    }

    private void normal(DensityProgram p, int idx, int[] src, double xz, double ys, boolean shifted) {
        // 26.3: flache Lagen (Frequenz/Amplitude/Fudge je Oktave, keine Halbierung mehr)
        int start = p.normals.getInt(idx * 2), count = p.normals.getInt(idx * 2 + 1);
        for (int i = start; i < start + count; i++) {
            double[] o = p.octaves.get(i);
            double f = o[1];
            double[] k = new double[3];
            for (int a = 0; a < 3; a++) k[a] = src[a] == SRC_NONE ? 0 : (a == 1 ? ys : xz) * f;
            terms.add(new Term((int) o[0], src, k, shifted ? f : 0, o[3]));
        }
    }

    /**
     * Reihenfolge wie gBlendOct: min-, max-, Haupt-Stapel je als Oktav-Bereich;
     * Smear je Lage (Fudge), Skalen aus den Blend-Skalaren.
     */
    private void blended(DensityProgram p, double[] d) {
        double xzMul = d[0], yMul = d[1], xzFac = d[2], yFac = d[3];
        int[] src = {SRC_X, SRC_Y, SRC_Z};
        stack(p, xzMul, yMul, (int) d[6], (int) d[7], src);
        stack(p, xzMul, yMul, (int) d[8], (int) d[9], src);
        stack(p, xzMul / xzFac, yMul / yFac, (int) d[10], (int) d[11], src);
    }

    private void stack(DensityProgram p, double xz, double ys, int start, int count, int[] src) {
        for (int i = start; i < start + count; i++) {
            double[] o = p.octaves.get(i);
            terms.add(new Term((int) o[0], src, new double[]{xz * o[1], ys * o[1], xz * o[1]}, 0, o[3]));
        }
    }

    /** Auf 12 signifikante Bits gekuerzt: Produkt mit einer Ganzzahl bis 2^12 bleibt in float exakt. */
    private static float hi(double k) {
        if (k == 0) return 0;
        double ulp = Math.scalb(1.0, Math.getExponent(k) - 11);
        return (float) (Math.rint(k / ulp) * ulp);
    }

    /** Statische Termtabelle (48 Byte je Term). */
    public void writeStatic(ByteBuffer b) {
        for (int t = 0; t < terms.size(); t++) {
            Term m = terms.get(t);
            int o = t * 48;
            float kxh = hi(m.k[0]), kyh = hi(m.k[1]), kzh = hi(m.k[2]);
            b.putFloat(o, kxh).putFloat(o + 4, (float) (m.k[0] - kxh)).putFloat(o + 8, kyh).putFloat(o + 12, (float) (m.k[1] - kyh));
            b.putFloat(o + 16, kzh).putFloat(o + 20, (float) (m.k[2] - kzh)).putFloat(o + 24, (float) m.shift).putFloat(o + 28, (float) m.yScale);
            b.putInt(o + 32, m.improved).putInt(o + 36, m.src[0] | m.src[1] << 2 | m.src[2] << 4).putInt(o + 40, 0).putInt(o + 44, 0);
        }
    }

    /** Knotenanteil: (Ursprung * K + Versatz) mod 256 je Achse; y-Quelle ohne Ursprung (y klein). */
    public void writeAnchor(ByteBuffer b, int offset, int anchorX, int anchorZ) {
        for (int t = 0; t < terms.size(); t++) {
            Term m = terms.get(t);
            int o = offset + t * 16;
            for (int a = 0; a < 3; a++) {
                double off = m.improved >= 0 ? offs[m.improved * 3 + a] : 0;
                int src = m.src[a];
                double base = src == SRC_X ? anchorX * m.k[a] : src == SRC_Z ? anchorZ * m.k[a] : 0;
                double v = base + off;
                v -= Math.floor(v / 256.0) * 256.0;
                b.putFloat(o + a * 4, (float) v);
            }
            b.putFloat(o + 12, 0);
        }
    }
}
