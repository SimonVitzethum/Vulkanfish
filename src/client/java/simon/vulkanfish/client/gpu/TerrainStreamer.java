package simon.vulkanfish.client.gpu;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Haelt die GPU-Scene (Cluster/Meshlets/Vertices/Dreiecke) synchron zur Welt.
 *
 * <p>Render-Thread: Sections im Sichtradius scannen, Dirty-Meldungen (Mixin
 * auf Vanillas Section-Invalidierung) einsammeln, Snapshots
 * ({@link RenderSectionRegion}, wie Vanilla) erzeugen und an Worker geben.
 * Worker: {@link SectionMesher}. Render-Thread (Frame-Aufnahme): fertige
 * Meshes allokieren und ueber den Staging-Ring hochladen.
 *
 * <p>Freigaben werden um einen Upload-Durchgang verzoegert: der alte Meshlet-
 * Bereich wird im selben Frame genullt (triCount=0 -> Cull ueberspringt) und
 * erst danach wiederverwendet -> nie zwei Schreiber auf denselben Bereich
 * innerhalb eines Command-Buffers.
 */
public final class TerrainStreamer {
    private static final Logger LOG = LoggerFactory.getLogger("vulkanfish");
    private static final ConcurrentLinkedQueue<Long> DIRTY = new ConcurrentLinkedQueue<>();
    // Nur sammeln, solange ein Streamer die Queue auch leert (sonst waechst sie endlos)
    private static volatile boolean collecting;
    private static final int MAX_JOBS_IN_FLIGHT = 192;   // gemeinsamer Worker-Pool (alle Kerne bis auf zwei)
    private static final int MAX_REGIONS_PER_FRAME = 48;
    private static final int SCAN_INTERVAL_FRAMES = 20;

    private final Long2ObjectOpenHashMap<Section> sections = new Long2ObjectOpenHashMap<>();
    // Nur Sections mit Arbeit (statt jeden Frame alle ~6000 zu durchsuchen)
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet wantMesh = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private long frameCounter;
    // Dieselbe Section hoechstens alle N Frames neu meshen: fasst Dirty-Stuerme
    // (fliessendes Wasser, Farmen, Redstone) zusammen, statt jeden Tick neu zu bauen
    private static final int REMESH_MIN_FRAMES = 3;
    private final ConcurrentLinkedQueue<SectionMesher.MeshResult> done = new ConcurrentLinkedQueue<>();
    private final ArrayDeque<SectionMesher.MeshResult> ready = new ArrayDeque<>();
    // {target, start, count}: toRetire = entfernt, wird im naechsten flush genullt;
    // retiring = genullt/ersetzt, wird zu Beginn des naechsten flush freigegeben
    private final List<int[]> toRetire = new ArrayList<>();
    private final List<int[]> retiring = new ArrayList<>();
    private final AtomicInteger jobsInFlight = new AtomicInteger();
    private final ThreadLocal<SectionMesher> meshers = new ThreadLocal<>();

    private final RangeAllocator vertAlloc;
    private final RangeAllocator triAlloc;
    private final RangeAllocator meshletAlloc;
    private final RangeAllocator clusterAlloc;

    private volatile int generation;
    private volatile BlockStateModelSet modelSet;
    private ClientLevel level;
    private long lastCameraSection = Long.MIN_VALUE;
    private int lastRadius = -1;
    private int framesSinceScan;
    private boolean overflowWarned;
    private boolean overflowed;
    private int evictedSections; // Statistik: fuer Naehere ans LOD abgegeben
    // GPU-Scene voll: die naechsten Sections behalten, fernere zeichnet das LOD (gleiches Licht,
    // gleicher G-Buffer). Verdraengt wird nur, was deutlich weiter weg liegt (kein Hin und Her).
    private static final double EVICT_MARGIN = 1.5; // Chunks
    private static final int RETRY_PER_SCAN = 96;
    private int camSXNow, camSZNow;
    private int farthestAppliedHd2;
    private int framesSinceVisibleSync;
    private boolean errorWarned;
    private long quadTotal;
    private long waterQuadsMeshed; // Statistik: gemeshte Wasser-Quads (kumulativ)
    private long dirtyReceived;
    private long dirtyTracked;
    private int meshedSections;
    private long lastStatsMs;

    private static final class Section {
        int version;
        boolean pending;
        boolean needsMesh = true;
        long lastScheduledFrame = Long.MIN_VALUE / 2;
        int vStart = -1, vCount;
        int tStart = -1, tCount;
        int mStart = -1, mCount;
        int cluster = -1;
        int quads;
        boolean applied;   // aktuelle (evtl. kurz veraltete) Geometrie liegt auf der GPU
        boolean overflow;  // kein Platz in der GPU-Scene (bzw. fuer Naehere verdraengt) -> das LOD zeichnet sie
        int needVerts;     // Vertices beim letzten gescheiterten Versuch (Wiederholung nur, wenn das passt)
        boolean shadowOnly; // nur als Schattenwerfer geladen, von Vanilla (noch) nie als sichtbar gemeldet
    }

