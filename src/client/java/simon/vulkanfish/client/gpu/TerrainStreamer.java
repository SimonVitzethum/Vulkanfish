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
    private boolean capacityFreed;
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
        boolean overflow;  // GPU-Scene war voll -> Vanilla zeichnet diese Section
    }

    private final NativePassRunner runnerRef;

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
        long camSection = SectionPos.asLong(camSX, camSY, camSZ);

        Long dirty;
        while ((dirty = DIRTY.poll()) != null) {
            dirtyReceived++;
            Section s = sections.get((long) dirty);
            if (s == null) continue; // noch nie sichtbar -> kommt ueber syncVisible()
            dirtyTracked++;
            s.version++;
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
            scan(camSX, camSZ, radius);
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
    private void scan(int camSX, int camSZ, int radius) {
        LongArrayList remove = new LongArrayList();
        for (var e : sections.long2ObjectEntrySet()) {
            long key = e.getLongKey();
            if (!inRadius(key, camSX, camSZ, radius)
                    || level.getChunkSource().getChunk(SectionPos.x(key), SectionPos.z(key), ChunkStatus.FULL, false) == null) {
                remove.add(key);
            }
        }
        for (int i = 0; i < remove.size(); i++) {
            Section s = sections.remove(remove.getLong(i));
            releaseSection(s);
            runnerRef.rtSectionRemoved(remove.getLong(i));
        }
        if (capacityFreed) {
            capacityFreed = false;
            for (var e : sections.long2ObjectEntrySet()) {
                Section s = e.getValue();
                if (s.overflow && !s.pending) {
                    s.overflow = false;
                    s.needsMesh = true;
                    wantMesh.add(e.getLongKey());
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
            if (sections.containsKey(key) || !inRadius(key, camSX, camSZ, radius)) continue;
            int sy = SectionPos.y(key);
            if (sy < minSY || sy > maxSY) continue;
            LevelChunk chunk = level.getChunkSource().getChunk(SectionPos.x(key), SectionPos.z(key), ChunkStatus.FULL, false);
            if (chunk == null) continue;
            LevelChunkSection section = chunk.getSection(sy - minSY);
            if (section.hasOnlyAir()) continue;
            sections.put(key, new Section());
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
            if (!s.pending && frameCounter - s.lastScheduledFrame >= REMESH_MIN_FRAMES) candidates.add(key);
        }
        if (candidates.isEmpty()) return;
        candidates.unstableSort((a, b) -> Integer.compare(dist2(a, camSX, camSY, camSZ), dist2(b, camSX, camSY, camSZ)));
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
            WorkerPool.submit(WorkerPool.PRIO_SECTION + dist2(key, camSX, camSY, camSZ), () -> {
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

    private static int dist2(long key, int cx, int cy, int cz) {
        int dx = SectionPos.x(key) - cx;
        int dy = SectionPos.y(key) - cy;
        int dz = SectionPos.z(key) - cz;
        return dx * dx + dy * dy + dz * dz;
    }

    // ---------- Frame-Aufnahme: fertige Meshes -> GPU ----------

    /** Im Render-Thread waehrend der Frame-Aufnahme; staged in den Ring des aktuellen Slots. */
    public void flushUploads(NativePassRunner runner) {
        // Im Vorframe genullte/ersetzte Bereiche sind jetzt frei
        for (int[] f : retiring) {
            allocator(f[0]).free(f[1], f[2]);
        }
        if (!retiring.isEmpty()) capacityFreed = true;
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
            ready.poll();
            s.pending = false;
            // Immer uebernehmen (neuer als der GPU-Stand, pro Section nur ein Job in Arbeit);
            // war die Section inzwischen wieder dirty, direkt nachmeshen. Verwerfen wuerde
            // bei Dauer-Aenderungen (fliessendes Wasser, Farmen) nie etwas anzeigen.
            if (r.version() != s.version) {
                s.needsMesh = true;
                wantMesh.add(r.key());
            }
            apply(runner, r, s);
        }
        runner.setMeshletTotal(meshletAlloc.top());
        runner.setClusterTotal(clusterAlloc.top());
        logStats(runner);
    }

    private void apply(NativePassRunner runner, SectionMesher.MeshResult r, Section s) {
        releaseRanges(runner, s);
        quadTotal -= s.quads;
        s.quads = 0;
        s.applied = false;
        s.overflow = false;
        if (r.quadCount() == 0) {
            s.applied = true; // nichts zu zeichnen
            zeroCluster(runner, s);
            runner.rtSectionApplied(r.key(), 0, 0, 0, 0L, r.lights());
            return;
        }

        int v = vertAlloc.alloc(r.vertexCount());
        int t = v < 0 ? -1 : triAlloc.alloc(r.triCount());
        int m = t < 0 ? -1 : meshletAlloc.alloc(r.meshletCount());
        if (s.cluster < 0 && m >= 0) s.cluster = clusterAlloc.alloc(1);
        if (v < 0 || t < 0 || m < 0 || s.cluster < 0) {
            if (v >= 0) vertAlloc.free(v, r.vertexCount());
            if (t >= 0) triAlloc.free(t, r.triCount());
            if (m >= 0) meshletAlloc.free(m, r.meshletCount());
            if (!overflowWarned) {
                overflowWarned = true;
                LOG.warn("[vulkanfish] GPU-Scene voll (verts {}/{}, meshlets {}/{}) – Vanilla zeichnet den Rest",
                        vertAlloc.top(), vertAlloc.capacity(), meshletAlloc.top(), meshletAlloc.capacity());
            }
            s.overflow = true;
            overflowed = true;
            zeroCluster(runner, s);
            runner.rtSectionApplied(r.key(), 0, 0, 0, 0L, r.lights()); // Lichter ja, Geometrie nicht
            return;
        }
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
        lastCameraSection = Long.MIN_VALUE;
    }

    private void logStats(NativePassRunner runner) {
        long now = System.currentTimeMillis();
        if (now - lastStatsMs < 10_000) return;
        lastStatsMs = now;
        LOG.info("[vulkanfish] Terrain: {} Sections ({} gemesht, {} in Arbeit), {} Quads, Meshlets {}/{} (GPU sichtbar: {}), Verts {}/{}, {} FPS, Wasser-Quads gemesht {}, dirty {}/{}",
                sections.size(), meshedSections, jobsInFlight.get(), quadTotal,
                meshletAlloc.top(), meshletAlloc.capacity(), runner.lastVisibleMeshlets(),
                vertAlloc.top(), vertAlloc.capacity(), Minecraft.getInstance().getFps(), waterQuadsMeshed, dirtyTracked, dirtyReceived);
        LOG.info("[vulkanfish] GPU-Zeit/Frame: {} (Schatten-Meshlets {}, Wasser-Meshlets {})", runner.passTimings(),
                runner.lastShadowMeshlets(), runner.lastWaterMeshlets());
    }

    /** Mindestens einmal kein Platz in der GPU-Scene gewesen (dann meshet Vanilla mit). */
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
