package simon.vulkanfish.client.lod;

import java.util.Arrays;

/**
 * Meshet einen LOD-Knoten (32 x Hoehe x 32 Voxel der Stufe l, volle Welthoehe) zu Quads
 * (Greedy-Meshing je Seite und Schicht) und Meshlets zu je 32 Quads. Worker-Thread, eine
 * Instanz pro Thread (Puffer werden wiederverwendet).
 *
 * <p>Nachbarn: ein Voxel Rand aus den angrenzenden Chunks derselben Stufe -> keine inneren
 * Flaechen an Knotengrenzen. Gegen Risse zu Knoten anderer Stufe bekommen die obersten zwei
 * Voxel jeder Randsaeule ihre Aussenseite immer ("Schuerze"): der grobe Nachbar ist wegen der
 * konservativen Verkleinerung hoechstens hoeher, die Luecke dazwischen ist so geschlossen.
 *
 * <p>Quad (2 x uint): x 5 | z 5 | y 9 | Seite 3 | (breite-1) 5 | (hoehe-1) 5;
 * rgb 7:7:7 | Himmelslicht 4 | Emission 4 | Art 3.
 */
public final class LodMesher {
    public static final int QUADS_PER_MESHLET = 32;
    private static final int W = 34; // 32 + Rand
    private static final int SKIRT = 2;

    /** Zugriff auf LOD-Saeulen (Chunk-Koordinaten); null = keine Daten (Luft). */
    public interface ColumnSource {
        LodColumn get(int chunkX, int chunkZ);
    }

    /** Ergebnis in knotenlokalen Quads; Meshlet-Bounds/Ebenen in Weltkoordinaten. */
    public record Mesh(int level, int nodeX, int nodeZ, int quadCount, int[] quads, int meshletCount,
                       int[] meshletQuadStart, int[] meshletQuadCount, float[] meshletBounds, float[] meshletPlanes,
                       float[] aabb) {
    }

    private short[] mat = new short[0];
    private byte[] bio = new byte[0];
    private short[] cov = new short[0];
    private int[] top = new int[W * W];
    private long[] mask = new long[32 * 512];
    private int[] quads = new int[8192];
    private int quadCount;
    private int yLo, yHi; // belegter Hoehenbereich des Knotens (inkl. Rand), ausserhalb gibt es keine Flaechen
    private final int[][] dirQuadStart = new int[6][];

    public Mesh mesh(int level, int nodeX, int nodeZ, int minY, int height, ColumnSource source) {
        int h = height >> level;
        int cells = W * W * h;
        if (mat.length < cells) {
            mat = new short[cells];
            bio = new byte[cells];
            cov = new short[cells];
        } else {
            Arrays.fill(mat, 0, cells, (short) 0);
            Arrays.fill(cov, 0, cells, (short) 0);
        }
        Arrays.fill(top, -1);
        yLo = Integer.MAX_VALUE;
        yHi = -1;
        fill(level, nodeX, nodeZ, h, source);
        if (yHi < 0) return buildMeshlets(level, nodeX, nodeZ, minY, h, new int[6]); // leer

        quadCount = 0;
        int[] dirCount = new int[6];
        for (int dir = 0; dir < 6; dir++) {
            int before = quadCount;
            meshDirection(dir, h);
            dirCount[dir] = quadCount - before;
        }
        return buildMeshlets(level, nodeX, nodeZ, minY, h, dirCount);
    }

    private int idx(int gx, int y, int gz) {
        return (y * W + gz) * W + gx;
    }