    // Schattenwerfer: Vanillas Sichtbarkeitssuche meldet aus einer Hoehle nie die Oberflaeche darueber
    // (Fels verdeckt sie) -> ohne diese Sections faellt die Sonne durch den Fels. Im Radius der
    // Schattenkarte deshalb alle nicht leeren Sections ab knapp unter der Kamera laden.
    static final int SHADOW_RADIUS = (int) Math.ceil(FrameDataCapture.SHADOW_DISTANCE / 16.0) + 1;
    static final int SHADOW_BELOW = 2; // Sections unter der Kamera (Sonne kommt von oben)
    private static final int SHADOW_PRIO = 16 * 1024; // hinter sichtbare Sections derselben Entfernung

    private final NativePassRunner runnerRef;

    // ---- Nahfeld-Abdeckung fuers LOD: je Chunk ein Bit je Section (ab minSectionY), gesetzt wenn
    // das Nahfeld sie vollstaendig zeichnet (Geometrie auf der GPU bzw. Vanilla bei Ueberlauf) oder
    // sie sichtbar und leer ist. Nie gesehene Sections (ausserhalb des Frustums, verdeckt, noch nicht
    // von Vanillas Sichtbarkeitssuche erreicht) bleiben offen: dort zeichnet das LOD weiter.
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap coveredBits = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet airVisible = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private int coverageVersion;

    // ... und je Chunk die gewuenschten (sichtbaren, nicht leeren) Sections
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap trackedBits = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

    private void setCovered(long sectionKey, boolean covered) {
        if (setBit(coveredBits, sectionKey, covered)) coverageVersion++;
    }

    private boolean setBit(it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap map, long sectionKey, boolean on) {
        if (level == null) return false;
        int bit = SectionPos.y(sectionKey) - level.getMinSectionY();
        if (bit < 0 || bit >= 64) return false;
        long ck = ((long) SectionPos.x(sectionKey) << 32) | (SectionPos.z(sectionKey) & 0xFFFFFFFFL);
        long old = map.get(ck);
        long now = on ? old | (1L << bit) : old & ~(1L << bit);
        if (now == old) return false;
        if (now == 0) map.remove(ck);
        else map.put(ck, now);
        return true;
    }

    /** Nahfeld zeichnet alle bisher gesehenen Sections des Chunks (und mindestens eine)? */
    public boolean chunkComplete(int cx, int cz) {
        long ck = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        long covered = coveredBits.get(ck);
        return covered != 0 && (trackedBits.get(ck) & ~covered) == 0;
    }

    /** Vom Nahfeld vollstaendig gezeichnete Sections des Chunks (Bit = Section ueber minSectionY). */
    public long coveredSections(int cx, int cz) {
        return coveredBits.get(((long) cx << 32) | (cz & 0xFFFFFFFFL));
    }

    /** Aendert sich bei jeder Abdeckungsaenderung (LOD baut seine Nahfeld-Maske dann neu). */
    public int coverageVersion() {
        return coverageVersion;
    }

    public TerrainStreamer(NativePassRunner runner) {
        this.runnerRef = runner;
        vertAlloc = new RangeAllocator(runner.maxVerts());
        triAlloc = new RangeAllocator(runner.maxTris());
        meshletAlloc = new RangeAllocator(runner.maxMeshlets());
        clusterAlloc = new RangeAllocator(runner.maxClusters());
        collecting = true;
    }

    /** Aus dem LevelExtractor-Mixin (beliebiger Thread). */
    public static void markDirty(int sx, int sy, int sz) {
        if (collecting) DIRTY.add(SectionPos.asLong(sx, sy, sz));
    }

    // ---------- Render-Thread: Welt -> Jobs ----------

    public void tick(ClientLevel lvl, double camX, double camY, double camZ) {
        Minecraft mc = Minecraft.getInstance();
        BlockStateModelSet currentModels = mc.getModelManager().getBlockStateModelSet();
        if (lvl != level || currentModels != modelSet) {
            // Weltwechsel oder Resource-Reload (neue Atlas-UVs) -> alles neu
            resetAll();
            level = lvl;
            modelSet = currentModels;
        }
        if (level == null) return;

        int radius = mc.options.getEffectiveRenderDistance();
        int camSX = SectionPos.blockToSectionCoord(camX);
        int camSY = SectionPos.blockToSectionCoord(camY);
        int camSZ = SectionPos.blockToSectionCoord(camZ);
        camSXNow = camSX;
        camSZNow = camSZ;
        long camSection = SectionPos.asLong(camSX, camSY, camSZ);

        Long dirty;
        while ((dirty = DIRTY.poll()) != null) {
            dirtyReceived++;
            Section s = sections.get((long) dirty);
            if (s == null) {
                // leere sichtbare Section bekommt Bloecke: bis zum Meshen wieder offen (LOD zeigt sie)
                if (airVisible.remove((long) dirty)) setCovered(dirty, false);
                continue; // noch nie sichtbar -> kommt ueber syncVisible()
            }
            dirtyTracked++;
            s.version++;
            if (s.overflow && !s.applied) continue; // zeichnet das LOD; neu gemesht wird erst, wenn wieder Platz ist
            s.needsMesh = true;
            wantMesh.add((long) dirty);
        }
        if (++framesSinceVisibleSync >= 8) {
            framesSinceVisibleSync = 0;
            syncVisible(mc, camSX, camSZ, radius);
        }

        if (++framesSinceScan >= SCAN_INTERVAL_FRAMES || camSection != lastCameraSection || radius != lastRadius) {
            framesSinceScan = 0;
            lastCameraSection = camSection;
            lastRadius = radius;
            scan(camSX, camSY, camSZ, radius);
            syncShadowCasters(camSX, camSY, camSZ, radius);
        }
        schedule(camSX, camSY, camSZ);
    }

