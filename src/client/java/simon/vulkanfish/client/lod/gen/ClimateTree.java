package simon.vulkanfish.client.lod.gen;

import java.util.Arrays;

/**
 * Zweistufiger Huellquader-Baum ueber die Klima-Punkte fuer die GPU-Biomsuche. Die Punkte
 * werden umsortiert (rekursive Median-Teilung entlang der breitesten Achse), in Blaetter zu
 * {@value #LEAF} Punkten und Gruppen zu {@value #GROUP} Blaettern zusammengefasst. Die GPU
 * ueberspringt einen Knoten, wenn schon der Abstand zu seinem Quader groesser als der
 * bisher beste ist – das Ergebnis bleibt die exakte naechste Stelle.
 *
 * <p>Knotenformat (16 float): min/max je Parameter (12), kleinster offset, erstes Kind,
 * Kinderzahl (beide als Bitmuster), 0. Knoten 0 ist der Kopf: erstes Kind = Gruppenzahl,
 * Kinderzahl = Blattzahl. Danach Gruppen, danach Blaetter; Gruppen zeigen auf Blaetter,
 * Blaetter auf Punkte.
 */
public final class ClimateTree {
    public static final int LEAF = 4;
    public static final int GROUP = 32;
    public static final int NODE_FLOATS = 16;

    public final double[] climate;
    public final int[] climateBiome;
    public final float[] nodes;
    public final int groups, leaves;

    private ClimateTree(double[] climate, int[] climateBiome, float[] nodes, int groups, int leaves) {
        this.climate = climate;
        this.climateBiome = climateBiome;
        this.nodes = nodes;
        this.groups = groups;
        this.leaves = leaves;
    }

    /** @param climate je Punkt 14 double (min/max x 6, offset, 0) */
    public static ClimateTree build(double[] climate, int[] biome) {
        int n = biome.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        split(climate, order, 0, n);
        double[] c = new double[n * 14];
        int[] b = new int[n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(climate, order[i] * 14, c, i * 14, 14);
            b[i] = biome[order[i]];
        }
        int leaves = (n + LEAF - 1) / LEAF;
        int groups = (leaves + GROUP - 1) / GROUP;
        float[] nodes = new float[(1 + groups + leaves) * NODE_FLOATS];
        head(nodes, 0, groups, leaves);
        float[][] leafBox = new float[leaves][];
        for (int l = 0; l < leaves; l++) {
            int first = l * LEAF, count = Math.min(LEAF, n - first);
            float[] box = emptyBox();
            for (int i = first; i < first + count; i++) {
                for (int k = 0; k < 6; k++) {
                    box[k * 2] = Math.min(box[k * 2], (float) c[i * 14 + k * 2]);
                    box[k * 2 + 1] = Math.max(box[k * 2 + 1], (float) c[i * 14 + k * 2 + 1]);
                }
                box[12] = Math.min(box[12], (float) Math.abs(c[i * 14 + 12]));
            }
            leafBox[l] = box;
            write(nodes, 1 + groups + l, box, first, count);
        }
        for (int g = 0; g < groups; g++) {
            int first = g * GROUP, count = Math.min(GROUP, leaves - first);
            float[] box = emptyBox();
            for (int l = first; l < first + count; l++) {
                for (int k = 0; k < 12; k += 2) {
                    box[k] = Math.min(box[k], leafBox[l][k]);
                    box[k + 1] = Math.max(box[k + 1], leafBox[l][k + 1]);
                }
                box[12] = Math.min(box[12], leafBox[l][12]);
            }
            write(nodes, 1 + g, box, first, count);
        }
        return new ClimateTree(c, b, nodes, groups, leaves);
    }

    private float bound(int node, float[] t) {
        int o = node * NODE_FLOATS;
        float s = 0;
        for (int k = 0; k < 6; k++) {
            float e = Math.max(Math.max(t[k] - nodes[o + k * 2 + 1], nodes[o + k * 2] - t[k]), 0);
            s += e * e;
        }
        return s + nodes[o + 12] * nodes[o + 12];
    }