    private void fill(int level, int nodeX, int nodeZ, int h, ColumnSource source) {
        int perChunk = 16 >> level; // Voxelsaeulen pro Chunk und Achse (>= 1, Stufe <= 4)
        for (int gz = 0; gz < W; gz++) {
            int vz = nodeZ * 32 + gz - 1;
            int bz = vz << level;
            int cz = Math.floorDiv(bz, 16);
            int lz = (bz & 15) >> level;
            for (int gx = 0; gx < W; gx++) {
                int vx = nodeX * 32 + gx - 1;
                int bx = vx << level;
                int cx = Math.floorDiv(bx, 16);
                int lx = (bx & 15) >> level;
                LodColumn col = source.get(cx, cz);
                if (col == null || !col.hasLevel(level) || lx >= perChunk || lz >= perChunk) continue;
                long[] runs = col.runs(level);
                int to = col.columnTo(level, lx, lz);
                int t = -1;
                for (int i = col.columnFrom(level, lx, lz); i < to; i++) {
                    long r = runs[i];
                    int y0 = LodColumn.runY(r), len = LodColumn.runLen(r);
                    int m = LodColumn.runMat(r), b = LodColumn.runBiome(r), c = LodColumn.runCover(r);
                    int y1 = Math.min(y0 + len, h);
                    for (int y = y0; y < y1; y++) {
                        int k = idx(gx, y, gz);
                        mat[k] = (short) m;
                        bio[k] = (byte) b;
                    }
                    if (y1 > y0) {
                        yLo = Math.min(yLo, y0);
                        yHi = Math.max(yHi, y1 - 1);
                        cov[idx(gx, y1 - 1, gz)] = (short) c;
                        if (LodMaterials.byId(m).occludes()) t = Math.max(t, y1 - 1);
                    }
                }
                top[gz * W + gx] = t;
            }
        }
    }

