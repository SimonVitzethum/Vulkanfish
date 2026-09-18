package simon.vulkanfish.client.lod;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import simon.vulkanfish.client.gpu.NativePassRunner;
import simon.vulkanfish.client.gpu.RangeAllocator;
import simon.vulkanfish.client.gpu.WorkerPool;
import simon.vulkanfish.client.mixin.ChunkMapInvoker;

/**
 * LOD-Fernfeld (eigene Implementierung): Quadtree aus Saeulen-Knoten (32x32 Voxel, volle
 * Welthoehe) in den Stufen 0..4 um die Kamera bis zur LOD-Distanz.
 *
 * <p>Stufenwahl nach Bildfehler: Stufe l (Voxel = 2^l Bloecke) nur, wo ein Voxel hoechstens
 * {@code pixelError} Pixel gross erscheint -> kein sichtbarer Detailverlust. Stufe 0 beginnt
 * am Rand des Nahfelds (Vanilla-Sichtweite, volle Modelle); die Quads des Fernfelds in vom
 * Nahfeld gezeichneten Chunks verwirft der Mesh-Shader (Abdeckungsmaske).
 *
 * <p>Daten pro Chunk (LodColumn) in fester Rangfolge: geladener Chunk > Bobby-Cache >
 * Spielstand (Einzelspieler) > Generator. Alles Bauen laeuft im {@link WorkerPool}; der
 * Render-Thread waehlt nur aus, verteilt und laedt fertige Meshes hoch.
 */
public final class LodManager {
    private static final Logger LOG = LoggerFactory.getLogger("vulkanfish");
    private static volatile LodManager instance;

    static final int MAX_LEVEL = 4;
    private static final int STATE_NONE = 0;
    private static final int STATE_PENDING = 1;
    private static final int STATE_READY = 2;
    private static final int STATE_NODATA = 3;
    private static final int MAX_BUILDS_IN_FLIGHT = 128;
    private static final int MAX_REQUESTS_PER_FRAME = 4096;
    private static final long REQUEST_BUDGET_NS = 1_000_000L;   // Render-Thread-Zeit pro Frame fuer Anfragen
    private static final long SCHEDULE_BUDGET_NS = 1_000_000L;  // ... und fuer die Knotenpruefung
    private static final int RECHECK_FRAMES = 15;               // wartende Knoten nur alle N Frames pruefen
    private static final int MAX_LIVE_COPIES_PER_FRAME = 24;
    private static final long UPLOAD_BYTES_PER_FRAME = 12L << 20; // LOD-Backfill: PCIe schafft Vielfaches, Framespitze ~0,5 ms
    // GPU-Kapazitaet des Fernfelds (Quads a 8 Byte, Meshlets a 64 Byte)
    public static final int QUAD_BYTES = 8;
    public static final int MAX_QUADS = 32 << 20;
    public static final int MAX_MESHLETS = 1536 << 10;
    public static final int MAX_CLUSTERS = 65535;
    public static final int NEAR_MASK_SIZE = 128; // Chunks je Achse, toroidal

    private volatile int lodChunks;
    private volatile float pixelError;
    private final BobbySource bobby;
    private simon.vulkanfish.client.lod.gen.WorldgenSource worldgen;
    private NativePassRunner runner;

    public void setRunner(NativePassRunner r) {
        runner = r;
    }

    private simon.vulkanfish.client.gpu.TerrainStreamer nearField;
    private int lastCoverageVersion = -1;
    private long lastMaskFrame;

    /** Nahfeld (wer zeichnet welche Chunks): LOD blendet nur wirklich gezeichnete Chunks aus. */
    public void setNearField(simon.vulkanfish.client.gpu.TerrainStreamer streamer) {
        nearField = streamer;
    }

    // ---- LOD komplett auf der GPU ----
    private volatile boolean gpuGen;       // GPU generiert fehlende Chunks (Seed/Datapacks)
    private simon.vulkanfish.client.lod.gen.LodBiomeTable biomeTable;
    private java.util.concurrent.CompletableFuture<simon.vulkanfish.client.lod.gen.LodBiomeTable> biomeTableFuture;
    private final ConcurrentLinkedQueue<NativePassRunner.LodGpuJob> gpuJobs = new ConcurrentLinkedQueue<>();

    private void setupGpuGeneration() {
        var fut = biomeTableFuture;
        biomeTableFuture = null;
        try {
            biomeTable = fut.join();
            var src = worldgen;
            if (src == null || runner == null) return;
            boolean ok = runner.setupGenerator(src.program, minY, height, src.settings.seaLevel())
                    && runner.setupLodGpu(src.program, biomeTable.table,
                    biomeTable.tree != null ? biomeTable.tree : simon.vulkanfish.client.lod.gen.ClimateTree.build(biomeTable.climate, biomeTable.climateBiome),
                    biomeTable.fallbackBiome, LodMaterials.of(src.settings.defaultBlock()).id(),
                    LodMaterials.of(src.settings.defaultFluid()).id(),
                    LodMaterials.of(net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState()).id(), lodGpuClient);
            gpuGen = ok;
            if (ok) {
                // bisher ohne Daten gebliebene Chunks jetzt generierbar -> Knoten neu bauen
                for (Node n : nodes.values()) n.dirty = true;
                requeueAll = true;
                LOG.info("[vulkanfish] LOD: GPU-Generierung aktiv – fehlende Chunks entstehen aus Seed + Worldgen der Welt");
                LOG.info("[vulkanfish] LOD: Vereisungs-Rauschen (Anker + Offset) vs. Vanilla: max. Abweichung {} / {} (bei 0 / 10 Mio.)",
                        simon.vulkanfish.client.lod.gen.FreezeNoise.selfCheck(-17, 23),
                        simon.vulkanfish.client.lod.gen.FreezeNoise.selfCheck(10_000_003, -7_000_011));
            }
        } catch (Throwable t) {
            LOG.warn("[vulkanfish] LOD: GPU-Generierung nicht verfuegbar", t);
        }
    }

    private final NativePassRunner.LodGpuClient lodGpuClient = new NativePassRunner.LodGpuClient() {
        @Override
        public java.util.List<NativePassRunner.LodGpuJob> nextLodJobs(int max) {
            java.util.List<NativePassRunner.LodGpuJob> out = new ArrayList<>();
            NativePassRunner.LodGpuJob j;
            while (out.size() < max && (j = gpuJobs.poll()) != null) {
                Node n = nodes.get(j.nodeKey());
                if (n == null || n.buildSerial != j.serial()) {
                    buildsInFlight.decrementAndGet();
                    if (n != null) {
                        n.building = false;
                        enqueue(n);
                    }
                    continue;
                }
                out.add(j);
            }
            return out;
        }

        @Override
        public java.util.List<NativePassRunner.LodGpuCommit> lodMeshed(java.util.List<NativePassRunner.LodGpuJob> jobs, int[] counts) {
            java.util.List<NativePassRunner.LodGpuCommit> commits = new ArrayList<>();
            for (int i = 0; i < jobs.size(); i++) {
                var job = jobs.get(i);
                buildsInFlight.decrementAndGet();
                Node n = nodes.get(job.nodeKey());
                if (n == null || n.buildSerial != job.serial()) {
                    if (n != null) {
                        n.building = false;
                        enqueue(n);
                    }
                    continue;
                }
                n.building = false;
                enqueue(n); // waehrend des Baus geaendert -> gleich wieder
                int[] dir = new int[7]; // 6 Seiten + Wasseroberflaeche (Zaehler 9)
                int quads = 0, meshlets = 0;
                for (int d = 0; d < 7; d++) {
                    dir[d] = Math.min(counts[i * 10 + (d < 6 ? d : 9)], 24 * 1024);
                    quads += dir[d];
                    meshlets += (dir[d] + 31) / 32;
                }
                if (counts[i * 10 + 6] != 0 && !overflowWarned) {
                    overflowWarned = true;
                    LOG.warn("[vulkanfish] LOD: Knoten {}/{},{} hat mehr Flaechen als der GPU-Zwischenpuffer (Rest fehlt)", n.level, n.nx, n.nz);
                }
                if (n.meshletStart >= 0) {
                    toRetire.add(new int[]{0, n.quadStart, n.quadCount});
                    toRetire.add(new int[]{1, n.meshletStart, n.meshletCount});
                }
                n.quadStart = n.meshletStart = -1;
                n.quadCount = n.meshletCount = 0;
                int yMin = counts[i * 10 + 7], yMax = counts[i * 10 + 8];
                int s = 1 << n.level;
                float x0 = n.nx * 32f * s, z0 = n.nz * 32f * s;
                n.aabb = new float[]{x0 - s, minY + Math.max(0, yMin) * (float) s - s, z0 - s,
                        x0 + 33f * s, minY + (yMax + 2) * (float) s, z0 + 33f * s};
                n.ready = true;
                nodesBuilt++;
                if (quads == 0) {
                    if (n.drawn) clusterHide.add(n.key);
                    drawSetDirty = true;
                    continue;
                }
                int q = quadAlloc.alloc(quads);
                int ms = q < 0 ? -1 : meshletAlloc.alloc(meshlets);
                if (q < 0 || ms < 0) {
                    if (q >= 0) quadAlloc.free(q, quads);
                    if (ms >= 0) meshletAlloc.free(ms, meshlets);
                    n.ready = false;
                    n.dirty = true;
                    deferred.add(n.key);
                    memoryPressure = true; // aufbewahrte Knoten verdraengen
                    continue;
                }
                n.quadStart = q;
                n.quadCount = quads;
                n.meshletStart = ms;
                n.meshletCount = meshlets;
                // Cluster-Index im Meshlet-Kopf ist nur Buchhaltung (der Cull nutzt die Kennbits)
                commits.add(new NativePassRunner.LodGpuCommit(i, q, ms, Math.max(n.cluster, 0), n.level, n.nx, n.nz, dir));
                if (n.drawn) clusterShow.add(n.key);
                drawSetDirty = true;
            }
            return commits;
        }

        @Override
        public void lodCancelled(java.util.List<NativePassRunner.LodGpuJob> jobs) {
            // Batches verworfen (neuer Generator): Zaehler freigeben, Knoten neu einreihen
            for (var job : jobs) {
                buildsInFlight.decrementAndGet();
                Node n = nodes.get(job.nodeKey());
                if (n != null && n.buildSerial == job.serial()) {
                    n.building = false;
                    n.dirty = true;
                    enqueue(n);
                }
            }
        }
    };
    private boolean overflowWarned;
    private boolean drawSetDirty;

