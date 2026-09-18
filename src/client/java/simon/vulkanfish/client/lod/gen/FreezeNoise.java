package simon.vulkanfish.client.lod.gen;

import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NoiseStack;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/**
 * Vanillas Klima-Rauschen fuer die Vereisung (26.3: NoiseStack aus SimplexNoise statt
 * PerlinSimplexNoise; Formel und Seeds unveraendert, Tabellen je Lage).
 *
 * <p>Aufteilung CPU/GPU wie beim Dichte-Rauschen (NoiseTerms): 2D-Simplex haengt nur von der
 * Gitterzelle (mod 256, Permutation) und dem Rest im schiefen Gitter ab. Die CPU rechnet je
 * LOD-Knoten und Rauschterm den Anker (Zelle mod 256, Rest) in double, die GPU (lod_gen.slang,
 * frzSimplex) addiert die kleinen Spalten-Offsets in FP32 – genau auch bei hohen Koordinaten.
 *
 * <p>Terme: 0..2 FROZEN (x*0.05, drei Lagen), 3 INFO (x*0.2), 4 INFO (x*0.09), 5 TEMPERATURE (x/8).
 */
public final class FreezeNoise {
    public static final int TERMS = 6;
    private static final double F2 = 0.5 * (Math.sqrt(3.0) - 1.0);

    /** Je Term: Skala (Eingabe * Lagenfrequenz), Wertfaktor (Amplitude), Index der Permutationstabelle. */
    private static final double[] SCALE = new double[TERMS];
    private static final double[] VALUE = new double[TERMS];
    private static final int[] PERM_OF = new int[TERMS];
    /** Permutationstabellen je 256 Eintraege, dahinter 12 Gradienten (x, y). */
    public static final int[] PERM;
    public static final int PERM_TABLES;
    private static final boolean OK;

    private static final Noise FROZEN = Biome.FROZEN_TEMPERATURE_NOISE;
    private static final Noise INFO = Biome.BIOME_INFO_NOISE;
    private static final SimplexNoise TEMP = new SimplexNoise(new WorldgenRandom(new LegacyRandomSource(1234L)), true);

    static {
        int[] perm = new int[0];
        int tables = 0;
        boolean ok = false;
        try {
            Field pF = gradPermField();
            int[][] grad = readGradients();
            perm = new int[5 * 256 + 24];
            int t = 0;
            // FROZEN: drei Lagen des Stacks (Frequenz + Amplitude je Lage)
            java.util.List<NoiseTables.LayerData> frozen = NoiseTables.stackLayers((NoiseStack) FROZEN);
            if (frozen == null) throw new IllegalStateException("FROZEN-Lagen");
            for (NoiseTables.LayerData layer : frozen) {
                SimplexNoise sn = (SimplexNoise) layer.noise();
                int table = tableOf(sn, perm, tables, pF);
                if (table == tables) tables++;
                SCALE[t] = 0.05 * layer.frequency();
                VALUE[t] = layer.amplitude();
                PERM_OF[t] = table;
                t++;
            }
            // INFO mit zwei Skalen (eine Tabelle), TEMPERATURE einmal
            SimplexNoise info = (SimplexNoise) INFO;
            int infoTable = tableOf(info, perm, tables, pF);
            if (infoTable == tables) tables++;
            SCALE[t] = 0.2;
            VALUE[t] = 1.0;
            PERM_OF[t] = infoTable;
            t++;
            SCALE[t] = 0.09;
            VALUE[t] = 1.0;
            PERM_OF[t] = infoTable;
            t++;
            int tempTable = tableOf(TEMP, perm, tables, pF);
            if (tempTable == tables) tables++;
            SCALE[t] = 0.125;
            VALUE[t] = 1.0;
            PERM_OF[t] = tempTable;
            t++;
            for (int g = 0; g < 12; g++) {
                perm[5 * 256 + g * 2] = grad[g][0];
                perm[5 * 256 + g * 2 + 1] = grad[g][1];
            }
            ok = t == TERMS;
        } catch (Throwable e) {
            org.slf4j.LoggerFactory.getLogger("vulkanfish").warn("[vulkanfish] LOD: Vereisungs-Rauschen nicht verfuegbar ({})", e.toString());
        }
        PERM = perm;
        PERM_TABLES = tables;
        OK = ok;
    }