    // Seiten: 0 +X, 1 -X, 2 +Y, 3 -Y, 4 +Z, 5 -Z
    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};

    private void meshDirection(int dir, int h) {
        int axis = dir >> 1; // 0 X, 1 Y, 2 Z
        // Nur der belegte Hoehenbereich: bei Y-Seiten die Schichten, sonst die v-Zeilen
        int sLo = axis == 1 ? yLo : 0, sHi = axis == 1 ? yHi : 31;
        int uCount = 32; // X-Seiten: u = z; Y: u = x; Z: u = x
        int vLo = axis == 1 ? 0 : yLo, vHi = axis == 1 ? 31 : yHi; // X/Z-Seiten: v = y; Y: v = z
        int vCount = vHi - vLo + 1;
        if (mask.length < uCount * vCount) mask = new long[uCount * vCount];
        for (int s = sLo; s <= sHi; s++) {
            boolean any = false;
            for (int vi = 0; vi < vCount; vi++) {
                int v = vi + vLo;
                for (int u = 0; u < uCount; u++) {
                    int x, y, z;
                    if (axis == 0) { x = s; z = u; y = v; }
                    else if (axis == 1) { y = s; x = u; z = v; }
                    else { z = s; x = u; y = v; }
                    long key = faceKey(x, y, z, dir, h);
                    mask[vi * uCount + u] = key;
                    any |= key != 0;
                }
            }
            if (any) greedy(mask, uCount, vCount, vLo, dir, s);
        }
    }

    /** 0 = keine Flaeche; sonst Farbe/Licht/Art + 1<<40 als Gueltigkeitsbit. */
    private long faceKey(int x, int y, int z, int dir, int h) {
        int gx = x + 1, gz = z + 1;
        int m = mat[idx(gx, y, gz)];
        if (m == 0) return 0;
        LodMaterials.Material self = LodMaterials.byId(m);
        int nx = gx + DX[dir], ny = y + DY[dir], nz = gz + DZ[dir];
        boolean visible;
        if (ny < 0) {
            return 0; // Weltboden
        } else if (ny >= h) {
            visible = true;
        } else {
            int n = mat[idx(nx, ny, nz)];
            if (n == 0) {
                visible = true;
            } else {
                visible = false;
                // Schuerze gegen Risse an Knotengrenzen (Nachbar evtl. andere Stufe)
                boolean border = nx == 0 || nx == W - 1 || nz == 0 || nz == W - 1;
                if (border && self.kind() != LodMaterials.KIND_WATER && y >= top[gz * W + gx] - (SKIRT - 1)) visible = true;
            }
        }
        if (!visible) return 0;
        if (self.kind() == LodMaterials.KIND_WATER && dir != 2 && ny < h && ny >= 0) {
            // Wasser nur als Oberflaeche und an offenen Seiten (Wasserfaelle) zeigen
            int n = mat[idx(nx, ny, nz)];
            if (n != 0) return 0;
        }
        int face = dir == 2 ? LodMaterials.FACE_TOP : dir == 3 ? LodMaterials.FACE_BOTTOM : LodMaterials.FACE_SIDE;
        int biome = bio[idx(gx, y, gz)] & 0xFF;
        int rgb = LodMaterials.faceColor(self, face, biome);
        if (dir == 2) {
            int c = cov[idx(gx, y, gz)];
            if (c != 0) {
                int cc = LodMaterials.faceColor(LodMaterials.byId(c), LodMaterials.FACE_TOP, biome);
                rgb = mix(rgb, cc);
            }
        }
        int sky;
        if (ny >= h) sky = 15;
        else {
            int cx = Math.max(0, Math.min(W - 1, nx)), cz = Math.max(0, Math.min(W - 1, nz));
            sky = ny > top[cz * W + cx] ? 15 : 3;
        }
        int r7 = (rgb >> 17) & 0x7F, g7 = (rgb >> 9) & 0x7F, b7 = (rgb >> 1) & 0x7F;
        int packed = r7 | (g7 << 7) | (b7 << 14) | (sky << 21) | (self.emission() << 25) | ((self.kind() & 7) << 29);
        return (packed & 0xFFFFFFFFL) | (1L << 40);
    }

    private static int mix(int a, int b) {
        int r = (((a >> 16) & 0xFF) + ((b >> 16) & 0xFF)) >> 1;
        int g = (((a >> 8) & 0xFF) + ((b >> 8) & 0xFF)) >> 1;
        int bl = ((a & 0xFF) + (b & 0xFF)) >> 1;
        return (r << 16) | (g << 8) | bl;
    }

    private void greedy(long[] m, int uCount, int vCount, int vLo, int dir, int s) {
        int axis = dir >> 1;
        for (int v = 0; v < vCount; v++) {
            for (int u = 0; u < uCount; ) {
                long key = m[v * uCount + u];
                if (key == 0) {
                    u++;
                    continue;
                }
                int w = 1;
                while (u + w < uCount && w < 32 && m[v * uCount + u + w] == key) w++;
                int hgt = 1;
                outer:
                while (v + hgt < vCount && hgt < 32) {
                    for (int k = 0; k < w; k++) {
                        if (m[(v + hgt) * uCount + u + k] != key) break outer;
                    }
                    hgt++;
                }
                for (int dv = 0; dv < hgt; dv++) {
                    for (int k = 0; k < w; k++) m[(v + dv) * uCount + u + k] = 0;
                }
                int x, y, z, vv = v + vLo;
                if (axis == 0) { x = s; z = u; y = vv; }
                else if (axis == 1) { y = s; x = u; z = vv; }
                else { z = s; x = u; y = vv; }
                emit(x, y, z, dir, w, hgt, (int) key);
                u += w;
            }
        }
    }

    private void emit(int x, int y, int z, int dir, int su, int sv, int packed) {
        if ((quadCount + 1) * 2 > quads.length) quads = Arrays.copyOf(quads, quads.length * 2);
        quads[quadCount * 2] = (x & 31) | ((z & 31) << 5) | ((y & 511) << 10) | (dir << 19) | ((su - 1) << 22) | ((sv - 1) << 27);
        quads[quadCount * 2 + 1] = packed;
        quadCount++;
    }

    private Mesh buildMeshlets(int level, int nodeX, int nodeZ, int minY, int h, int[] dirCount) {
        int scale = 1 << level;
        float ox = (float) (nodeX * 32) * scale, oz = (float) (nodeZ * 32) * scale, oy = minY;
        int meshlets = 0;
        for (int d = 0; d < 6; d++) meshlets += (dirCount[d] + QUADS_PER_MESHLET - 1) / QUADS_PER_MESHLET;
        int[] mStart = new int[meshlets];
        int[] mCount = new int[meshlets];
        float[] bounds = new float[meshlets * 4];
        float[] planes = new float[meshlets * 4];
        float[] aabb = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        int mi = 0, q = 0;
        for (int d = 0; d < 6; d++) {
            int end = q + dirCount[d];
            for (int start = q; start < end; start += QUADS_PER_MESHLET) {
                int n = Math.min(QUADS_PER_MESHLET, end - start);
                float minX = Float.MAX_VALUE, minYw = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
                float maxX = -Float.MAX_VALUE, maxYw = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
                float cutoff = Float.MAX_VALUE;
                for (int i = start; i < start + n; i++) {
                    int a = quads[i * 2];
                    int px = a & 31, pz = (a >> 5) & 31, py = (a >> 10) & 511;
                    int su = ((a >> 22) & 31) + 1, sv = ((a >>> 27) & 31) + 1;
                    int axis = d >> 1;
                    // Ausdehnung des Quads in Voxeln (Ebene auf der Aussenseite des Voxels)
                    int x0 = px, y0 = py, z0 = pz, x1 = px + 1, y1 = py + 1, z1 = pz + 1;
                    if (axis == 0) { z1 = pz + su; y1 = py + sv; }
                    else if (axis == 1) { x1 = px + su; z1 = pz + sv; }
                    else { x1 = px + su; y1 = py + sv; }
                    float wx0 = ox + x0 * scale, wx1 = ox + x1 * scale;
                    float wy0 = oy + y0 * scale, wy1 = oy + y1 * scale;
                    float wz0 = oz + z0 * scale, wz1 = oz + z1 * scale;
                    minX = Math.min(minX, wx0); maxX = Math.max(maxX, wx1);
                    minYw = Math.min(minYw, wy0); maxYw = Math.max(maxYw, wy1);
                    minZ = Math.min(minZ, wz0); maxZ = Math.max(maxZ, wz1);
                    float plane = switch (d) {
                        case 0 -> wx1;
                        case 1 -> -wx0;
                        case 2 -> wy1;
                        case 3 -> -wy0;
                        case 4 -> wz1;
                        default -> -wz0;
                    };
                    cutoff = Math.min(cutoff, plane);
                }
                mStart[mi] = start;
                mCount[mi] = n;
                float hx = (maxX - minX) * 0.5f, hy = (maxYw - minYw) * 0.5f, hz = (maxZ - minZ) * 0.5f;
                bounds[mi * 4] = minX + hx;
                bounds[mi * 4 + 1] = minYw + hy;
                bounds[mi * 4 + 2] = minZ + hz;
                bounds[mi * 4 + 3] = (float) Math.sqrt(hx * hx + hy * hy + hz * hz) + 0.01f;
                planes[mi * 4] = DX[d];
                planes[mi * 4 + 1] = DY[d];
                planes[mi * 4 + 2] = DZ[d];
                planes[mi * 4 + 3] = cutoff;
                aabb[0] = Math.min(aabb[0], minX); aabb[1] = Math.min(aabb[1], minYw); aabb[2] = Math.min(aabb[2], minZ);
                aabb[3] = Math.max(aabb[3], maxX); aabb[4] = Math.max(aabb[4], maxYw); aabb[5] = Math.max(aabb[5], maxZ);
                mi++;
            }
            q = end;
        }
        return new Mesh(level, nodeX, nodeZ, quadCount, Arrays.copyOf(quads, quadCount * 2), meshlets,
                mStart, mCount, bounds, planes, aabb);
    }
}