    /** Echte Laeufe eines Knotens packen (Worker); fehlende Saeulen generiert die GPU. */
    private NativePassRunner.LodGpuJob packJob(Node n, int serial) {
        int l = n.level, perChunk = 16 >> l;
        int[] heads = new int[34 * 34 * 2];
        it.unimi.dsi.fastutil.ints.IntArrayList runs = new it.unimi.dsi.fastutil.ints.IntArrayList();
        boolean anyReal = false, anyMissing = false;
        for (int gz = 0; gz < 34; gz++) {
            int bz = (n.nz * 32 + gz - 1) << l;
            int cz = Math.floorDiv(bz, 16), lz = (bz & 15) >> l;
            for (int gx = 0; gx < 34; gx++) {
                int bx = (n.nx * 32 + gx - 1) << l;
                int cx = Math.floorDiv(bx, 16), lx = (bx & 15) >> l;
                int c = (gz * 34 + gx) * 2;
                LodColumn col = column(cx, cz);
                if (col == null || col.source == LodColumn.SOURCE_GENERATED || !col.hasLevel(l) || lx >= perChunk || lz >= perChunk) {
                    heads[c] = 0;
                    heads[c + 1] = -1;
                    anyMissing = true;
                    continue;
                }
                anyReal = true;
                long[] r = col.runs(l);
                int from = col.columnFrom(l, lx, lz), to = col.columnTo(l, lx, lz);
                heads[c] = runs.size() / 4;
                heads[c + 1] = to - from;
                for (int k = from; k < to; k++) {
                    long run = r[k];
                    runs.add(LodColumn.runY(run) | (LodColumn.runLen(run) << 16));
                    runs.add(LodColumn.runMat(run) | (LodColumn.runBiome(run) << 16));
                    runs.add(LodColumn.runCover(run));
                    runs.add(0);
                }
            }
        }
        boolean generate = anyMissing && gpuGen;
        return new NativePassRunner.LodGpuJob(n.key, serial, n.level, n.nx, n.nz, generate,
                anyReal ? heads : null, anyReal ? runs.toIntArray() : null);
    }

    // ---- Chunk-Daten (von Workern befuellt) ----
    private static final class ChunkEntry {
        volatile LodColumn column;
        volatile int state;
        volatile int wantLevel = MAX_LEVEL;
        int requestedLevel = Integer.MAX_VALUE;
        // Anfrage lieferte nichts, es gibt aber eine (grobere) Saeule: nicht endlos neu anfragen
        volatile boolean noFinerData;
    }

    private final ConcurrentHashMap<Long, ChunkEntry> chunks = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<long[]> updatedChunks = new ConcurrentLinkedQueue<>(); // {Chunk, geaenderte Stufen}
    // je Chunk der Inhalts-Hash jeder Stufe, mit dem Knoten zuletzt gebaut werden konnten (bleibt auch,
    // wenn die Saeule selbst verworfen wird): erneutes Einlesen gleicher Daten baut nichts neu
    private final ConcurrentHashMap<Long, long[]> publishedHash = new ConcurrentHashMap<>();
    private final LongArrayList requestQueue = new LongArrayList();
    private int requestHead;          // bis hier abgearbeitet (Liste wird nur periodisch sortiert/kompaktiert)
    private int requestSortedSize;
    private final LongOpenHashSet requestSet = new LongOpenHashSet();

    // ---- Knoten (nur Render-Thread) ----
    private static final class Node {
        final long key;
        final int level, nx, nz;
        int quadStart = -1, quadCount, meshletStart = -1, meshletCount, cluster = -1;
        boolean ready;          // Mesh liegt auf der GPU (evtl. veraltet)
        boolean building;
        boolean dirty = true;   // Daten geaendert -> neu bauen
        boolean drawn;          // Cluster aktiv
        float[] aabb;
        long lastUsedFrame;
        int buildSerial;

        Node(long key, int level, int nx, int nz) {
            this.key = key;
            this.level = level;
            this.nx = nx;
            this.nz = nz;
        }
    }

    private record Built(long nodeKey, int serial, LodMesher.Mesh mesh) {
    }

    private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
    private final ConcurrentLinkedQueue<Built> built = new ConcurrentLinkedQueue<>();
    private final AtomicInteger buildsInFlight = new AtomicInteger();
    private final ThreadLocal<LodMesher> meshers = ThreadLocal.withInitial(LodMesher::new);
    private final ThreadLocal<LodColumnBuilder> columnBuilders = ThreadLocal.withInitial(LodColumnBuilder::new);

    // ---- GPU-Bereiche ----
    private final RangeAllocator quadAlloc = new RangeAllocator(MAX_QUADS);
    private final RangeAllocator meshletAlloc = new RangeAllocator(MAX_MESHLETS);
    private final RangeAllocator clusterAlloc = new RangeAllocator(MAX_CLUSTERS);
    private final List<int[]> retiring = new ArrayList<>(); // {0 quads|1 meshlets|2 Cluster, start, count}
    private final List<int[]> toRetire = new ArrayList<>();
    private final LongArrayList clusterShow = new LongArrayList();
    private final LongArrayList clusterHide = new LongArrayList();

    // ---- Auswahl ----
    private final LongArrayList desired = new LongArrayList();
    private long frame;
    private double lastSelX = Double.NaN, lastSelY, lastSelZ;
    private int lastSelNear = -1;
    private float lastPixelAngle;
    private ClientLevel level;
    private int minY, height;
    private int camChunkX, camChunkZ, nearRd;
    // je Chunk (toroidal 128x128) die vom Nahfeld gezeichneten Sections als Bits ab minSectionY
    private final int[] nearMask = new int[NEAR_MASK_SIZE * NEAR_MASK_SIZE];
    // Summentabelle der Nahfeld-Abdeckung um (satCx, satCz), Radius satR: Knoten-Test in O(1)
    private int[] nearSat = new int[1];
    private int satCx, satCz, satR;
    private boolean nearMaskUploaded;
    private final long[] selNs = new long[4]; // Messung: Auswahl, Sortierung, Zeichenmenge, Anzahl
    private boolean nearMaskDirty = true;
    private long lastLogMs;
    private int columnsBuilt, nodesBuilt;
    private final int[] sourceCount = new int[5];

    public LodManager(int lodChunks, float pixelError) {
        this.lodChunks = lodChunks;
        this.pixelError = Math.max(0.25f, pixelError);
        this.bobby = new BobbySource();
        instance = this;
    }

    /** Worldgen-Quelle neu aufsetzen (z. B. neuer Seed): wie ein Weltwechsel beim naechsten Tick. */
    public void reloadWorldgen() {
        level = null;
    }

    /** Fernfeld-Radius aendern (Einstellungen): neue Auswahl, Ueberzaehliges wird verdraengt. */
    public void setDistanceChunks(int chunks) {
        if (chunks == lodChunks) return;
        lodChunks = chunks;
        lastSelX = Double.NaN;
        // 256+ Chunks Radius (ab hier quadratisch mehr Chunks!): Backfill dauert lange, Pools
        // werden knapp – einmalig warnen statt still zu churnen.
        if (chunks > 256) {
            LOG.warn("[vulkanfish] LOD-Distanz {} Chunks (~{} Mio. Chunks Flaeche): experimentell – Backfill braucht viele Minuten, fernes LOD bleibt solange lueckenhaft",
                    chunks, String.format(java.util.Locale.ROOT, "%.1f", Math.PI * chunks * chunks / 1e6));
        }
    }

    /** Erlaubter Bildfehler eines Voxels in Pixeln (kleiner = mehr Detail). */
    public void setPixelError(float err) {
        float e = Math.max(0.25f, err);
        if (e == pixelError) return;
        pixelError = e;
        lastSelX = Double.NaN;
    }

    public static LodManager instance() {
        return instance;
    }

    public float farBlocks() {
        return lodChunks * 16.0f;
    }

    // =====================================================================
    // Render-Thread: pro Frame
    // =====================================================================