    private FreezeNoise() {
    }

    private static Field gradPermField() throws NoSuchFieldException {
        Field f = net.minecraft.world.level.levelgen.synth.GradientNoise.class.getDeclaredField("perms");
        f.setAccessible(true);
        return f;
    }

    /** 12 Gradienten (x, y): alte int[][]-Tabelle oder neue Gradient-Records (per dot gelesen). */
    private static int[][] readGradients() throws Exception {
        Class<?> gradNoise = net.minecraft.world.level.levelgen.synth.GradientNoise.class;
        Field f = gradNoise.getDeclaredField("GRADIENT");
        f.setAccessible(true);
        Object table = f.get(null);
        Object[] entries = (Object[]) table;
        int[][] grad = new int[12][2];
        java.lang.reflect.Method dot = null;
        for (int g = 0; g < 12; g++) {
            Object e = entries[g];
            if (e instanceof int[] pair) {
                grad[g][0] = pair[0];
                grad[g][1] = pair[1];
            } else {
                if (dot == null) dot = e.getClass().getMethod("dot", double.class, double.class, double.class);
                grad[g][0] = (int) (double) dot.invoke(e, 1.0, 0.0, 0.0);
                grad[g][1] = (int) (double) dot.invoke(e, 0.0, 1.0, 0.0);
            }
        }
        return grad;
    }

    private static int tableOf(SimplexNoise level, int[] perm, int tables, Field pF) throws IllegalAccessException {
        byte[] p = (byte[]) pF.get(level);
        for (int k = 0; k < tables; k++) {
            boolean same = true;
            for (int i = 0; i < 256 && same; i++) same = (perm[k * 256 + i] & 255) == (p[i] & 255);
            if (same) return k; // INFO wird mit zwei Skalen benutzt: eine Tabelle
        }
        for (int i = 0; i < 256; i++) perm[tables * 256 + i] = p[i] & 255;
        return tables;
    }

    /**
     * Selbstpruefung: die Zerlegung Anker (double) + Offset gegen Vanillas eigene Rauschfunktion.
     * Rechnet den GPU-Weg in double nach; liefert die groesste Abweichung.
     */
    public static double selfCheck(int x0, int z0) {
        java.nio.FloatBuffer an = java.nio.FloatBuffer.allocate(TERMS * 4);
        writeAnchors(x0, z0, an, 0);
        double worst = 0;
        for (int dz = 0; dz < 34 * 16; dz += 7) {
            for (int dx = 0; dx < 34 * 16; dx += 5) {
                double sum = 0;
                for (int t = 0; t < 3; t++) sum += gpuPath(an, t, dx, dz);
                double ref = FROZEN.get((x0 + dx) * 0.05, (z0 + dz) * 0.05);
                worst = Math.max(worst, Math.abs(sum - ref));
            }
        }
        return worst;
    }