    private static boolean inRadius(long key, int camSX, int camSZ, int radius) {
        int dx = SectionPos.x(key) - camSX;
        int dz = SectionPos.z(key) - camSZ;
        return dx * dx + dz * dz <= (radius + 1) * (radius + 1);
    }

    /**
     * Entladen (ausserhalb Radius / Chunk weg) und Overflow-Retry. Geladen wird
     * NICHT flaechig: nur was Vanilla je als sichtbar erkannt hat (syncVisible),
     * sonst fuellen unsichtbare Hoehlensysteme die GPU-Scene.
     */
    private void scan(int camSX, int camSY, int camSZ, int radius) {
        LongArrayList remove = new LongArrayList();
        for (var e : sections.long2ObjectEntrySet()) {
            long key = e.getLongKey();
            // Reine Schattenwerfer ausserhalb des Schattenradius (plus Rand gegen Hin-und-her) verwerfen
            boolean staleShadow = e.getValue().shadowOnly && !e.getValue().pending
                    && (!inRadius(key, camSX, camSZ, SHADOW_RADIUS + 2) || SectionPos.y(key) < camSY - SHADOW_BELOW - 2);
            if (staleShadow || !inRadius(key, camSX, camSZ, radius)
                    || level.getChunkSource().getChunk(SectionPos.x(key), SectionPos.z(key), ChunkStatus.FULL, false) == null) {
                remove.add(key);
            }
        }
        for (int i = 0; i < remove.size(); i++) {
            Section s = sections.remove(remove.getLong(i));
            setCovered(remove.getLong(i), false);
            setBit(trackedBits, remove.getLong(i), false);
            releaseSection(s);
            runnerRef.rtSectionRemoved(remove.getLong(i));
        }
        var ait = airVisible.iterator();
        while (ait.hasNext()) {
            long key = ait.nextLong();
            if (!inRadius(key, camSX, camSZ, radius)
                    || level.getChunkSource().getChunk(SectionPos.x(key), SectionPos.z(key), ChunkStatus.FULL, false) == null) {
                ait.remove();
                setCovered(key, false);
            }
        }
        retryOverflow(camSX, camSZ);
    }

    private static int hd2(long key, int camSX, int camSZ) {
        int dx = SectionPos.x(key) - camSX, dz = SectionPos.z(key) - camSZ;
        return dx * dx + dz * dz;
    }

    /**
     * Uebergelaufene Sections (zeichnet das LOD) wieder versuchen: die naechsten zuerst, wenn ihr
     * letzter Bedarf in den freien Platz passt oder sie deutlich naeher liegen als die fernste belegte
     * Section (die wird dann beim Hochladen verdraengt, siehe makeRoom).
     */
    private void retryOverflow(int camSX, int camSZ) {
        int far = 0;
        LongArrayList waiting = new LongArrayList();
        for (var e : sections.long2ObjectEntrySet()) {
            Section s = e.getValue();
            if (s.applied && s.mStart >= 0) far = Math.max(far, hd2(e.getLongKey(), camSX, camSZ));
            else if (s.overflow && !s.pending && !s.needsMesh) waiting.add(e.getLongKey());
        }
        farthestAppliedHd2 = far;
        if (waiting.isEmpty()) return;
        waiting.unstableSort((a, b) -> Integer.compare(hd2(a, camSX, camSZ), hd2(b, camSX, camSZ)));
        long free = vertAlloc.capacity() - vertAlloc.used() - vertAlloc.capacity() / 100; // etwas Reserve
        double farDist = Math.sqrt(far);
        int n = 0;
        for (int i = 0; i < waiting.size() && n < RETRY_PER_SCAN; i++) {
            long key = waiting.getLong(i);
            Section s = sections.get(key);
            boolean nearer = Math.sqrt(hd2(key, camSX, camSZ)) + EVICT_MARGIN < farDist;
            boolean fits = s.needVerts <= free;
            if (!nearer && !fits) continue;
            if (fits) free -= s.needVerts;
            s.needsMesh = true;
            wantMesh.add(key);
            n++;
        }
    }