    /**
     * @param pixelAngle Winkel eines Pixels (rad) aus Projektion + Fensterhoehe
     */
    public void tick(ClientLevel lvl, double camX, double camY, double camZ, float pixelAngle, int nearRenderDistance) {
        frame++;
        if (lvl != level) {
            resetAll();
            level = lvl;
            if (lvl == null) return;
            minY = lvl.getMinY();
            height = lvl.getHeight();
            worldgen = simon.vulkanfish.client.lod.gen.WorldgenSource.forClientLevel(lvl);
            gpuGen = false;
            biomeTable = null;
            if (runner != null) runner.stopGenerator();
            if (worldgen != null && worldgen.valid && runner != null) {
                final var src = worldgen;
                // Biomtabelle (Vanillas Surface-Rules an Musterchunks) im Hintergrund, dann GPU einrichten
                biomeTableFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> simon.vulkanfish.client.lod.gen.LodBiomeTable.build(src), r -> WorkerPool.submit(0, r));
            } else {
                worldgen = null;
            }
        }
        if (level == null || !(pixelAngle > 0f)) return;
        if (biomeTableFuture != null && biomeTableFuture.isDone()) setupGpuGeneration();
        camChunkX = Math.floorDiv((int) Math.floor(camX), 16);
        camChunkZ = Math.floorDiv((int) Math.floor(camZ), 16);
        if (nearRenderDistance != nearRd) {
            nearRd = nearRenderDistance;
            nearMaskDirty = true;
        }
        if (frame % 10 == 0) nearMaskDirty = true; // geladene Chunks aendern sich laufend
        // Nahfeld hat Sections fertig gemesht oder neue sichtbare wartend: Maske zeitnah nachziehen
        if (nearField != null && nearField.coverageVersion() != lastCoverageVersion && frame - lastMaskFrame >= 2) {
            lastCoverageVersion = nearField.coverageVersion();
            nearMaskDirty = true;
        }
        boolean coverageChanged = false;
        if (nearMaskDirty) {
            nearMaskDirty = false;
            lastMaskFrame = frame;
            coverageChanged = buildNearMask();
            if ((coverageChanged || !nearMaskUploaded) && runner != null) {
                runner.setLodNearMask(nearMask, camChunkX, camChunkZ, Math.floorDiv(minY, 16));
                nearMaskUploaded = true;
            }
        }

        // Fertige Saeulen -> betroffene Knoten neu bauen
        long[] upd;
        while ((upd = updatedChunks.poll()) != null) markNodesDirty(upd[0], (int) upd[1]);