    private double dist(int i, double[] t) {
        double d = 0;
        for (int k = 0; k < 6; k++) {
            double above = t[k] - climate[i * 14 + k * 2 + 1], below = climate[i * 14 + k * 2] - t[k];
            double e = above > 0 ? above : Math.max(below, 0);
            d += e * e;
        }
        return d + climate[i * 14 + 12] * climate[i * 14 + 12];
    }

    /**
     * Wie die GPU-Suche in lod_gen.slang: je Gruppe die untere Schranke; naechstgelegene Gruppe
     * zuerst (bestes Blatt darin zuerst), danach alle Gruppen/Blaetter, deren Schranke noch
     * gewinnen kann; Abstand 0 beendet die Suche. stats[0] += Punkte, stats[1] += Blaetter.
     */
    public int search(double[] t, long[] stats) {
        float[] tf = new float[6];
        for (int k = 0; k < 6; k++) tf[k] = (float) t[k];
        double[] best = {1e300};
        int[] bestIdx = {0};
        int first = -1;
        float bl = Float.MAX_VALUE;
        for (int g = 0; g < groups; g++) {
            float lb = bound(1 + g, tf);
            if (lb < bl) {
                bl = lb;
                first = g;
            }
        }
        searchGroup(first, tf, t, best, bestIdx, stats);
        for (int g = 0; g < groups && best[0] > 0; g++) {
            if (g == first || bound(1 + g, tf) * 0.9999 > best[0]) continue;
            searchGroup(g, tf, t, best, bestIdx, stats);
        }
        return bestIdx[0];
    }

    private void searchGroup(int g, float[] tf, double[] t, double[] best, int[] bestIdx, long[] stats) {
        int go = (1 + g) * NODE_FLOATS;
        int l0 = Float.floatToRawIntBits(nodes[go + 13]), ln = Float.floatToRawIntBits(nodes[go + 14]);
        int firstLeaf = l0;
        float bl = Float.MAX_VALUE;
        for (int l = l0; l < l0 + ln; l++) {
            float lb = bound(1 + groups + l, tf);
            if (lb < bl) {
                bl = lb;
                firstLeaf = l;
            }
        }
        if (bl * 0.9999 > best[0]) return;
        searchLeaf(firstLeaf, t, best, bestIdx, stats);
        for (int l = l0; l < l0 + ln && best[0] > 0; l++) {
            if (l == firstLeaf || bound(1 + groups + l, tf) * 0.9999 > best[0]) continue;
            searchLeaf(l, t, best, bestIdx, stats);
        }
    }

    private void searchLeaf(int l, double[] t, double[] best, int[] bestIdx, long[] stats) {
        stats[1]++;
        int lo = (1 + groups + l) * NODE_FLOATS;
        int p0 = Float.floatToRawIntBits(nodes[lo + 13]), pn = Float.floatToRawIntBits(nodes[lo + 14]);
        for (int i = p0; i < p0 + pn; i++) {
            stats[0]++;
            double d = dist(i, t);
            if (d < best[0]) {
                best[0] = d;
                bestIdx[0] = i;
            }
        }
    }

    /** Stichprobe gegen Vanillas Biomquelle: Treffer, Suchaufwand (Log). */
    public void validate(WorldgenSource src, net.minecraft.world.level.biome.BiomeSource biomeSource) {
        var sampler = src.randomState.sampler();
        java.util.Random rnd = new java.util.Random(99);
        int n = 2000, treeVsBrute = 0, vsVanilla = 0;
        long[] stats = new long[2];
        long t0 = System.nanoTime();
        for (int s = 0; s < n; s++) {
            int qx = rnd.nextInt(20000) - 10000, qz = rnd.nextInt(20000) - 10000, qy = rnd.nextInt(96) - 16;
            var tp = sampler.sample(qx, qy, qz);
            double[] t = {tp.temperature(), tp.humidity(), tp.continentalness(), tp.erosion(), tp.depth(), tp.weirdness()};
            int a = search(t, stats), b = bruteForce(t);
            if (climateBiome[a] == climateBiome[b]) treeVsBrute++;
            int v = simon.vulkanfish.client.lod.LodMaterials.biomeId(biomeSource.getNoiseBiome(qx, qy, qz, sampler));
            if (climateBiome[a] == v) vsVanilla++;
        }
        org.slf4j.LoggerFactory.getLogger("vulkanfish").info(
                "[vulkanfish] Klima-Baum: {} Gruppen, {} Blaetter; je Suche {} Punkte, {} Blaetter; Baum=Brute {}/{}, Baum=Vanilla {}/{} ({} ms)",
                groups, leaves, stats[0] / n, stats[1] / n, treeVsBrute, n, vsVanilla, n, (System.nanoTime() - t0) / 1_000_000);
    }

