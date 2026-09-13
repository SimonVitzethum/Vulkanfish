package simon.vulkanfish.client.lod;

/**
 * LOD-Zusammenfassung eines Chunks (16 x Welthoehe x 16) in mehreren Stufen: Stufe l hat
 * Voxel von 2^l Bloecken, also (16>>l)^2 Saeulen mit Hoehe/2^l Voxeln. Jede Saeule ist
 * lauflaengenkodiert (von unten nach oben, Luft wird weggelassen) – Terrain besteht fast
 * nur aus wenigen langen Laeufen, eine ferne Stufe kostet so nur einige hundert Byte.
 *
 * <p>Lauf (long): y 12 Bit | Laenge 12 Bit | Material 16 Bit | Biom 8 Bit | Bodendecker 16 Bit
 * (Bodendecker = Farbe von Gras/Blumen/Teppich auf der Oberseite des obersten Voxels im Lauf).
 * Unveraenderlich nach dem Bau, daher ohne Synchronisation zwischen Threads teilbar.
 */
public final class LodColumn {
    public static final int LEVELS = 5;
    /** Quelle, hoeher = vertrauenswuerdiger (ersetzt niedrigere). */
    public static final int SOURCE_GENERATED = 1;
    public static final int SOURCE_SAVED = 2;
    public static final int SOURCE_BOBBY = 3;
    public static final int SOURCE_LIVE = 4;

    public final int chunkX;
    public final int chunkZ;
    public final int source;
    public final int minLevel;      // feinste enthaltene Stufe (fernere Chunks brauchen keine Stufe 0)
    public final int minY;          // Welt-Y des untersten Blocks
    public final int height;        // Bloecke
    final int[][] colStart;         // pro Stufe: Startindex jeder Saeule in runs (+1 Endeintrag)
    final long[][] runs;

    LodColumn(int chunkX, int chunkZ, int source, int minLevel, int minY, int height, int[][] colStart, long[][] runs) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.source = source;
        this.minLevel = minLevel;
        this.minY = minY;
        this.height = height;
        this.colStart = colStart;
        this.runs = runs;
    }

    public boolean hasLevel(int level) {
        return level >= minLevel && level < LEVELS && colStart[level] != null;
    }

    public static long pack(int y, int len, int mat, int biome, int cover) {
        return (y & 0xFFFL) | ((long) (len & 0xFFF) << 12) | ((long) (mat & 0xFFFF) << 24)
                | ((long) (biome & 0xFF) << 40) | ((long) (cover & 0xFFFF) << 48);
    }

    public static int runY(long r) {
        return (int) (r & 0xFFF);
    }

    public static int runLen(long r) {
        return (int) ((r >>> 12) & 0xFFF);
    }

    public static int runMat(long r) {
        return (int) ((r >>> 24) & 0xFFFF);
    }

    public static int runBiome(long r) {
        return (int) ((r >>> 40) & 0xFF);
    }

    public static int runCover(long r) {
        return (int) ((r >>> 48) & 0xFFFF);
    }

    /** Laeufe der Saeule (lx, lz) auf Stufe level: [from, to) in runs(level). */
    public int columnFrom(int level, int lx, int lz) {
        int n = 16 >> level;
        return colStart[level][lz * n + lx];
    }

    public int columnTo(int level, int lx, int lz) {
        int n = 16 >> level;
        return colStart[level][lz * n + lx + 1];
    }

    public long[] runs(int level) {
        return runs[level];
    }

    /** Kopie ohne die Stufen unterhalb von level (Speicher fuer ferne Chunks). */
    public LodColumn trimmed(int level) {
        if (level <= minLevel) return this;
        int[][] cs = colStart.clone();
        long[][] rs = runs.clone();
        for (int l = 0; l < level && l < LEVELS; l++) {
            cs[l] = null;
            rs[l] = null;
        }
        return new LodColumn(chunkX, chunkZ, source, Math.min(level, LEVELS - 1), minY, height, cs, rs);
    }

    /** Inhalts-Hash einer Stufe (0 = Stufe fehlt): gleiche Daten erneut geladen -> Knoten nicht neu bauen. */
    public long levelHash(int level) {
        if (!hasLevel(level)) return 0L;
        long h = 0x9E3779B97F4A7C15L ^ source;
        for (long r : runs[level]) h = (h ^ r) * 0x100000001B3L + (h >>> 29);
        for (int c : colStart[level]) h = (h ^ c) * 0x100000001B3L + (h >>> 29);
        return h == 0L ? 1L : h;
    }

    /** Ungefaehrer Speicherbedarf (Statistik). */
    public long bytes() {
        long b = 64;
        for (int l = 0; l < LEVELS; l++) {
            if (runs[l] != null) b += runs[l].length * 8L + colStart[l].length * 4L;
        }
        return b;
    }
}