        // Auswahl nur bei Bewegung (4 Bloecke) oder anderer Aufloesung/Sichtweite neu
        double moved = Double.isNaN(lastSelX) ? 1e9 : Math.abs(camX - lastSelX) + Math.abs(camY - lastSelY) + Math.abs(camZ - lastSelZ);
        // (Nahfeld-Abdeckung aendert nur, was die GPU ausblendet – keine neue Auswahl noetig)
        if (moved > 4.0 || nearRd != lastSelNear || Math.abs(pixelAngle - lastPixelAngle) > lastPixelAngle * 0.02f) {
            lastSelX = camX;
            lastSelY = camY;
            lastSelZ = camZ;
            lastSelNear = nearRd;
            lastPixelAngle = pixelAngle;
            long t0 = System.nanoTime();
            select(camX, camY, camZ, pixelAngle);
            long t1 = System.nanoTime();
            final double sx = camX, sz = camZ;
            sortByDistance(desired, sx, sz);
            desiredSet.clear();
            desiredSet.addAll(desired);
            rebuildBuildQueue();
            long t2 = System.nanoTime();
            updateDrawSet();
            long t3 = System.nanoTime();
            selNs[0] += t1 - t0;
            selNs[1] += t2 - t1;
            selNs[2] += t3 - t2;
            selNs[3]++;
        }
        scheduleBuilds(camX, camZ);
        processRequests(camX, camZ);
        if (frame % 300 == 0 || (memoryPressure && frame % 10 == 0)) evict(camX, camZ);
        if (frame % 1200 == 0) prunePublished(camX, camZ);
        sweepChunks(camX, camZ, 2048);
        log();
    }

    /** Math.hypot ist in Java sehr langsam (Ueberlaufschutz); hier genuegt sqrt. */
    private static double len2(double dx, double dz) {
        return Math.sqrt(dx * dx + dz * dz);
    }

    private long[] sortScratch = new long[0];

    /**
     * Nach Entfernung sortieren: Entfernung einmal je Knoten, als float-Bits (nicht negativ ->
     * als Ganzzahl sortierbar) oben, Index unten, dann primitiv sortieren. Der Vergleicher mit
     * zwei Entfernungen je Vergleich kostete bei ~12 000 Knoten 5-13 ms.
     */
    private void sortByDistance(LongArrayList keys, double cx, double cz) {
        sortByDistance(keys, k -> keyDistance(k, cx, cz));
    }

    private void sortByDistance(LongArrayList keys, java.util.function.LongToDoubleFunction dist) {
        int n = keys.size();
        if (sortScratch.length < n) sortScratch = new long[Math.max(n, sortScratch.length * 2)];
        long[] tmp = sortScratch;
        for (int i = 0; i < n; i++) {
            float d = (float) dist.applyAsDouble(keys.getLong(i));
            tmp[i] = ((long) Float.floatToIntBits(Math.max(d, 0f)) << 32) | i;
        }
        java.util.Arrays.sort(tmp, 0, n);
        long[] sorted = new long[n];
        for (int i = 0; i < n; i++) sorted[i] = keys.getLong((int) tmp[i]);
        for (int i = 0; i < n; i++) keys.set(i, sorted[i]);
    }

    /** Stufe, die in dieser Entfernung gezeichnet wird. */
    private int levelAt(double dist, float pixelAngle) {
        int l = MAX_LEVEL;
        while (l > 0 && dist < splitDistance(l, pixelAngle)) l--;
        return l;
    }

    /** Split-Entfernung: naeher als das braucht Stufe l feinere Kinder (Voxel > pixelError Pixel). */
    private double splitDistance(int l, float pixelAngle) {
        return (1 << l) / (pixelAngle * pixelError);
    }

    private void select(double camX, double camY, double camZ, float pixelAngle) {
        desired.clear();
        double far = farBlocks();
        int rootSize = 32 << MAX_LEVEL;
        int r0x = (int) Math.floor((camX - far) / rootSize), r1x = (int) Math.floor((camX + far) / rootSize);
        int r0z = (int) Math.floor((camZ - far) / rootSize), r1z = (int) Math.floor((camZ + far) / rootSize);
        for (int rz = r0z; rz <= r1z; rz++) {
            for (int rx = r0x; rx <= r1x; rx++) {
                visit(MAX_LEVEL, rx, rz, camX, camY, camZ, pixelAngle, far);
            }
        }
    }

    private void visit(int l, int nx, int nz, double camX, double camY, double camZ, float pixelAngle, double far) {
        int size = 32 << l;
        double x0 = (double) nx * size, z0 = (double) nz * size;
        double dx = Math.max(0, Math.max(x0 - camX, camX - (x0 + size)));
        double dz = Math.max(0, Math.max(z0 - camZ, camZ - (z0 + size)));
        double dy = Math.max(0, Math.max(minY - camY, camY - (minY + height)));
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        if (distXZ > far) return;
        // Auch Knoten unter dem Nahfeld bauen: wird ein Chunk frei (Bewegen, noch nicht gemesht),
        // steht das LOD sofort bereit; gezeichnet wird dort nur, was das Nahfeld nicht abdeckt
        double dist = Math.sqrt(distXZ * distXZ + dy * dy);
        if (l > 0 && dist < splitDistance(l, pixelAngle)) {
            for (int cz = 0; cz < 2; cz++)
                for (int cx = 0; cx < 2; cx++)
                    visit(l - 1, nx * 2 + cx, nz * 2 + cz, camX, camY, camZ, pixelAngle, far);
            return;
        }
        desired.add(nodeKey(l, nx, nz));
    }

    /** Alle Chunks des Knotens liegen im gezeichneten Nahfeld. */
    private boolean nodeFullyNear(int l, int nx, int nz) {
        if (nearRd <= 0) return false;
        int chunksPer = 2 << l;
        int c0x = nx * chunksPer, c0z = nz * chunksPer;
        // Knoten groesser als das Nahfeld kann nicht ganz drin liegen
        if (chunksPer > nearRd * 2 + 1) return false;
        return nearCount(c0x, c0z, c0x + chunksPer - 1, c0z + chunksPer - 1) == chunksPer * chunksPer;
    }

    /** Im Sichtradius des Nahfelds und geladen (sonst zeichnet es dort sicher nichts). */
    private boolean inNearView(int cx, int cz) {
        return nearRd > 0 && ChunkTrackingView.isInViewDistance(camChunkX, camChunkZ, nearRd, cx, cz)
                && level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) != null;
    }

    static long nodeKey(int l, int nx, int nz) {
        return ((long) l << 58) | ((nx & 0x1FFFFFFFL) << 29) | (nz & 0x1FFFFFFFL);
    }

    private static int keyLevel(long k) {
        return (int) (k >>> 58);
    }

    private static int keyX(long k) {
        return (int) (((k >>> 29) & 0x1FFFFFFFL) << 3) >> 3; // Vorzeichen aus 29 Bit
    }

    private static int keyZ(long k) {
        return (int) ((k & 0x1FFFFFFFL) << 3) >> 3;
    }

    static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    // ---------- Zeichnen ohne Loecher bei Stufenwechseln ----------

    private final LongOpenHashSet desiredSet = new LongOpenHashSet();
    private LongOpenHashSet drawnSet = new LongOpenHashSet(), drawScratch = new LongOpenHashSet();

    private long lastDrawSetFrame = -100;

    private void updateDrawSet() {
        lastDrawSetFrame = frame;
        LongOpenHashSet draw = drawScratch;
        draw.clear();
        boolean anyFallback = false;
        for (int i = 0; i < desired.size(); i++) {
            long k = desired.getLong(i);
            Node n = nodes.get(k);
            if (n != null && n.ready) {
                draw.add(k);
                continue;
            }
            // Noch nicht fertig: fertige feinere Knoten (vom Heranzoomen) oder ein fertiger Vorfahr
            if (!collectReadyDescendants(keyLevel(k), keyX(k), keyZ(k), draw, 3)) {
                long anc = readyAncestor(keyLevel(k), keyX(k), keyZ(k));
                if (anc != 0) {
                    draw.add(anc);
                    anyFallback = true;
                }
            }
        }
        // Vorfahr gezeichnet -> seine Nachkommen nicht zusaetzlich (sonst Doppelgeometrie)
        // (Ueberlappung entsteht nur durch Ersatz-Vorfahren; gewuenschte Knoten sind disjunkt)
        LongArrayList remove = new LongArrayList();
        if (anyFallback) {
            for (long k : draw) {
                int l = keyLevel(k);
                int nx = keyX(k), nz = keyZ(k);
                for (int a = l + 1; a <= MAX_LEVEL; a++) {
                    nx >>= 1;
                    nz >>= 1;
                    if (draw.contains(nodeKey(a, nx, nz))) {
                        remove.add(k);
                        break;
                    }
                }
            }
        }
        for (int i = 0; i < remove.size(); i++) draw.remove(remove.getLong(i));

        // Nur Aenderungen gegenueber dem letzten Stand (statt ueber alle Knoten)
        for (long k : drawnSet) {
            if (draw.contains(k)) continue;
            Node n = nodes.get(k);
            if (n != null && n.drawn) {
                n.drawn = false;
                n.lastUsedFrame = frame;
                clusterHide.add(k);
            }
        }
        for (long k : draw) {
            Node n = nodes.get(k);
            if (n == null) continue;
            n.lastUsedFrame = frame;
            if (!n.drawn) {
                n.drawn = true;
                clusterShow.add(k);
            }
        }
        drawScratch = drawnSet;
        drawnSet = draw;
    }

    /** Sind alle Teilflaechen durch fertige feinere Knoten abgedeckt? Dann diese zeichnen. */
    private boolean collectReadyDescendants(int l, int nx, int nz, LongOpenHashSet out, int depth) {
        if (l == 0 || depth == 0) return false;
        LongArrayList found = new LongArrayList();
        for (int cz = 0; cz < 2; cz++) {
            for (int cx = 0; cx < 2; cx++) {
                int ccx = nx * 2 + cx, ccz = nz * 2 + cz;
                long ck = nodeKey(l - 1, ccx, ccz);
                Node c = nodes.get(ck);
                if (c != null && c.ready) {
                    found.add(ck);
                } else if (nodeFullyNear(l - 1, ccx, ccz)) {
                    // vom Nahfeld abgedeckt, nichts zu zeichnen
                } else {
                    LongOpenHashSet sub = new LongOpenHashSet();
                    if (!collectReadyDescendants(l - 1, ccx, ccz, sub, depth - 1)) return false;
                    found.addAll(sub);
                }
            }
        }
        out.addAll(found);
        return true;
    }

    private long readyAncestor(int l, int nx, int nz) {
        for (int a = l + 1; a <= MAX_LEVEL; a++) {
            nx >>= 1;
            nz >>= 1;
            Node n = nodes.get(nodeKey(a, nx, nz));
            if (n != null && n.ready) return n.key;
        }
        return 0;
    }

    // ---------- Bauen ----------

    private void scheduleBuilds(double camX, double camZ) {
        if (requeueAll) {
            requeueAll = false;
            rebuildBuildQueue();
        }
        if (frame % RECHECK_FRAMES == 0 && !deferred.isEmpty()) {
            // wartende Knoten (Daten fehlten, kein Platz) wieder pruefen
            buildQueue.addAll(deferred);
            deferred.clear();
            queueUnsorted = true;
        }
        if (queueUnsorted && frame % 30 == 0) {
            // Nachzuegler (Chunk-Aenderungen) nach Entfernung einordnen
            queueUnsorted = false;
            if (buildHead > 0) {
                buildQueue.removeElements(0, Math.min(buildHead, buildQueue.size()));
                buildHead = 0;
            }
            final double sx = camX, sz = camZ;
            sortByDistance(buildQueue, k -> keyDistance(k, sx, sz));
        }
        int budget = MAX_BUILDS_IN_FLIGHT - buildsInFlight.get();
        long t0 = System.nanoTime();
        while (buildHead < buildQueue.size() && budget > 0) {
            if ((buildHead & 15) == 0 && System.nanoTime() - t0 > SCHEDULE_BUDGET_NS) break;
            long k = buildQueue.getLong(buildHead++);
            Node n = nodes.get(k);
            if (n == null || n.building || !n.dirty || !desiredSet.contains(k)) continue;
            boolean gpu = runner != null && runner.lodGpuReady();
            boolean partial = !ensureChunks(n, camX, camZ);
            // Noch nie gezeigt und die GPU kann generieren: sofort bauen (fehlende Saeulen aus der
            // Worldgen), statt Sekunden auf den Spielstand zu warten und so lange den groben
            // Vorfahren zu zeigen. Der Knoten bleibt dirty und wird mit allen Daten nachgebaut.
            if (partial && !(gpu && gpuGen && !n.ready)) {
                deferred.add(k); // Daten kommen asynchron; Ankunft reiht den Knoten wieder ein
                continue;
            }
            n.building = true;
            n.dirty = partial;
            int serial = ++n.buildSerial;
            buildsInFlight.incrementAndGet();
            budget--;
            final Node node = n;
            final int lv = n.level, bx = n.nx, bz = n.nz, my = minY, hh = height;
            if (runner != null && runner.lodGpuReady()) {
                // GPU-Pfad: Worker packt nur die echten Laeufe, GPU generiert + meshet
                WorkerPool.submit(WorkerPool.PRIO_LOD + (long) nodeDistance(node, camX, camZ), () -> {
                    try {
                        gpuJobs.add(packJob(node, serial));
                    } catch (Throwable t) {
                        LOG.warn("[vulkanfish] LOD-Auftrag {} fehlgeschlagen", node.key, t);
                        buildsInFlight.decrementAndGet();
                        // Sonst bleibt building=true fuer immer und die Region ein permanentes
                        // Loch (der CPU-Pfad meldet Fehlschlag ueber built zurueck; hier nachholen)
                        node.building = false;
                        node.dirty = true;
                        enqueue(node);
                    }
                });
                continue;
            }
            WorkerPool.submit(WorkerPool.PRIO_LOD + (long) nodeDistance(node, camX, camZ), () -> {
                try {
                    long tm = System.nanoTime();
                    LodMesher.Mesh mesh = meshers.get().mesh(lv, bx, bz, my, hh, this::column);
                    MESH_TIMES.addAndGet(0, System.nanoTime() - tm);
                    MESH_TIMES.incrementAndGet(1);
                    built.add(new Built(node.key, serial, mesh));
                } catch (Throwable t) {
                    LOG.warn("[vulkanfish] LOD-Knoten {} fehlgeschlagen", node.key, t);
                    built.add(new Built(node.key, serial, null));
                } finally {
                    buildsInFlight.decrementAndGet();
                }
            });
        }
        if (buildHead >= buildQueue.size()) {
            buildQueue.clear();
            buildHead = 0;
        }
    }

    // ---------- Bau-Warteschlange: nur Knoten mit Arbeit, statt jeden Frame alle gewuenschten ----------
    private final LongArrayList buildQueue = new LongArrayList();
    private final LongArrayList deferred = new LongArrayList();
    private int buildHead;
    private boolean requeueAll, queueUnsorted;

    /** Nach der Auswahl: gewuenschte Knoten (nach Entfernung sortiert) mit Arbeit einreihen. */
    private void rebuildBuildQueue() {
        buildQueue.clear();
        buildHead = 0;
        queueUnsorted = false;
        for (int i = 0; i < desired.size(); i++) {
            long k = desired.getLong(i);
            Node n = nodes.get(k);
            if (n == null) {
                n = new Node(k, keyLevel(k), keyX(k), keyZ(k));
                nodes.put(k, n);
            }
            n.lastUsedFrame = frame;
            if (n.dirty && !n.building) buildQueue.add(k);
        }
    }

    private void enqueue(Node n) {
        if (n.dirty && !n.building && desiredSet.contains(n.key)) {
            buildQueue.add(n.key);
            queueUnsorted = true;
        }
    }

    private static double keyDistance(long k, double camX, double camZ) {
        int l = keyLevel(k), size = 32 << l;
        double cx = keyX(k) * (double) size + size * 0.5, cz = keyZ(k) * (double) size + size * 0.5;
        return len2(cx - camX, cz - camZ);
    }

    private static double nodeDistance(Node n, double camX, double camZ) {
        int size = 32 << n.level;
        double cx = n.nx * (double) size + size * 0.5, cz = n.nz * (double) size + size * 0.5;
        return Math.max(0, len2(cx - camX, cz - camZ) - size * 0.7);
    }

    /** Alle Chunks des Knotens (+1 Rand) mit passender Stufe da? Sonst anfordern. */
    private boolean ensureChunks(Node n, double camX, double camZ) {
        int chunksPer = 2 << n.level;
        int c0x = n.nx * chunksPer - 1, c0z = n.nz * chunksPer - 1;
        boolean all = true;
        for (int cz = c0z; cz <= c0z + chunksPer + 1; cz++) {
            for (int cx = c0x; cx <= c0x + chunksPer + 1; cx++) {
                long key = chunkKey(cx, cz);
                ChunkEntry e = chunks.computeIfAbsent(key, k -> new ChunkEntry());
                if (n.level < e.wantLevel) e.wantLevel = n.level;
                int st = e.state;
                if (st == STATE_NODATA) continue;
                LodColumn c = e.column;
                if (st == STATE_READY && c != null && (c.minLevel <= n.level || e.noFinerData)) continue;
                all = false;
                if (st != STATE_PENDING && requestSet.add(key)) requestQueue.add(key);
            }
        }
        return all;
    }

    LodColumn column(int cx, int cz) {
        ChunkEntry e = chunks.get(chunkKey(cx, cz));
        return e != null ? e.column : null;
    }

    /** changedLevels: Bit l = Daten der Stufe l neu; sonst nur wartende Knoten wecken (nichts neu bauen). */
    private void markNodesDirty(long chunk, int changedLevels) {
        int cx = (int) (chunk >> 32), cz = (int) chunk;
        for (int l = 0; l <= MAX_LEVEL; l++) {
            int chunksPer = 2 << l;
            boolean changed = (changedLevels & (1 << l)) != 0;
            // der Chunk selbst und (als Rand) die Nachbarknoten
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = Math.floorDiv(cx + dx, chunksPer), nz = Math.floorDiv(cz + dz, chunksPer);
                    Node n = nodes.get(nodeKey(l, nx, nz));
                    if (n != null) {
                        if (changed) n.dirty = true;
                        enqueue(n);
                    }
                }
            }
        }
    }

    // ---------- Quellen ----------

    private double lastSortX = Double.NaN, lastSortZ = Double.NaN;
    private long lastSortFrame;

    private void processRequests(double camX, double camZ) {
        if (requestQueue.isEmpty()) return;
        // Kompaktieren bei Bedarf, Sortieren nur bei Bewegung/Zuwachs (nicht jeden Frame 200k
        // Eintraege): bei statischer Kamera aendert sich die Entfernungsordnung nicht – mit
        // grossen Bobby-Mengen sparte das sonst alle ~120 Frames einen vollen Re-Sort.
        // Zusaetzlich mindestens 600 Frames zwischen Wachstum-Sorts (Millionen-Queues wuerden
        // sonst sekundenlang freezen – lieber leicht unsortiert weiterarbeiten).
        if (requestHead >= requestQueue.size() || requestQueue.size() > requestSortedSize * 2 + 256 || frame % 120 == 0) {
            if (requestHead > 0) {
                requestQueue.removeElements(0, Math.min(requestHead, requestQueue.size()));
                requestHead = 0;
            }
            if (requestQueue.isEmpty()) return;
            double moved = Double.isNaN(lastSortX) ? Double.POSITIVE_INFINITY
                    : Math.abs(camX - lastSortX) + Math.abs(camZ - lastSortZ);
            boolean grown = requestQueue.size() > requestSortedSize * 2 + 256;
            if (moved > 16.0 || (grown && frame - lastSortFrame >= 600)) {
                final double sx = camX, sz = camZ;
                sortByDistance(requestQueue, k -> chunkDist(k, sx, sz));
                requestSortedSize = requestQueue.size();
                lastSortX = camX;
                lastSortZ = camZ;
                lastSortFrame = frame;
            }
        }
        long t0 = System.nanoTime();
        int liveCopies = 0;
        int done = 0;
        while (requestHead < requestQueue.size() && done < MAX_REQUESTS_PER_FRAME) {
            if ((done & 31) == 0 && System.nanoTime() - t0 > REQUEST_BUDGET_NS) break;
            long key = requestQueue.getLong(requestHead);
            ChunkEntry e = chunks.get(key);
            int cx = (int) (key >> 32), cz = (int) key;
            if (e != null) {
                LevelChunk live = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (live != null) {
                    if (liveCopies >= MAX_LIVE_COPIES_PER_FRAME) break; // naechster Frame
                    liveCopies++;
                    e.state = STATE_PENDING;
                    submitSections(copySections(live), cx, cz, LodColumn.SOURCE_LIVE, e, chunkDist(key, camX, camZ));
                } else if (pendingStored.get() >= MAX_STORED_IN_FLIGHT) {
                    break; // Rueckstau: Parsen hinkt (grosse Bobby-Mengen); Eintrag bleibt an der Spitze
                } else {
                    e.state = STATE_PENDING;
                    pendingStored.incrementAndGet();
                    requestStored(e, cx, cz, chunkDist(key, camX, camZ));
                }
            }
            requestSet.remove(key);
            requestHead++;
            done++;
        }
    }

    private static double chunkDist(long key, double camX, double camZ) {
        int cx = (int) (key >> 32), cz = (int) key;
        return len2(cx * 16 + 8 - camX, cz * 16 + 8 - camZ);
    }

    private static LevelChunkSection[] copySections(LevelChunk chunk) {
        LevelChunkSection[] src = chunk.getSections();
        LevelChunkSection[] out = new LevelChunkSection[src.length];
        for (int i = 0; i < src.length; i++) out[i] = src[i] == null ? null : src[i].copy();
        return out;
    }

    /** Aus dem Client-Chunk-Cache beim Entladen (Render-Thread): echter Stand bleibt als LOD. */
    public void captureLive(LevelChunk chunk) {
        if (level == null || chunk.getLevel() != level) return;
        int cx = chunk.getPos().x(), cz = chunk.getPos().z();
        ChunkEntry e = chunks.computeIfAbsent(chunkKey(cx, cz), k -> new ChunkEntry());
        submitSections(copySections(chunk), cx, cz, LodColumn.SOURCE_LIVE, e, 0);
    }

    /** Bobby-Cache, dann Spielstand (asynchron, Worker), sonst Generator bzw. keine Daten. */
    // Rueckstau-Begrenzung (pendingStored): Spawn (Requests, bis ~12K/s) ist weit schneller als
    // Parsen+Bauen (~600/s). Ohne Cap stuenden sich bei grossen Bobby-Mengen hunderttausende
    // Chunk-Tags (GBs) in der Parse-Warteschlange. Live-Kopien zaehlen nie dazu (Nahfeld
    // bleibt reaktionsschnell); Ueberlauf wartet einen Frame (Eintrag bleibt an der Spitze).
    private final java.util.concurrent.atomic.AtomicInteger pendingStored = new java.util.concurrent.atomic.AtomicInteger();
    private static final int MAX_STORED_IN_FLIGHT = 256;
    private LodSummaryCache summaries; // persistente Bobby-Spalten (lazy je Welt)

    /** Summary-Cache zu den aktuellen Bobby-Ordnern (leer ohne Bobby -> inaktiv). */
    private synchronized LodSummaryCache summaries() {
        if (summaries == null) {
            java.util.Map<String, Path> dirs = new java.util.LinkedHashMap<>();
            for (Path d : bobby.snapshotDirs()) dirs.put(LodSummaryCache.summaryId(d), d);
            summaries = new LodSummaryCache(dirs, Minecraft.getInstance().gameDirectory.toPath());
        }
        return summaries;
    }

    private void requestStored(ChunkEntry e, int cx, int cz, double dist) {
        // 0. Summary-Cache (persistent, ohne NBT/Future/Cap): Treffer -> direkt bauen.
        // Nur wenn fein genug (minLevel <= wantLevel), sonst normaler Pfad (feiner nachladen).
        LodColumn cached = summaries().get(cx, cz);
        if (cached != null && cached.minLevel <= e.wantLevel) {
            final LodColumn c = cached;
            WorkerPool.submit(WorkerPool.PRIO_LOD + (long) dist, () -> publishColumn(cx, cz, c, e, 0, 0));
            return;
        }
        ChunkPos pos = new ChunkPos(cx, cz);
        java.util.concurrent.CompletableFuture<Optional<CompoundTag>> bobbyTag;
        try {
            bobbyTag = bobby.load(level, pos)
                // Haengende Futures (Bobby-/Disk-Hickup) duerfen keinen Chunk ewig blockieren:
                // Timeout feuert exceptionally -> whenComplete laeuft -> Abbau wie gewohnt.
                .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable t) {
            pendingStored.decrementAndGet(); // synchron fehlgeschlagen: nichts ausstehend
            noData(e, cx, cz, dist);
            return;
        }
        bobbyTag.whenComplete((tag, err) -> {
            if (err == null && tag != null && tag.isPresent()) {
                WorkerPool.submit(WorkerPool.PRIO_LOD + (long) dist, () -> parseAndBuild(tag.get(), cx, cz, LodColumn.SOURCE_BOBBY, e, dist, false));
                return;
            }
            requestSaved(e, cx, cz, dist);
        });
    }

    private void requestSaved(ChunkEntry e, int cx, int cz, double dist) {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        ClientLevel lvl = level;
        ServerLevel sl = server != null && lvl != null ? server.getLevel(lvl.dimension()) : null;
        if (sl == null) {
            pendingStored.decrementAndGet(); // ohne Spielstand: Anfrage beendet
            noData(e, cx, cz, dist);
            return;
        }
        try {
            sl.getChunkSource().chunkMap.read(new ChunkPos(cx, cz)).orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .whenComplete((tag, err) -> {
                if (err != null || tag == null || tag.isEmpty()) {
                    pendingStored.decrementAndGet(); // nichts gespeichert: Anfrage beendet
                    noData(e, cx, cz, dist);
                    return;
                }
                WorkerPool.submit(WorkerPool.PRIO_LOD + (long) dist, () -> {
                    CompoundTag t = tag.get();
                    try {
                        t = ((ChunkMapInvoker) sl.getChunkSource().chunkMap).vulkanfish$upgradeChunkTag(t);
                    } catch (Throwable ignored) {
                    }
                    parseAndBuild(t, cx, cz, LodColumn.SOURCE_SAVED, e, dist, true);
                });
            });
        } catch (Throwable t) {
            pendingStored.decrementAndGet(); // Lesefehler: Anfrage beendet
            noData(e);
        }
    }

    private void noData(ChunkEntry e) {
        if (e.column == null) {
            e.state = STATE_NODATA;
        } else {
            e.noFinerData = true; // Knoten bauen mit dem, was da ist (fehlende Stufen generiert die GPU)
            e.state = STATE_READY;
        }
    }

    /**
     * Keine echten Daten fuer diesen Chunk: er gilt als geklaert. Mit GPU-Generierung erzeugt
     * der Knoten die fehlenden Saeulen selbst (aus Seed + Worldgen der Welt), sonst bleibt die Luecke.
     */
    private void noData(ChunkEntry e, int cx, int cz, double dist) {
        noData(e);
    }

    private void parseAndBuild(CompoundTag tag, int cx, int cz, int source, ChunkEntry e, double dist, boolean server) {
        try {
            ClientLevel lvl = level;
            if (lvl == null) return;
            SerializableChunkData data = SerializableChunkData.parse(lvl, PalettedContainerFactory.create(lvl.registryAccess()), tag);
            if (!data.chunkStatus().isOrAfter(ChunkStatus.CARVERS)) { // Rand der Erkundung: Gelaende noch unfertig
                noData(e, cx, cz, dist);
                return;
            }
            LevelChunkSection[] sections = new LevelChunkSection[height >> 4];
            int minSection = minY >> 4;
            for (var sd : data.sectionData()) {
                int idx = sd.y() - minSection;
                if (idx >= 0 && idx < sections.length) sections[idx] = sd.chunkSection();
            }
            buildColumn(sections, cx, cz, source, e);
            if (source == LodColumn.SOURCE_BOBBY && e.column != null && e.column.source == LodColumn.SOURCE_BOBBY) {
                // Persistieren (Worker): naechste Session ohne NBT-Parse. Nur Bobby-Gebautes
                // (Quell-Check: behaltene bessere Spalten, z. B. Live, nie cachen).
                try {
                    summaries().put(cx, cz, e.column, tag.getIntOr("DataVersion", -1));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            noData(e);
        } finally {
            pendingStored.decrementAndGet(); // Anfrage roundtrip-beendet (Erfolg wie alle noData-Wege)
        }
    }

    private void submitSections(LevelChunkSection[] sections, int cx, int cz, int source, ChunkEntry e, double dist) {
        WorkerPool.submit(WorkerPool.PRIO_LOD + (long) dist, () -> {
            try {
                buildColumn(sections, cx, cz, source, e);
            } catch (Throwable t) {
                noData(e);
            }
        });
    }

    static final java.util.concurrent.atomic.AtomicLongArray COLUMN_TIMES = new java.util.concurrent.atomic.AtomicLongArray(4);

    private void buildColumn(LevelChunkSection[] sections, int cx, int cz, int source, ChunkEntry e) {
        LodColumn old = e.column;
        if (old != null && old.source > source && old.minLevel <= e.wantLevel) {
            e.state = STATE_READY; // bessere Quelle schon da
            return;
        }
        LodColumnBuilder b = columnBuilders.get();
        long t0 = System.nanoTime();
        b.fillFromSections(sections, height);
        long t1 = System.nanoTime();
        LodColumn col = b.build(cx, cz, source, Math.min(e.wantLevel, MAX_LEVEL), minY);
        long t2 = System.nanoTime();
        publishColumn(cx, cz, col, e, t1 - t0, t2 - t1);
    }

    /** Gebaute Saeule veroeffentlichen (Bau- wie Cache-Pfad): Vorrang, Statistik, Hash, Dirty. */
    private void publishColumn(int cx, int cz, LodColumn col, ChunkEntry e, long fillNs, long buildNs) {
        LodColumn old = e.column;
        if (old != null && old.source > col.source && old.minLevel <= e.wantLevel) {
            e.state = STATE_READY; // bessere Quelle schon da
            return;
        }
        e.column = col;
        e.noFinerData = false;
        COLUMN_TIMES.addAndGet(0, fillNs);
        COLUMN_TIMES.addAndGet(1, buildNs);
        COLUMN_TIMES.incrementAndGet(2);
        e.state = STATE_READY;
        synchronized (sourceCount) {
            sourceCount[col.source]++;
            columnsBuilt++;
        }
        // Welche Stufen haben sich gegenueber dem letzten Stand geaendert? (fehlende Stufen behalten den alten Hash)
        long key = chunkKey(cx, cz);
        int changed = 0;
        synchronized (publishedHash) {
            long[] prev = publishedHash.get(key);
            long[] now = prev != null ? prev.clone() : new long[LodColumn.LEVELS];
            for (int l = 0; l < LodColumn.LEVELS; l++) {
                long h = col.levelHash(l);
                if (h == 0L) continue;
                if (h != now[l]) changed |= 1 << l;
                now[l] = h;
            }
            publishedHash.put(key, now);
        }
        updatedChunks.add(new long[]{key, changed});
    }

    // ---------- Upload (Frame-Aufnahme) ----------

    /** Fertige Meshes hochladen und Cluster ein-/ausblenden (im Staging-Ring des Frames). */
    public void flushUploads(NativePassRunner runner) {
        for (int[] f : retiring) (f[0] == 0 ? quadAlloc : f[0] == 1 ? meshletAlloc : clusterAlloc).free(f[1], f[2]);
        retiring.clear();
        // Cluster-Slots: bereits beim Freigeben genullt; hier nur einen Durchgang spaeter wiederverwenden
        for (int[] f : toRetire) {
            if (f[0] == 1) runner.stageFill(NativePassRunner.TARGET_LOD_MESHLETS, (long) f[1] * 64, (long) f[2] * 64);
            retiring.add(f);
        }
        toRetire.clear();

        // Rueckstand: Ring voll ausnutzen (die Staging-Wache pro Knoten haelt); ruhig: 12 MiB.
        long budget = deferred.size() + buildQueue.size() > 500 ? Long.MAX_VALUE : UPLOAD_BYTES_PER_FRAME;
        Built b;
        boolean anyReady = false;
        while (budget > 0 && (b = built.peek()) != null) {
            Node n = nodes.get(b.nodeKey());
            if (n == null || b.serial() != n.buildSerial || b.mesh() == null) {
                built.poll();
                if (n != null) {
                    n.building = false;
                    if (b.mesh() == null) n.dirty = true;
                    if (n.dirty) deferred.add(n.key);
                }
                continue;
            }
            long bytes = (long) b.mesh().quadCount() * QUAD_BYTES + (long) b.mesh().meshletCount() * 64 + 64;
            if (bytes > runner.stagingFree() - (1 << 20)) break;
            built.poll();
            n.building = false;
            if (!upload(runner, n, b.mesh())) {
                n.dirty = true; // kein Platz: spaeter erneut
                deferred.add(n.key);
                break;
            }
            enqueue(n);
            budget -= bytes;
            nodesBuilt++;
            anyReady = true;
        }
        // Zeichenmenge hoechstens alle 3 Frames nachziehen (im Flug wird fast jeden Frame ein Knoten fertig)
        if (anyReady) drawSetDirty = true;
        if (drawSetDirty && frame - lastDrawSetFrame >= 3) {
            drawSetDirty = false;
            updateDrawSet();
        }

        // Cluster-Sichtbarkeit
        for (int i = 0; i < clusterHide.size(); i++) {
            Node n = nodes.get(clusterHide.getLong(i));
            if (n != null && (!n.drawn || n.meshletStart < 0)) releaseCluster(runner, n);
        }
        clusterHide.clear();
        for (int i = 0; i < clusterShow.size(); i++) {
            Node n = nodes.get(clusterShow.getLong(i));
            if (n != null && n.drawn) writeCluster(runner, n);
        }
        clusterShow.clear();
        runner.setLodCounts(clusterAlloc.top());
    }

    private boolean upload(NativePassRunner runner, Node n, LodMesher.Mesh m) {
        // alte Geometrie: Meshlets sofort nullen, Bereiche einen Durchgang spaeter freigeben
        if (n.meshletStart >= 0) {
            toRetire.add(new int[]{0, n.quadStart, n.quadCount});
            toRetire.add(new int[]{1, n.meshletStart, n.meshletCount});
        }
        n.quadStart = n.meshletStart = -1;
        n.quadCount = n.meshletCount = 0;
        n.aabb = m.aabb();
        if (m.quadCount() == 0) {
            n.ready = true;
            releaseCluster(runner, n);
            return true;
        }
        int q = quadAlloc.alloc(m.quadCount());
        int ms = q < 0 ? -1 : meshletAlloc.alloc(m.meshletCount());
        if (q < 0 || ms < 0) {
            if (q >= 0) quadAlloc.free(q, m.quadCount());
            if (ms >= 0) meshletAlloc.free(ms, m.meshletCount());
            memoryPressure = true;
            return false;
        }
        n.quadStart = q;
        n.quadCount = m.quadCount();
        n.meshletStart = ms;
        n.meshletCount = m.meshletCount();
        n.ready = true;

        ByteBuffer qb = ByteBuffer.allocate(m.quadCount() * QUAD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        qb.asIntBuffer().put(m.quads(), 0, m.quadCount() * 2);
        runner.stageCopy(NativePassRunner.TARGET_LOD_QUADS, (long) q * QUAD_BYTES, qb);

        int scale = 1 << n.level;
        int ox = n.nx * 32 * scale, oz = n.nz * 32 * scale;
        ByteBuffer mb = ByteBuffer.allocate(m.meshletCount() * 64).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < m.meshletCount(); i++) {
            int o = i * 64;
            mb.putInt(o, q + m.meshletQuadStart()[i]);   // erstes Quad
            mb.putInt(o + 4, m.meshletQuadCount()[i]);   // Quads
            mb.putInt(o + 8, n.level);                   // Stufe
            mb.putInt(o + 12, m.meshletQuadCount()[i] * 2);
            mb.putInt(o + 16, n.cluster | (m.meshletWater()[i] ? 0x80000000 : 0)); // Wasser: Wasser-Pass
            mb.putInt(o + 20, ox).putInt(o + 24, minY).putInt(o + 28, oz);
            float[] bd = m.meshletBounds();
            mb.putFloat(o + 32, bd[i * 4]).putFloat(o + 36, bd[i * 4 + 1]).putFloat(o + 40, bd[i * 4 + 2]).putFloat(o + 44, bd[i * 4 + 3]);
            float[] pl = m.meshletPlanes();
            mb.putFloat(o + 48, pl[i * 4]).putFloat(o + 52, pl[i * 4 + 1]).putFloat(o + 56, pl[i * 4 + 2]).putFloat(o + 60, pl[i * 4 + 3]);
        }
        runner.stageCopy(NativePassRunner.TARGET_LOD_MESHLETS, (long) ms * 64, mb);
        if (n.drawn) writeCluster(runner, n);
        return true;
    }

    private void writeCluster(NativePassRunner runner, Node n) {
        if (n.meshletStart < 0 || n.aabb == null) return;
        if (n.cluster < 0) {
            // Cluster-Slots nur fuer gezeichnete Knoten: der Cull laeuft ueber alle belegten Slots
            n.cluster = clusterAlloc.alloc(1);
            if (n.cluster < 0) {
                warnFull();
                return;
            }
        }
        float[] a = n.aabb;
        ByteBuffer cb = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        cb.putFloat(0, (a[0] + a[3]) * 0.5f).putFloat(4, (a[1] + a[4]) * 0.5f).putFloat(8, (a[2] + a[5]) * 0.5f)
                .putFloat(12, 0f)
                .putFloat(16, (a[3] - a[0]) * 0.5f).putFloat(20, (a[4] - a[1]) * 0.5f).putFloat(24, (a[5] - a[2]) * 0.5f)
                .putInt(28, n.meshletStart)
                .putFloat(32, 0f).putFloat(36, 0f).putFloat(40, 0f)
                .putInt(44, n.meshletCount)
                .putFloat(48, -1f)
                .putInt(52, 2); // Fernfeld
        runner.stageCopy(NativePassRunner.TARGET_LOD_CLUSTERS, (long) n.cluster * 64, cb);
    }

    /** Cluster-Slot nullen (Cull ueberspringt ihn) und im naechsten Durchgang freigeben. */
    private void releaseCluster(NativePassRunner runner, Node n) {
        if (n.cluster < 0) return;
        runner.stageFill(NativePassRunner.TARGET_LOD_CLUSTERS, (long) n.cluster * 64, 64);
        toRetire.add(new int[]{2, n.cluster, 1});
        n.cluster = -1;
    }

    private boolean fullWarned;

    private void warnFull() {
        if (!fullWarned) {
            fullWarned = true;
            LOG.warn("[vulkanfish] LOD-Speicher voll (Quads {}/{}, Meshlets {}/{}) – LOD-Distanz oder -Fehler anpassen",
                    quadAlloc.top(), MAX_QUADS, meshletAlloc.top(), MAX_MESHLETS);
        }
    }

    /**
     * Je Chunk ein Bit pro Section: vom Nahfeld gezeichnet -> Fernfeld dort verwerfen (Mesh-Shader
     * je Quad, Fragment je Pixel). Summentabelle: Chunks, die das Nahfeld komplett zeichnet.
     * @return true, wenn sich die Abdeckung geaendert hat
     */
    private boolean buildNearMask() {
        int[] old = nearMask.clone();
        int oldCx = satCx, oldCz = satCz;
        java.util.Arrays.fill(nearMask, 0);
        satCx = camChunkX;
        satCz = camChunkZ;
        satR = Math.max(0, nearRd + 2);
        int w = satR * 2 + 1;
        if (nearSat.length < (w + 1) * (w + 1)) nearSat = new int[(w + 1) * (w + 1)];
        java.util.Arrays.fill(nearSat, 0);
        if (level != null && nearRd > 0 && nearField != null) {
            for (int z = 0; z < w; z++) {
                int cz = satCz - satR + z, row = 0;
                for (int x = 0; x < w; x++) {
                    int cx = satCx - satR + x;
                    if (inNearView(cx, cz)) {
                        int bits = (int) nearField.coveredSections(cx, cz);
                        if (bits != 0) nearMask[(cz & (NEAR_MASK_SIZE - 1)) * NEAR_MASK_SIZE + (cx & (NEAR_MASK_SIZE - 1))] = bits;
                        if (nearField.chunkComplete(cx, cz)) row++;
                    }
                    nearSat[(z + 1) * (w + 1) + x + 1] = nearSat[z * (w + 1) + x + 1] + row;
                }
            }
        }
        return oldCx != satCx || oldCz != satCz || !java.util.Arrays.equals(old, nearMask);
    }

    /** Anzahl abgedeckter Chunks im Rechteck (Summentabelle, Chunks ausserhalb des Fensters = 0). */
    private int nearCount(int c0x, int c0z, int c1x, int c1z) {
        int w = satR * 2 + 1;
        int x0 = Math.max(c0x - (satCx - satR), 0), z0 = Math.max(c0z - (satCz - satR), 0);
        int x1 = Math.min(c1x - (satCx - satR), w - 1), z1 = Math.min(c1z - (satCz - satR), w - 1);
        if (x0 > x1 || z0 > z1) return 0;
        int W = w + 1;
        return nearSat[(z1 + 1) * W + x1 + 1] - nearSat[z0 * W + x1 + 1] - nearSat[(z1 + 1) * W + x0] + nearSat[z0 * W + x0];
    }

    // ---------- Aufraeumen ----------

    // Aufbewahren statt Neubauen: fertige Knoten ausserhalb der Auswahl bleiben auf der GPU, bis der
    // Speicher knapp wird. Zurueck an einem bekannten Ort ist das Fernfeld dann sofort da (vorher
    // wurde alles nach 600 Frames verworfen und bei der Rueckkehr komplett neu generiert).
    private static final double KEEP_FILL = 0.75;   // bis zu diesem Fuellstand (Quads/Meshlets) aufbewahren
    private static final double EVICT_TO = 0.65;    // ... sonst bis hierher verdraengen
    private static final double PRESSURE_TO = 0.5;  // nach einer gescheiterten Zuteilung
    private static final int MAX_NODES = 300_000;   // CPU-Buchhaltung
    private static final long IDLE_FRAMES = 600;    // so lange unbenutzt, bevor ein Knoten verdraengt werden darf
    private boolean memoryPressure;

    private void evict(double camX, double camZ) {
        boolean pressure = memoryPressure;
        memoryPressure = false;
        LongOpenHashSet wanted = new LongOpenHashSet(desired);
        long quads = 0, meshlets = 0;
        LongArrayList dead = new LongArrayList();
        LongArrayList kept = new LongArrayList();
        for (Node n : nodes.values()) {
            quads += n.quadCount;
            meshlets += n.meshletCount;
            if (n.building || n.drawn || wanted.contains(n.key) || frame - n.lastUsedFrame <= IDLE_FRAMES) continue;
            // nie fertig gewordene Knoten: nichts aufzubewahren (Neuanlage kostet nichts)
            if (!n.ready) dead.add(n.key);
            else kept.add(n.key);
        }
        double fill = Math.max(quads / (double) MAX_QUADS, meshlets / (double) MAX_MESHLETS);
        double target = pressure ? PRESSURE_TO : fill > KEEP_FILL ? EVICT_TO : 1.0;
        int nodeTarget = nodes.size() - dead.size() > MAX_NODES ? MAX_NODES * 9 / 10 : Integer.MAX_VALUE;
        if (target < 1.0 || nodeTarget != Integer.MAX_VALUE) {
            // die am weitesten entfernten zuerst (die braucht man am spaetesten wieder)
            sortByDistance(kept, k -> 1.0 / (1.0 + keyDistance(k, camX, camZ)));
            long qLimit = (long) (target * MAX_QUADS), mLimit = (long) (target * MAX_MESHLETS);
            int alive = nodes.size() - dead.size();
            for (int i = 0; i < kept.size() && (quads > qLimit || meshlets > mLimit || alive > nodeTarget); i++) {
                Node n = nodes.get(kept.getLong(i));
                quads -= n.quadCount;
                meshlets -= n.meshletCount;
                alive--;
                dead.add(n.key);
            }
            if (pressure && (quads > qLimit || meshlets > mLimit)) warnFull(); // alles Verbleibende wird gebraucht
        }
        for (int i = 0; i < dead.size(); i++) {
            Node n = nodes.remove(dead.getLong(i));
            if (n.meshletStart >= 0) {
                toRetire.add(new int[]{0, n.quadStart, n.quadCount});
                toRetire.add(new int[]{1, n.meshletStart, n.meshletCount});
            }
            if (n.cluster >= 0) toRetire.add(new int[]{2, n.cluster, 1}); // nicht gezeichnet: Slot ist genullt
        }
    }

    /** Hashes von Chunks, die kein aufbewahrter Knoten mehr betreffen kann (weit weg), verwerfen. */
    private void prunePublished(double camX, double camZ) {
        // Eng an der Auswahl (Sweep wirft Daten schon ab far+256): Hashes dahinter sind tot
        // (keine Updates mehr moeglich) – bei 1024 Chunks sonst hunderte MB.
        double limit = farBlocks() + 512; // etwas ueber die Auswahl hinaus (Knoten am Rand)
        synchronized (publishedHash) {
            publishedHash.keySet().removeIf(k -> chunkDist(k, camX, camZ) > limit);
        }
    }

    private java.util.Iterator<java.util.Map.Entry<Long, ChunkEntry>> chunkSweep;
    private long sweptBytes, sweptBytesLast;

    /**
     * Chunk-Daten aufraeumen, verteilt ueber die Frames (ein Durchlauf ueber ~200 000 Eintraege
     * am Stueck kostete 15 ms): weit ausserhalb verwerfen, feine Stufen grob gezeichneter
     * Chunks abwerfen (Speicher).
     */
    private void sweepChunks(double camX, double camZ, int budget) {
        double far = farBlocks() + 256;
        if (chunkSweep == null || !chunkSweep.hasNext()) {
            sweptBytesLast = sweptBytes;
            sweptBytes = 0;
            chunkSweep = chunks.entrySet().iterator();
        }
        for (int i = 0; i < budget && chunkSweep.hasNext(); i++) {
            var en = chunkSweep.next();
            ChunkEntry e = en.getValue();
            double dist = chunkDist(en.getKey(), camX, camZ);
            if (dist > far && e.state != STATE_PENDING) {
                chunkSweep.remove();
                continue;
            }
            LodColumn c = e.column;
            if (c == null) continue;
            sweptBytes += c.bytes();
            int need = levelAt(dist - 32, lastPixelAngle);
            if (need > c.minLevel + 1 && reloadable(c)) {
                e.column = c.trimmed(need - 1); // eine Stufe Reserve fuer kleine Bewegungen
                e.wantLevel = need - 1;
            }
        }
    }

    /**
     * Kommen die feinen Stufen nach dem Ausduennen wieder? Spielstand und Bobby ja; ein live
     * uebernommener Chunk nur im Einzelspieler (der Server speichert ihn). Auf einem Server waere
     * er sonst fuer immer grob: jede Anfrage liefe ins Leere und der Knoten wartete endlos.
     */
    private boolean reloadable(LodColumn c) {
        return c.source == LodColumn.SOURCE_SAVED || c.source == LodColumn.SOURCE_BOBBY
                || (c.source == LodColumn.SOURCE_LIVE && Minecraft.getInstance().getSingleplayerServer() != null);
    }

    private void resetAll() {
        memoryPressure = false;
        if (summaries != null) {
            summaries.close();
            summaries = null; // neue Welt/Dimension -> neue Bobby-Ordner (lazy)
        }
        nodes.clear();
        publishedHash.clear();
        updatedChunks.clear();
        chunks.clear();
        chunkSweep = null;
        nearMaskUploaded = false;
        built.clear();
        desired.clear();
        desiredSet.clear();
        drawnSet.clear();
        buildQueue.clear();
        deferred.clear();
        buildHead = 0;
        requestQueue.clear();
        requestHead = 0;
        requestSet.clear();
        retiring.clear();
        toRetire.clear();
        clusterShow.clear();
        clusterHide.clear();
        quadAlloc.reset();
        meshletAlloc.reset();
        clusterAlloc.reset();
        lastSelX = Double.NaN;
        nearMaskDirty = true;
        // Material-IDs bleiben ueber Weltwechsel gleich (Bloecke aendern sich nicht, GPU-Tabelle bleibt gueltig)
        // GPU: Cluster-Zaehler 0 -> nichts mehr sichtbar, bis neu gebaut
    }

    private static final boolean LOG_FAST = Boolean.getBoolean("vulkanfish.lodMove") || Boolean.getBoolean("vulkanfish.lodReturn");

    public void logStats() {
        log();
    }

    private void log() {
        long now = System.currentTimeMillis();
        if (now - lastLogMs < (LOG_FAST ? 2_000 : 10_000)) return;
        lastLogMs = now;
        long bytes = sweptBytesLast; // aus dem verteilten Durchlauf (sweepChunks)
        int ready = 0, drawn = 0, building = 0;
        for (Node n : nodes.values()) {
            if (n.ready) ready++;
            if (n.drawn) drawn++;
            if (n.building) building++;
        }
        // Rueckstand: gewuenschte, noch nicht fertige Knoten (nah = unter 1024 Bloecken)
        int missing = 0, missingNear = 0;
        for (int i = 0; i < desired.size(); i++) {
            long k = desired.getLong(i);
            Node n = nodes.get(k);
            if (n != null && n.ready) continue;
            missing++;
            if (keyDistance(k, lastSelX, lastSelZ) < 1024) missingNear++;
        }
        LOG.info("[vulkanfish] LOD-Rueckstand: {} Knoten offen, davon {} unter 1024 Bloecken; im Bau {} Knoten, {} Auftraege gezaehlt",
                missing, missingNear, building, buildsInFlight.get());
        long cn = Math.max(1, COLUMN_TIMES.get(2));
        LOG.info("[vulkanfish] LOD-CPU je Chunk (ms): Saeule Einlesen {} Bauen {} (n={}); Knoten-Meshing {} (n={})",
                fmt(COLUMN_TIMES.get(0) / 1e6 / cn), fmt(COLUMN_TIMES.get(1) / 1e6 / cn), COLUMN_TIMES.get(2),
                fmt(MESH_TIMES.get(0) / 1e6 / Math.max(1, MESH_TIMES.get(1))), MESH_TIMES.get(1));
        LOG.info("[vulkanfish] LOD: {} Knoten gewuenscht, {} fertig, {} gezeichnet; Quads {}/{} M, Meshlets {}/{} K; "
                        + "{} Chunks ({} MiB), Quellen live/bobby/save/gen {}/{}/{}/{}, Warteschlange {} Anfragen, {} Worker-Jobs",
                desired.size(), ready, drawn, quadAlloc.top() >> 20, MAX_QUADS >> 20, meshletAlloc.top() >> 10, MAX_MESHLETS >> 10,
                chunks.size(), bytes >> 20, sourceCount[LodColumn.SOURCE_LIVE], sourceCount[LodColumn.SOURCE_BOBBY],
                sourceCount[LodColumn.SOURCE_SAVED], sourceCount[LodColumn.SOURCE_GENERATED], requestQueue.size() - requestHead,
                WorkerPool.queued());
        if (selNs[3] > 0) {
            LOG.info("[vulkanfish] LOD-Auswahl ({}x): Auswahl {} ms, Sortierung {} ms, Zeichenmenge {} ms", selNs[3],
                    fmt(selNs[0] / 1e6 / selNs[3]), fmt(selNs[1] / 1e6 / selNs[3]), fmt(selNs[2] / 1e6 / selNs[3]));
            java.util.Arrays.fill(selNs, 0);
        }
    }

    static final java.util.concurrent.atomic.AtomicLongArray MESH_TIMES = new java.util.concurrent.atomic.AtomicLongArray(2);

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    public void shutdown() {
        instance = null;
    }
}