    public int bruteForce(double[] t) {
        double best = 1e300;
        int bestIdx = 0;
        for (int i = 0; i < climateBiome.length; i++) {
            double d = dist(i, t);
            if (d < best) {
                best = d;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private static float[] emptyBox() {
        float[] box = new float[13];
        for (int k = 0; k < 12; k += 2) {
            box[k] = Float.POSITIVE_INFINITY;
            box[k + 1] = Float.NEGATIVE_INFINITY;
        }
        box[12] = Float.POSITIVE_INFINITY;
        return box;
    }

    private static void head(float[] nodes, int node, int a, int b) {
        nodes[node * NODE_FLOATS + 13] = Float.intBitsToFloat(a);
        nodes[node * NODE_FLOATS + 14] = Float.intBitsToFloat(b);
    }

    private static void write(float[] nodes, int node, float[] box, int first, int count) {
        System.arraycopy(box, 0, nodes, node * NODE_FLOATS, 13);
        head(nodes, node, first, count);
    }

    /**
     * Teilung an der Blattgrenze nahe dem Median; Achse (nach Quadermitte sortiert) mit der
     * kleinsten Summe der Kantenlaengen beider Teilquader – enge, wenig ueberlappende Kinder.
     */
    private static void split(double[] c, Integer[] order, int from, int to) {
        if (to - from <= LEAF) return;
        int half = (to - from) / 2;
        int mid = from + Math.max(LEAF, (half + LEAF - 1) / LEAF * LEAF);
        if (mid >= to) return;
        int bestAxis = 0;
        double bestCost = Double.POSITIVE_INFINITY;
        for (int k = 0; k < 7; k++) {
            final int ax = k;
            Arrays.sort(order, from, to, (a, b) -> Double.compare(center(c, a, ax), center(c, b, ax)));
            double cost = span(c, order, from, mid) + span(c, order, mid, to);
            if (cost < bestCost) {
                bestCost = cost;
                bestAxis = k;
            }
        }
        final int ax = bestAxis;
        Arrays.sort(order, from, to, (a, b) -> Double.compare(center(c, a, ax), center(c, b, ax)));
        split(c, order, from, mid);
        split(c, order, mid, to);
    }

    private static double span(double[] c, Integer[] order, int from, int to) {
        double sum = 0;
        for (int k = 0; k < 6; k++) {
            double mn = Double.POSITIVE_INFINITY, mx = Double.NEGATIVE_INFINITY;
            for (int i = from; i < to; i++) {
                mn = Math.min(mn, c[order[i] * 14 + k * 2]);
                mx = Math.max(mx, c[order[i] * 14 + k * 2 + 1]);
            }
            sum += mx - mn;
        }
        double omin = Double.POSITIVE_INFINITY, omax = 0;
        for (int i = from; i < to; i++) {
            omin = Math.min(omin, Math.abs(c[order[i] * 14 + 12]));
            omax = Math.max(omax, Math.abs(c[order[i] * 14 + 12]));
        }
        return sum + (omax - omin);
    }

    private static double center(double[] c, int p, int k) {
        return k == 6 ? c[p * 14 + 12] : (c[p * 14 + k * 2] + c[p * 14 + k * 2 + 1]) * 0.5;
    }
}
