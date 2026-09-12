package simon.vulkanfish.client.lod;

import java.util.Arrays;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/**
 * Baut aus den Bloecken eines Chunks die LOD-Stufen (Worker-Thread, eine Instanz pro Thread).
 *
 * <p>Verkleinerung KONSERVATIV: ein grober Voxel ist belegt, sobald irgendein Block darin
 * ein Voxel-Material hat – duenne Staemme, Zaeune, Bruecken, Ueberhaenge verschwinden nie,
 * sie werden hoechstens dicker (und das nur unterhalb eines Pixels, siehe LodManager).
 * Sichtbar ist von aussen der oberste Block, darum bestimmt er Material und Biom des Voxels.
 */
public final class LodColumnBuilder {
    private short[] mats = new short[0];
    private byte[] biomes = new byte[0];
    private long[] runBuf = new long[4096];
    private int height;

    /** Blockdaten aus Chunk-Sections (live, Bobby oder gespeichert) uebernehmen. */
    public void fillFromSections(LevelChunkSection[] sections, int height) {
        fillFromSections(sections, height, null, null);
    }

    /** remapFrom -> remapTo (z. B. der Platzhalter des Generators wieder als Stein). */
    public void fillFromSections(LevelChunkSection[] sections, int height, BlockState remapFrom, BlockState remapTo) {
        prepare(height);
        int sectionsTall = height >> 4;
        BlockState lastState = null;
        int lastId = 0;
        for (int s = 0; s < sectionsTall && s < sections.length; s++) {
            LevelChunkSection sec = sections[s];
            if (sec == null) continue;
            if (!sec.hasOnlyAir()) {
                var states = sec.getStates();
                for (int y = 0; y < 16; y++) {
                    int base = ((s << 4) + y) << 8;
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            BlockState st = states.get(x, y, z);
                            if (st != lastState) {
                                lastState = st;
                                BlockState m = st == remapFrom ? remapTo : st;
                                lastId = m.isAir() ? 0 : LodMaterials.of(m).id();
                            }
                            mats[base | (z << 4) | x] = (short) lastId;
                        }
                    }
                }
            }
            PalettedContainerRO<Holder<Biome>> bio = sec.getBiomes();
            Holder<Biome> lastBiome = null;
            int lastBiomeId = 0;
            for (int qy = 0; qy < 4; qy++) {
                for (int qz = 0; qz < 4; qz++) {
                    for (int qx = 0; qx < 4; qx++) {
                        Holder<Biome> b = bio.get(qx, qy, qz);
                        if (b != lastBiome) {
                            lastBiome = b;
                            lastBiomeId = LodMaterials.biomeId(b);
                        }
                        biomes[(((s << 2) + qy) << 4) | (qz << 2) | qx] = (byte) lastBiomeId;
                    }
                }
            }
        }
    }

    /** Direkt aus Material-IDs (GPU-Generator): mats[(y*16+z)*16+x], biomes pro Quart. */
    public void fillFromIds(short[] ids, byte[] quartBiomes, int height) {
        prepare(height);
        System.arraycopy(ids, 0, mats, 0, 256 * height);
        System.arraycopy(quartBiomes, 0, biomes, 0, 16 * (height >> 2));
    }

    private void prepare(int h) {
        height = h;
        if (mats.length < 256 * h) {
            mats = new short[256 * h];
            biomes = new byte[16 * (h >> 2)];
        } else {
            Arrays.fill(mats, 0, 256 * h, (short) 0);
            Arrays.fill(biomes, 0, 16 * (h >> 2), (byte) 0);
        }
    }

    public LodColumn build(int chunkX, int chunkZ, int source, int minLevel, int minY) {
        fillEnclosed();
        int[][] colStart = new int[LodColumn.LEVELS][];
        long[][] runs = new long[LodColumn.LEVELS][];
        for (int l = minLevel; l < LodColumn.LEVELS; l++) buildLevel(l, colStart, runs);
        return new LodColumn(chunkX, chunkZ, source, minLevel, minY, height, colStart, runs);
    }

    private int[] queue = new int[0];
    private long[] visited = new long[0];
    private final int[] topOcc = new int[256];

    /**
     * Von aussen unsichtbare Hohlraeume fuellen (eingeschlossene Hoehlen, Aquifere, Lava-Taschen):
     * Flutfuellung durch Luft/Wasser/Lava/Decker, Start an der Oberkante und an den Chunk-Raendern
     * oberhalb der dortigen Oberflaeche. Alles Unerreichte ist von aussen nicht zu sehen und wird
     * zum Gestein darunter – sichtbare Hoehleneingaenge, Ueberhaenge und Boegen bleiben erhalten.
     * Spart den Grossteil der Flaechen und Laeufe (Hoehlenwaende).
     */
    private void fillEnclosed() {
        int cells = 256 * height;
        if (queue.length < cells) {
            queue = new int[cells];
            visited = new long[(cells + 63) >> 6];
        } else {
            java.util.Arrays.fill(visited, 0, (cells + 63) >> 6, 0L);
        }
        for (int c = 0; c < 256; c++) {
            int t = -1;
            for (int y = height - 1; y >= 0; y--) {
                if (!passable(mats[(y << 8) | c])) {
                    t = y;
                    break;
                }
            }
            topOcc[c] = t;
        }
        int head = 0, tail = 0;
        for (int c = 0; c < 256; c++) {
            int x = c & 15, z = c >> 4;
            boolean border = x == 0 || x == 15 || z == 0 || z == 15;
            int yFrom = border ? Math.max(0, topOcc[c] - 2) : height - 1;
            for (int y = Math.max(yFrom, topOcc[c] + 1); y < height; y++) tail = seed((y << 8) | c, tail);
            if (border) for (int y = yFrom; y <= topOcc[c] && y >= 0; y++) tail = seed((y << 8) | c, tail);
        }
        while (head < tail) {
            int i = queue[head++];
            int x = i & 15, z = (i >> 4) & 15, y = i >> 8;
            if (x > 0) tail = seed(i - 1, tail);
            if (x < 15) tail = seed(i + 1, tail);
            if (z > 0) tail = seed(i - 16, tail);
            if (z < 15) tail = seed(i + 16, tail);
            if (y > 0) tail = seed(i - 256, tail);
            if (y < height - 1) tail = seed(i + 256, tail);
        }
        // Unerreichte passierbare Zellen -> Material des Gesteins darunter (sonst darueber)
        for (int c = 0; c < 256; c++) {
            int below = 0;
            for (int y = 0; y < height; y++) {
                int i = (y << 8) | c;
                int id = mats[i];
                if (!passable(id)) {
                    below = id;
                    continue;
                }
                if ((visited[i >> 6] & (1L << i)) != 0 || id == 0 && y > topOcc[c]) continue;
                if (below == 0) {
                    for (int yy = y + 1; yy < height; yy++) {
                        int a = mats[(yy << 8) | c];
                        if (!passable(a)) {
                            below = a;
                            break;
                        }
                    }
                }
                if (below != 0) mats[i] = (short) below;
            }
        }
    }

    private int seed(int i, int tail) {
        if ((visited[i >> 6] & (1L << i)) != 0 || !passable(mats[i])) return tail;
        visited[i >> 6] |= 1L << i;
        queue[tail] = i;
        return tail + 1;
    }

    private static boolean passable(int id) {
        if (id == 0) return true;
        int k = LodMaterials.byId(id).kind();
        return k == LodMaterials.KIND_COVER || k == LodMaterials.KIND_WATER || k == LodMaterials.KIND_LAVA;
    }

    private void buildLevel(int l, int[][] colStartOut, long[][] runsOut) {
        int s = 1 << l;
        int n = 16 >> l;
        int hv = height >> l;
        int[] colStart = new int[n * n + 1];
        int count = 0;
        for (int cz = 0; cz < n; cz++) {
            for (int cx = 0; cx < n; cx++) {
                colStart[cz * n + cx] = count;
                int runStart = count;
                // von oben nach unten: Bodendecker eines reinen Decker-Voxels geht an den Voxel darunter
                int pendingCover = 0;
                int curMat = -1, curBiome = 0, curCover = 0, curTop = -1, curLen = 0;
                for (int vy = hv - 1; vy >= -1; vy--) {
                    int mat = 0, biome = 0, cover = 0, topBlockY = -1;
                    if (vy >= 0) {
                        int y0 = vy << l;
                        int coverY = -1;
                        search:
                        for (int y = y0 + s - 1; y >= y0; y--) {
                            int rowBase = y << 8;
                            for (int z = cx * 0 + cz * s; z < cz * s + s; z++) {
                                int zBase = rowBase | (z << 4);
                                for (int x = cx * s; x < cx * s + s; x++) {
                                    int id = mats[zBase | x];
                                    if (id == 0) continue;
                                    LodMaterials.Material m = LodMaterials.byId(id);
                                    if (m.isVoxel()) {
                                        mat = id;
                                        topBlockY = y;
                                        biome = biomes[((y >> 2) << 4) | ((z >> 2) << 2) | (x >> 2)] & 0xFF;
                                        break search;
                                    } else if (m.kind() == LodMaterials.KIND_COVER && coverY < 0) {
                                        coverY = y;
                                        cover = id;
                                    }
                                }
                            }
                        }
                        if (mat == 0) {
                            if (cover != 0) pendingCover = cover; // traegt nach unten weiter
                        } else {
                            if (cover == 0 || coverY < topBlockY) cover = pendingCover;
                            pendingCover = 0;
                        }
                    }
                    // Lauf fortsetzen oder abschliessen (Laeufe von unten nach oben speichern -> umdrehen)
                    boolean same = mat != 0 && mat == curMat && biome == curBiome && cover == 0 && curLen > 0;
                    if (same) {
                        curLen++;
                        curTop = vy;
                        continue;
                    }
                    if (curMat > 0 && curLen > 0) {
                        if (count >= runBuf.length) runBuf = Arrays.copyOf(runBuf, runBuf.length * 2);
                        runBuf[count++] = LodColumn.pack(curTop, curLen, curMat, curBiome, curCover);
                    }
                    curMat = mat;
                    curBiome = biome;
                    curCover = cover;
                    curTop = vy;
                    curLen = mat != 0 ? 1 : 0;
                }
                // oben->unten gesammelt: auf unten->oben drehen
                for (int i = runStart, j = count - 1; i < j; i++, j--) {
                    long t = runBuf[i];
                    runBuf[i] = runBuf[j];
                    runBuf[j] = t;
                }
            }
        }
        colStart[n * n] = count;
        colStartOut[l] = colStart;
        runsOut[l] = Arrays.copyOf(runBuf, count);
    }
}