    /** Nicht leere Sections im Schattenradius ab knapp unter der Kamera als Schattenwerfer laden. */
    private void syncShadowCasters(int camSX, int camSY, int camSZ, int radius) {
        if (Boolean.getBoolean("vulkanfish.noShadowCasters")) return; // Messung
        int r = Math.min(SHADOW_RADIUS, radius);
        int minSY = level.getMinSectionY();
        int maxSY = level.getMaxSectionY();
        int sy0 = Math.max(minSY, camSY - SHADOW_BELOW);
        var src = level.getChunkSource();
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dz * dz > r * r) continue;
                int cx = camSX + dx, cz = camSZ + dz;
                LevelChunk chunk = src.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                // wie syncVisible: erst mit geladenen Nachbarn (sonst Kanten gegen "Luft")
                if (src.getChunk(cx + 1, cz, ChunkStatus.FULL, false) == null || src.getChunk(cx - 1, cz, ChunkStatus.FULL, false) == null
                        || src.getChunk(cx, cz + 1, ChunkStatus.FULL, false) == null || src.getChunk(cx, cz - 1, ChunkStatus.FULL, false) == null) continue;
                for (int sy = sy0; sy <= maxSY; sy++) {
                    long key = SectionPos.asLong(cx, sy, cz);
                    if (sections.containsKey(key) || airVisible.contains(key)) continue;
                    if (chunk.getSection(sy - minSY).hasOnlyAir()) {
                        // nichts zu zeichnen: fuers LOD abgedeckt (sonst kommen dessen Schatten-Meshlets ueber
                        // der Oberflaeche durch den Cull); bekommt sie Bloecke, oeffnet der Dirty-Pfad sie wieder
                        airVisible.add(key);
                        setCovered(key, true);
                        continue;
                    }
                    Section s = new Section();
                    s.shadowOnly = true;
                    sections.put(key, s);
                    wantMesh.add(key);
                }
            }
        }
    }

    /** Vanillas aktuelle Sichtbarkeitsliste (Frustum + Occlusion-BFS) -> zu meshende Sections. */
    private void syncVisible(Minecraft mc, int camSX, int camSZ, int radius) {
        var visible = mc.levelRenderer.visibleSections();
        int minSY = level.getMinSectionY();
        int maxSY = level.getMaxSectionY();
        for (int i = 0; i < visible.size(); i++) {
            long key = visible.get(i).getSectionNode();
            Section known = sections.get(key);
            if (known != null && known.shadowOnly) {
                // jetzt auch sichtbar: normale Section (bleibt bis zum Sichtradius, zaehlt fuers LOD)
                known.shadowOnly = false;
                setBit(trackedBits, key, true);
                continue;
            }
            if (known != null || airVisible.contains(key) || !inRadius(key, camSX, camSZ, radius)) continue;
            int sy = SectionPos.y(key);
            if (sy < minSY || sy > maxSY) continue;
            int cx = SectionPos.x(key), cz = SectionPos.z(key);
            LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
            if (chunk == null) continue;
            // Wie Vanilla erst mit geladenen Nachbarn meshen: sonst entstehen an Chunk-Grenzen
            // Wasser-Seitenflaechen/Kanten gegen "Luft" (bis dahin zeigt das LOD die Section)
            var src = level.getChunkSource();
            if (src.getChunk(cx + 1, cz, ChunkStatus.FULL, false) == null || src.getChunk(cx - 1, cz, ChunkStatus.FULL, false) == null
                    || src.getChunk(cx, cz + 1, ChunkStatus.FULL, false) == null || src.getChunk(cx, cz - 1, ChunkStatus.FULL, false) == null) continue;
            LevelChunkSection section = chunk.getSection(sy - minSY);
            if (section.hasOnlyAir()) {
                airVisible.add(key); // nichts zu zeichnen: fuers LOD abgedeckt
                setCovered(key, true);
                continue;
            }
            sections.put(key, new Section());
            setBit(trackedBits, key, true);
            wantMesh.add(key);
        }
    }

    /** Liegt die Opaque-Geometrie dieser Section in der GPU-Scene? (Render-Thread) */
    public boolean covers(long sectionNode) {
        Section s = sections.get(sectionNode);
        return s != null && s.applied;
    }

    private void schedule(int camSX, int camSY, int camSZ) {
        frameCounter++;
        int budget = Math.min(MAX_REGIONS_PER_FRAME, MAX_JOBS_IN_FLIGHT - jobsInFlight.get());
        if (budget <= 0 || wantMesh.isEmpty()) return;
        LongArrayList candidates = new LongArrayList();
        var it = wantMesh.iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            Section s = sections.get(key);
            if (s == null || !s.needsMesh) {
                it.remove(); // entfernt oder erledigt
                continue;
            }
            // Neu sichtbar, aber weiter weg als alles Belegte und kein Platz: gleich dem LOD ueberlassen
            // (spart das Meshen; retryOverflow holt sie, sobald sie naeher sind oder Platz frei wird)
            if (!s.applied && !s.overflow && s.mStart < 0 && sceneNearlyFull()
                    && hd2(key, camSX, camSZ) >= farthestAppliedHd2) {
                it.remove();
                s.needsMesh = false;
                s.overflow = true;
                s.needVerts = (int) Math.min(Integer.MAX_VALUE, vertAlloc.used() / Math.max(1, meshedSections));
                setCovered(key, false);
                overflowed = true;
                continue;
            }
            if (!s.pending && frameCounter - s.lastScheduledFrame >= REMESH_MIN_FRAMES) candidates.add(key);
        }
        if (candidates.isEmpty()) return;
        candidates.unstableSort((a, b) -> Integer.compare(prio(a, camSX, camSY, camSZ), prio(b, camSX, camSY, camSZ)));
        RenderRegionCache cache = new RenderRegionCache();
        int gen = generation;
        BlockStateModelSet models = modelSet;
        for (int i = 0; i < Math.min(budget, candidates.size()); i++) {
            long key = candidates.getLong(i);
            Section s = sections.get(key);
            RenderSectionRegion region = cache.createRegion(level, key);
            int version = s.version;
            s.pending = true;
            s.needsMesh = false;
            s.lastScheduledFrame = frameCounter;
            wantMesh.remove(key);
            jobsInFlight.incrementAndGet();
            WorkerPool.submit(WorkerPool.PRIO_SECTION + prio(key, camSX, camSY, camSZ), () -> {
                try {
                    done.add(mesher(models).mesh(region, key, gen, version));
                } catch (Throwable t) {
                    if (!errorWarned) {
                        errorWarned = true;
                        LOG.warn("[vulkanfish] Meshing von Section {} fehlgeschlagen", SectionPos.of(key), t);
                    }
                    done.add(new SectionMesher.MeshResult(key, gen, version, 0, 0,
                            ByteBuffer.allocate(0), ByteBuffer.allocate(0), new int[0], new int[0],
                            new float[0], new float[0], new byte[0], 0, 0, 0L, new int[0]));
                } finally {
                    jobsInFlight.decrementAndGet();
                }
            });
        }
    }

    private SectionMesher mesher(BlockStateModelSet models) {
        SectionMesher m = meshers.get();
        if (m == null || m.modelSet() != models) {
            Minecraft mc = Minecraft.getInstance();
            m = new SectionMesher(models, mc.getModelManager().getFluidStateModelSet(), mc.getBlockColors(),
                    mc.options.ambientOcclusion().get());
            meshers.set(m);
        }
        return m;
    }

    private boolean sceneNearlyFull() {
        return vertAlloc.used() > vertAlloc.capacity() * 0.97 || meshletAlloc.used() > meshletAlloc.capacity() * 0.97
                || triAlloc.used() > triAlloc.capacity() * 0.97;
    }

    /** Meshing-Reihenfolge: sichtbare Sections vor reinen Schattenwerfern gleicher Entfernung. */
    private int prio(long key, int cx, int cy, int cz) {
        Section s = sections.get(key);
        return dist2(key, cx, cy, cz) + (s != null && s.shadowOnly ? SHADOW_PRIO : 0);
    }

    /**
     * Reihenfolge: erst nach waagerechtem Abstand der Chunk-Saeule, darin nach Hoehe. So werden alle
     * Sections einer Saeule zusammen fertig (sonst Wasser ohne Grund darunter, Chunk halb gezeichnet).
     */
    private static int dist2(long key, int cx, int cy, int cz) {
        int dx = SectionPos.x(key) - cx;
        int dy = SectionPos.y(key) - cy;
        int dz = SectionPos.z(key) - cz;
        return (dx * dx + dz * dz) * 1024 + Math.min(dy * dy, 1023);
    }

    // ---------- Frame-Aufnahme: fertige Meshes -> GPU ----------

    /** Im Render-Thread waehrend der Frame-Aufnahme; staged in den Ring des aktuellen Slots. */
    public void flushUploads(NativePassRunner runner) {
        // Im Vorframe genullte/ersetzte Bereiche sind jetzt frei
        for (int[] f : retiring) {
            allocator(f[0]).free(f[1], f[2]);
        }
        retiring.clear();
        // Entfernte Sections: Meshlets nullen, Bereiche erst im naechsten flush freigeben
        for (int[] f : toRetire) {
            if (f[0] == NativePassRunner.TARGET_MESHLETS) {
                runner.stageFill(f[0], (long) f[1] * SectionMesher.MESHLET_BYTES, (long) f[2] * SectionMesher.MESHLET_BYTES);
            } else if (f[0] == NativePassRunner.TARGET_CLUSTERS) {
                // Cluster-Cull liest meshletCount: freie Slots muessen 0 sein
                runner.stageFill(f[0], (long) f[1] * NativePassRunner.CLUSTER_BYTES, (long) f[2] * NativePassRunner.CLUSTER_BYTES);
            }
            retiring.add(f);
        }
        toRetire.clear();

        SectionMesher.MeshResult r;
        while ((r = done.poll()) != null) ready.add(r);
        while ((r = ready.peek()) != null) {
            Section s = sections.get(r.key());
            if (s == null || r.generation() != generation) {
                ready.poll();
                if (s != null) s.pending = false;
                continue;
            }
            long bytes = (long) r.vertexCount() * SectionMesher.VERTEX_BYTES + r.triCount() * 4L
                    + (long) r.meshletCount() * SectionMesher.MESHLET_BYTES + NativePassRunner.CLUSTER_BYTES;
            if (bytes > runner.stagingFree()) break; // naechster Frame
            if (!apply(runner, r, s)) break; // Platz wird erst im naechsten Frame frei
            ready.poll();
            s.pending = false;
            // Immer uebernehmen (neuer als der GPU-Stand, pro Section nur ein Job in Arbeit);
            // war die Section inzwischen wieder dirty, direkt nachmeshen. Verwerfen wuerde
            // bei Dauer-Aenderungen (fliessendes Wasser, Farmen) nie etwas anzeigen.
            if (r.version() != s.version && !(s.overflow && !s.applied)) {
                s.needsMesh = true;
                wantMesh.add(r.key());
            }
        }
        runner.setMeshletTotal(meshletAlloc.top());
        runner.setClusterTotal(clusterAlloc.top());
        logStats(runner);
    }

    /** @return false: kein Platz, fernere Sections wurden verdraengt -> im naechsten Frame erneut (dann frei) */
    private boolean apply(NativePassRunner runner, SectionMesher.MeshResult r, Section s) {
        if (r.quadCount() == 0) {
            setCovered(r.key(), true); // nichts zu zeichnen: fuers LOD abgedeckt
            releaseRanges(runner, s);
            quadTotal -= s.quads;
            s.quads = 0;
            s.applied = true;
            s.overflow = false;
            zeroCluster(runner, s);
            runner.rtSectionApplied(r.key(), 0, 0, 0, 0L, r.lights());
            return true;
        }

        // Erst neu zuteilen, dann die alte Geometrie freigeben: bleibt kein Platz, zeichnet die Section
        // bis zum naechsten Versuch ihr altes Mesh weiter (kein Flackern)
        int v = vertAlloc.alloc(r.vertexCount());
        int t = v < 0 ? -1 : triAlloc.alloc(r.triCount());
        int m = t < 0 ? -1 : meshletAlloc.alloc(r.meshletCount());
        int cl = s.cluster >= 0 ? s.cluster : m >= 0 ? clusterAlloc.alloc(1) : -1;
        if (v < 0 || t < 0 || m < 0 || cl < 0) {
            if (v >= 0) vertAlloc.free(v, r.vertexCount());
            if (t >= 0) triAlloc.free(t, r.triCount());
            if (m >= 0) meshletAlloc.free(m, r.meshletCount());
            if (cl >= 0 && s.cluster < 0) clusterAlloc.free(cl, 1);
            s.needVerts = r.vertexCount();
            if (makeRoom(runner, r)) return false;
            if (!overflowWarned) {
                overflowWarned = true;
                LOG.warn("[vulkanfish] GPU-Scene voll (verts {}/{}, meshlets {}/{}) – entfernte Sections zeichnet das Fernfeld",
                        vertAlloc.used(), vertAlloc.capacity(), meshletAlloc.used(), meshletAlloc.capacity());
            }
            toLod(runner, s, r.key());
            runner.rtSectionApplied(r.key(), 0, 0, 0, 0L, r.lights()); // Lichter ja, Geometrie nicht
            return true;
        }
        s.cluster = cl;
        setCovered(r.key(), true);
        releaseRanges(runner, s);
        quadTotal -= s.quads;
        s.quads = 0;
        s.overflow = false;
        s.vStart = v;
        s.vCount = r.vertexCount();
        s.tStart = t;
        s.tCount = r.triCount();
        s.mStart = m;
        s.mCount = r.meshletCount();
        s.quads = r.quadCount();
        s.applied = true;
        for (int i = 0; i < r.meshletCount(); i++) {
            if (r.meshletKind()[i] == SectionMesher.KIND_WATER) waterQuadsMeshed += r.meshletQuadCount()[i];
        }
        quadTotal += s.quads;
        meshedSections++;

        runner.stageCopy(NativePassRunner.TARGET_VERTS, (long) v * SectionMesher.VERTEX_BYTES, r.vertices());
        // Raytracing: BLAS aus genau diesem Vertexbereich (massiv + Cutout liegen vorn, v ist Vielfaches von 4)
        runner.rtSectionApplied(r.key(), v / 4, r.solidQuads(), r.cutoutQuads(), r.rtHash(), r.lights());
        runner.stageCopy(NativePassRunner.TARGET_TRIS, (long) t * 4L, r.tris());

        ByteBuffer mb = ByteBuffer.allocate(r.meshletCount() * SectionMesher.MESHLET_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < r.meshletCount(); i++) {
            int o = i * SectionMesher.MESHLET_BYTES;
            int quadStart = r.meshletQuadStart()[i];
            int quadCount = r.meshletQuadCount()[i];
            mb.putInt(o, v + quadStart * 4);          // vertexStart
            mb.putInt(o + 4, quadCount * 4);          // vertexCount
            mb.putInt(o + 8, t + quadStart * 2);      // indexStart (Dreiecke)
            mb.putInt(o + 12, quadCount * 2);         // triCount
            // clusterIdx | Bit 31 Wasser | Bit 30 transluzente Bloecke
            int kindBits = r.meshletKind()[i] == SectionMesher.KIND_WATER ? 0x80000000
                    : r.meshletKind()[i] == SectionMesher.KIND_TRANSLUCENT ? 0x40000000 : 0;
            mb.putInt(o + 16, s.cluster | kindBits);
            mb.putInt(o + 20, SectionPos.sectionToBlockCoord(SectionPos.x(r.key())));  // origin
            mb.putInt(o + 24, SectionPos.sectionToBlockCoord(SectionPos.y(r.key())));
            mb.putInt(o + 28, SectionPos.sectionToBlockCoord(SectionPos.z(r.key())));
            float[] b = r.meshletBounds();
            mb.putFloat(o + 32, b[i * 4]).putFloat(o + 36, b[i * 4 + 1]).putFloat(o + 40, b[i * 4 + 2]);
            mb.putFloat(o + 44, b[i * 4 + 3]);
            float[] p = r.meshletPlanes();
            mb.putFloat(o + 48, p[i * 4]).putFloat(o + 52, p[i * 4 + 1]).putFloat(o + 56, p[i * 4 + 2]);
            mb.putFloat(o + 60, p[i * 4 + 3]);
        }
        runner.stageCopy(NativePassRunner.TARGET_MESHLETS, (long) m * SectionMesher.MESHLET_BYTES, mb);

        // Cluster = Section-AABB
        ByteBuffer cb = ByteBuffer.allocate(NativePassRunner.CLUSTER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        long key = r.key();
        cb.putFloat(0, SectionPos.sectionToBlockCoord(SectionPos.x(key)) + 8f)
                .putFloat(4, SectionPos.sectionToBlockCoord(SectionPos.y(key)) + 8f)
                .putFloat(8, SectionPos.sectionToBlockCoord(SectionPos.z(key)) + 8f)
                .putFloat(12, 0f)                          // lodError
                .putFloat(16, 8f).putFloat(20, 8f).putFloat(24, 8f) // extent
                .putInt(28, m)                             // meshletStart
                .putFloat(32, 0f).putFloat(36, 0f).putFloat(40, 0f) // coneAxis
                .putInt(44, r.meshletCount())
                .putFloat(48, -1f)                         // coneCutoff
                .putInt(52, 0);                            // flags
        runner.stageCopy(NativePassRunner.TARGET_CLUSTERS, (long) s.cluster * NativePassRunner.CLUSTER_BYTES, cb);
        return true;
    }

    /** Section ans LOD abgeben: Geometrie weg, nicht mehr abgedeckt (das Fernfeld zeichnet sie). */
    private void toLod(NativePassRunner runner, Section s, long key) {
        releaseRanges(runner, s);
        quadTotal -= s.quads;
        s.quads = 0;
        s.applied = false;
        s.overflow = true;
        zeroCluster(runner, s);
        setCovered(key, false);
        overflowed = true;
    }

    /**
     * GPU-Scene voll: deutlich fernere belegte Sections (die fernsten zuerst) ans LOD abgeben, bis
     * reichlich Platz fuer diese frei wird (Luecken im Allokator). Ihre Bereiche werden im naechsten
     * Frame frei. @return false, wenn es nichts Ferneres gibt
     */
    private boolean makeRoom(NativePassRunner runner, SectionMesher.MeshResult r) {
        double myDist = Math.sqrt(hd2(r.key(), camSXNow, camSZNow));
        LongArrayList cand = new LongArrayList();
        for (var e : sections.long2ObjectEntrySet()) {
            Section c = e.getValue();
            if (c.applied && c.mStart >= 0 && !c.pending
                    && Math.sqrt(hd2(e.getLongKey(), camSXNow, camSZNow)) > myDist + EVICT_MARGIN) cand.add(e.getLongKey());
        }
        if (cand.isEmpty()) return false;
        cand.unstableSort((a, b) -> Integer.compare(hd2(b, camSXNow, camSZNow), hd2(a, camSXNow, camSZNow)));
        long needV = Math.max(2L * r.vertexCount(), vertAlloc.capacity() / 256);
        long needT = Math.max(2L * r.triCount(), triAlloc.capacity() / 256);
        long needM = Math.max(2L * r.meshletCount(), meshletAlloc.capacity() / 256);
        long gotV = 0, gotT = 0, gotM = 0;
        for (int i = 0; i < cand.size() && (gotV < needV || gotT < needT || gotM < needM); i++) {
            long key = cand.getLong(i);
            Section c = sections.get(key);
            gotV += c.vCount;
            gotT += c.tCount;
            gotM += c.mCount;
            c.needVerts = c.vCount;
            toLod(runner, c, key);
            runner.rtSectionRemoved(key); // BLAS liest den Vertexbereich, der gleich neu vergeben wird
            evictedSections++;
        }
        return true;
    }

    /** Neu gemeshte Section: alte Meshlets JETZT nullen (sonst doppelt/veraltet sichtbar), Bereiche retiren. */
    private void releaseRanges(NativePassRunner runner, Section s) {
        if (s.mStart >= 0) {
            runner.stageFill(NativePassRunner.TARGET_MESHLETS, (long) s.mStart * SectionMesher.MESHLET_BYTES,
                    (long) s.mCount * SectionMesher.MESHLET_BYTES);
            retiring.add(new int[]{NativePassRunner.TARGET_MESHLETS, s.mStart, s.mCount});
            retiring.add(new int[]{NativePassRunner.TARGET_VERTS, s.vStart, s.vCount});
            retiring.add(new int[]{NativePassRunner.TARGET_TRIS, s.tStart, s.tCount});
            meshedSections--;
        }
        s.mStart = s.vStart = s.tStart = -1;
        s.mCount = s.vCount = s.tCount = 0;
    }

    /** Cluster ohne (gueltige) Meshlets: meshletCount = 0, sonst liest der Cull ersetzte Slots. */
    private void zeroCluster(NativePassRunner runner, Section s) {
        if (s.cluster >= 0) {
            runner.stageFill(NativePassRunner.TARGET_CLUSTERS, (long) s.cluster * NativePassRunner.CLUSTER_BYTES,
                    NativePassRunner.CLUSTER_BYTES);
        }
    }

    /** Section entfernt (Render-Thread, ausserhalb der Frame-Aufnahme): Nullen im naechsten flush. */
    private void releaseSection(Section s) {
        if (s == null) return;
        quadTotal -= s.quads;
        s.quads = 0;
        if (s.mStart >= 0) {
            toRetire.add(new int[]{NativePassRunner.TARGET_MESHLETS, s.mStart, s.mCount});
            toRetire.add(new int[]{NativePassRunner.TARGET_VERTS, s.vStart, s.vCount});
            toRetire.add(new int[]{NativePassRunner.TARGET_TRIS, s.tStart, s.tCount});
            meshedSections--;
        }
        if (s.cluster >= 0) toRetire.add(new int[]{NativePassRunner.TARGET_CLUSTERS, s.cluster, 1});
        s.mStart = s.vStart = s.tStart = s.cluster = -1;
    }

    private RangeAllocator allocator(int target) {
        return switch (target) {
            case NativePassRunner.TARGET_VERTS -> vertAlloc;
            case NativePassRunner.TARGET_TRIS -> triAlloc;
            case NativePassRunner.TARGET_MESHLETS -> meshletAlloc;
            default -> clusterAlloc;
        };
    }

    private void resetAll() {
        generation++;
        if (runnerRef != null) runnerRef.rtClear();
        sections.clear();
        wantMesh.clear();
        ready.clear();
        done.clear();
        toRetire.clear();
        retiring.clear();
        vertAlloc.reset();
        triAlloc.reset();
        meshletAlloc.reset();
        clusterAlloc.reset();
        quadTotal = 0;
        meshedSections = 0;
        overflowWarned = false;
        coveredBits.clear();
        trackedBits.clear();
        airVisible.clear();
        coverageVersion++;
        lastCameraSection = Long.MIN_VALUE;
    }

    private void logStats(NativePassRunner runner) {
        long now = System.currentTimeMillis();
        if (now - lastStatsMs < 10_000) return;
        lastStatsMs = now;
        int toLod = 0;
        for (Section s : sections.values()) if (s.overflow && !s.applied) toLod++;
        LOG.info("[vulkanfish] Terrain: {} Sections ({} gemesht, {} in Arbeit, {} ans LOD abgegeben, {} verdraengt), {} Quads, Meshlets {}/{} (GPU sichtbar: {}), Verts {}/{}, {} FPS, Wasser-Quads gemesht {}, dirty {}/{}",
                sections.size(), meshedSections, jobsInFlight.get(), toLod, evictedSections, quadTotal,
                meshletAlloc.used(), meshletAlloc.capacity(), runner.lastVisibleMeshlets(),
                vertAlloc.used(), vertAlloc.capacity(), Minecraft.getInstance().getFps(), waterQuadsMeshed, dirtyTracked, dirtyReceived);
        LOG.info("[vulkanfish] GPU-Zeit/Frame: {} (Schatten-Meshlets {}, Wasser-Meshlets {})", runner.passTimings(),
                runner.lastShadowMeshlets(), runner.lastWaterMeshlets());
    }

    /** Mindestens einmal kein Platz in der GPU-Scene gewesen (ohne LOD meshet dann Vanilla mit). */
    public boolean overflowed() {
        return overflowed;
    }

    public void shutdown() {
        collecting = false;
        DIRTY.clear();
        // Worker-Pool ist geteilt (Daemon-Threads); laufende Jobs verwerfen ihr Ergebnis (generation)
        generation++;
    }
}