    private static double gpuPath(java.nio.FloatBuffer an, int t, double dx, double dz) {
        double g2 = (3.0 - Math.sqrt(3.0)) / 6.0;
        double sk = (dx + dz) * F2;
        double u = an.get(t * 4 + 1) + SCALE[t] * (dx + sk), v = an.get(t * 4 + 3) + SCALE[t] * (dz + sk);
        double fi = Math.floor(u), fj = Math.floor(v);
        int ii = (int) an.get(t * 4) + (int) fi, jj = (int) an.get(t * 4 + 2) + (int) fj;
        double fu = u - fi, fv = v - fj, g = (fu + fv) * g2, h = fu - g, k = fv - g;
        int i1 = h > k ? 1 : 0, j1 = 1 - i1, tb = PERM_OF[t] * 256;
        int gi0 = PERM[tb + ((ii + PERM[tb + (jj & 255)]) & 255)] % 12;
        int gi1 = PERM[tb + ((ii + i1 + PERM[tb + ((jj + j1) & 255)]) & 255)] % 12;
        int gi2 = PERM[tb + ((ii + 1 + PERM[tb + ((jj + 1) & 255)]) & 255)] % 12;
        double n = corner(gi0, h, k) + corner(gi1, h - i1 + g2, k - j1 + g2) + corner(gi2, h - 1 + 2 * g2, k - 1 + 2 * g2);
        return 70.0 * n * VALUE[t];
    }

    private static double corner(int gi, double x, double y) {
        double t = 0.5 - x * x - y * y;
        if (t < 0) return 0;
        t *= t;
        return t * t * (PERM[5 * 256 + gi * 2] * x + PERM[5 * 256 + gi * 2 + 1] * y);
    }

    /** Vereist Wasser an (x, z) bei Hoehe y? Gleiche Rechnung wie lod_gen.slang (in double). */
    public static boolean freezes(float baseTemp, boolean frozenModifier, int x, int y, int z, int seaLevel) {
        double t = baseTemp;
        if (frozenModifier) {
            double d = FROZEN.get(x * 0.05, z * 0.05) * 7.0 + INFO.get(x * 0.2, z * 0.2);
            if (d < 0.3 && INFO.get(x * 0.09, z * 0.09) < 0.8) t = 0.2;
        }
        if (y > seaLevel + 17) t -= (TEMP.get((double) ((float) x / 8f), (double) ((float) z / 8f)) * 8.0 + y - (seaLevel + 17)) * 0.05 / 40.0;
        return t < 0.15;
    }

    public static boolean available() {
        return OK;
    }

    /** Je Term 4 floats fuer die GPU: Skala, Wertfaktor, Tabellen-Offset, 0. */
    public static void writeTerms(java.nio.FloatBuffer out) {
        for (int t = 0; t < TERMS; t++) {
            out.put(t * 4, (float) SCALE[t]).put(t * 4 + 1, (float) VALUE[t]).put(t * 4 + 2, PERM_OF[t] * 256).put(t * 4 + 3, 0f);
        }
    }

    /**
     * Anker eines Knotens (erste Spalte bei Block x0, z0) je Term: Zelle im schiefen Gitter mod 256
     * und Rest, in double gerechnet. out: TERMS x (zelle_u, rest_u, zelle_v, rest_v) ab off.
     */
    public static void writeAnchors(int x0, int z0, java.nio.FloatBuffer out, int off) {
        for (int t = 0; t < TERMS; t++) {
            double px, pz;
            if (t == TERMS - 1) { // TEMPERATURE: Vanilla teilt in float
                px = (double) ((float) x0 / 8.0f);
                pz = (double) ((float) z0 / 8.0f);
            } else {
                double s = t < 3 ? 0.05 : t == 3 ? 0.2 : 0.09;
                px = x0 * s;
                pz = z0 * s;
            }
            double in = SCALE[t] / (t < 3 ? 0.05 : t == 3 ? 0.2 : t == 4 ? 0.09 : 0.125);
            px *= in;
            pz *= in;
            double sk = (px + pz) * F2;
            double u = px + sk, v = pz + sk;
            double iu = Math.floor(u), iv = Math.floor(v);
            out.put(off + t * 4, (float) Math.floorMod((long) iu, 256L)).put(off + t * 4 + 1, (float) (u - iu))
                    .put(off + t * 4 + 2, (float) Math.floorMod((long) iv, 256L)).put(off + t * 4 + 3, (float) (v - iv));
        }
    }
}
