package simon.vulkanfish.client.lod;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import simon.vulkanfish.client.gpu.WorkerPool;

/**
 * Bobby-Cache als LOD-Quelle (optional, keine harte Abhaengigkeit): Bobby speichert jeden je
 * gesehenen Chunk auf Platte. Wir lesen die gespeicherten Chunk-Tags – auch weit ausserhalb der
 * Sichtweite, ohne sie als Fake-Chunks zu laden; sie haben Vorrang vor Spielstand und Generator.
 *
 * <p>Mit Bobby: ueber dessen API (Bobby 5.2.x, Minecraft 26.x):
 * {@code ClientChunkCacheExt.bobby_getFakeChunkManager()} -> {@code getWorlds().loadTag(pos)}
 * (Mehrwelten-Modus) bzw. {@code getStorage().loadTag(pos)}. So liest nur Bobby seine Dateien.
 *
 * <p>Ohne Bobby (entfernt oder aus): der Cache bleibt nutzbar – direkt aus
 * {@code .bobby/<Server>/<Seed-Hash>/<Dimension>/r.X.Z.mca} (Vanilla-Regionformat, nur lesend).
 *
 * <p>Kein Lookup pro Chunk: Beim Weltbeitritt (und danach alle 60 s im Hintergrund) wird die
 * Abdeckung einmalig aus allen Regionskoepfen gelesen (nur 8 KiB pro .mca-Datei, keine Chunkdaten).
 * Danach entscheidet ein O(1)-Set, ob ein Chunk ueberhaupt einen Bobby-Zugriff (API-Future oder
 * Plattenlesung) verursacht – Fehlstellen kosten weder Future noch I/O. Vor dem ersten Scan gilt
 * das alte Verhalten (durchlassen), damit nichts klemmt.
 */
final class BobbySource {
    private static final Logger LOG = LoggerFactory.getLogger("vulkanfish");
    private static final CompletableFuture<Optional<CompoundTag>> EMPTY = CompletableFuture.completedFuture(Optional.empty());

    private boolean api;
    private Method getManager, getStorage, getWorlds, storageLoad, worldsLoad;
    // Direktes Lesen ohne Bobby
    private ClientLevel diskLevel;
    private Path diskDir;
    private RegionFileStorage disk;
    private int diskHits;
    // Einmalig geladene Abdeckung: Chunk-Schluessel aller gecachten Chunks (alle Seed-Ordner).
    // Unveränderlich nach Veroeffentlichung – atomarer Tausch, nebenlaeufige Reads sind sicher.
    private volatile Set<Long> bobbyChunks = Set.of();
    private volatile boolean scanPending;
    private volatile long lastScanMs;
    private volatile int scanGen;
    private final AtomicBoolean scanning = new AtomicBoolean();

    private static long key(ChunkPos p) {
        return ((long) p.x() << 32) | (p.z() & 0xFFFFFFFFL);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    BobbySource() {
        if (!FabricLoader.getInstance().isModLoaded("bobby")) return;
        try {
            Class<?> ext = Class.forName("de.johni0702.minecraft.bobby.ext.ClientChunkCacheExt");
            getManager = ext.getMethod("bobby_getFakeChunkManager");
            Class<?> mgr = Class.forName("de.johni0702.minecraft.bobby.FakeChunkManager");
            getStorage = mgr.getMethod("getStorage");
            getWorlds = mgr.getMethod("getWorlds");
            storageLoad = getStorage.getReturnType().getMethod("loadTag", ChunkPos.class);
            worldsLoad = getWorlds.getReturnType().getMethod("loadTag", ChunkPos.class);
            api = true;
            LOG.info("[vulkanfish] LOD: Bobby-Cache als Quelle aktiv (Vorrang vor Spielstand und Generator)");
        } catch (Throwable t) {
            LOG.warn("[vulkanfish] LOD: Bobby gefunden, aber API abweichend – lese seinen Cache direkt ({})", t.toString());
        }
    }

    @SuppressWarnings("unchecked")
    CompletableFuture<Optional<CompoundTag>> load(ClientLevel level, ChunkPos pos) {
        if (level == null) return EMPTY;
        // Plattenauflösung auch bei aktiver Bobby-API: Scan und Gate gelten für beide Pfade
        if (level != diskLevel) openDisk(level);
        else maybeRescan(level);
        // Gate: gecachter Chunk? Nur dann Bobby anfassen (API-Future oder Platte).
        // Ausnahmen: Scan läuft noch (altes Verhalten), oder keine Cache-Ordner bekannt –
        // dann hält Bobby ggf. reine Live-Daten (API-Pfad wie bisher pro Chunk).
        if (!scanPending && !disks.isEmpty() && !bobbyChunks.contains(key(pos))) return EMPTY;
        if (api) {
            try {
                Object manager = getManager.invoke(level.getChunkSource());
                if (manager != null) {
                    Object worlds = getWorlds.invoke(manager);
                    Object result = worlds != null ? worldsLoad.invoke(worlds, pos) : null;
                    if (result == null) {
                        Object storage = getStorage.invoke(manager);
                        result = storage != null ? storageLoad.invoke(storage, pos) : null;
                    }
                    if (result instanceof CompletableFuture<?> f) {
                        return ((CompletableFuture<Object>) f).thenApply(BobbySource::asTag).exceptionally(t -> Optional.empty());
                    }
                    return CompletableFuture.completedFuture(asTag(result));
                }
                // Bobby geladen, aber in dieser Welt aus (z. B. Einzelspieler ohne viewDistanceOverwrite)
            } catch (Throwable t) {
                api = false;
                LOG.warn("[vulkanfish] LOD: Bobby-Zugriff fehlgeschlagen – lese seinen Cache direkt ({})", t.toString());
            }
        }
        return loadFromDisk(level, pos);
    }

    private static Optional<CompoundTag> asTag(Object o) {
        if (o instanceof Optional<?> opt && opt.isPresent() && opt.get() instanceof CompoundTag tag) return Optional.of(tag);
        return Optional.empty();
    }

    // ---------- Abdeckung: einmal alle laden, dann O(1) pro Chunk ----------

    /** Alle 60 s: neue Cache-Ordner entdecken bzw. Abdeckung auffrischen (Hintergrund). */
    private void maybeRescan(ClientLevel level) {
        if (scanPending) return;
        long now = System.currentTimeMillis();
        if (now - lastScanMs < 60_000) return;
        lastScanMs = now;
        if (disks.isEmpty()) {
            openDisk(level); // Cache-Ordner koennten seither erschienen sein (billig: nur Stats)
            return;
        }
        if (scanning.compareAndSet(false, true)) {
            java.util.List<Path> dirs = java.util.List.copyOf(disks.stream().map(DiskEntry::dir).toList());
            int gen = scanGen;
            WorkerPool.submit(WorkerPool.PRIO_LOD, () -> scanDirs(dirs, gen));
        }
    }

    /** Liest nur Regionskoepfe (8 KiB/Datei): Offset-Tabelle sagt, welche Chunks existieren. */
    private void scanDirs(java.util.List<Path> dirs, int gen) {
        try {
            Set<Long> found = new HashSet<>();
            int regions = 0;
            ByteBuffer buf = ByteBuffer.allocate(8192);
            for (Path dir : dirs) {
                java.util.List<Path> files;
                try (var stream = Files.list(dir)) {
                    files = stream.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("r.") && n.endsWith(".mca");
                    }).toList();
                } catch (Throwable t) {
                    continue;
                }
                for (Path f : files) {
                    int[] rc = regionCoords(f.getFileName().toString());
                    if (rc == null) continue;
                    try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
                        buf.clear();
                        while (buf.hasRemaining()) {
                            if (ch.read(buf) <= 0) break;
                        }
                        if (buf.position() < 4096) continue;
                        buf.flip();
                        int bx = rc[0] * 32, bz = rc[1] * 32;
                        for (int i = 0; i < 1024; i++) {
                            if (buf.getInt() != 0) found.add(key(bx + (i & 31), bz + (i >> 5)));
                        }
                        regions++;
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (gen == scanGen) {
                bobbyChunks = found; // atomarer Tausch: keine Uebergangs-Luecken
                scanPending = false;
                LOG.info("[vulkanfish] LOD: Bobby-Abdeckung geladen: {} Chunks in {} Regionen", found.size(), regions);
            }
        } finally {
            scanning.set(false);
        }
    }

    /** "r.&lt;x&gt;.&lt;z&gt;.mca" -> {x, z}, sonst null. */
    private static int[] regionCoords(String name) {
        if (!name.startsWith("r.") || !name.endsWith(".mca")) return null;
        String[] p = name.split("\\.");
        if (p.length != 4) return null;
        try {
            return new int[]{Integer.parseInt(p[1]), Integer.parseInt(p[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------- ohne Bobby: Cache-Dateien direkt ----------
    // Alle Seed-Ordner des Servers (Bobby legt je Seed einen an): exakter Seed zuerst,
    // dann alle anderen — gecachte Chunks werden immer angezeigt, auch ohne eingestellten Seed.

    private record DiskEntry(Path dir, RegionFileStorage storage) {
    }

    private final java.util.List<DiskEntry> disks = new java.util.ArrayList<>();

    private CompletableFuture<Optional<CompoundTag>> loadFromDisk(ClientLevel level, ChunkPos pos) {
        if (level != diskLevel) openDisk(level);
        if (disks.isEmpty()) return EMPTY;
        String region = "r." + (pos.x() >> 5) + "." + (pos.z() >> 5) + ".mca";
        DiskEntry hit = null;
        for (DiskEntry e : disks) {
            // Nur vorhandene Regionen anfassen: RegionFileStorage legte fehlende sonst leer an
            if (Files.isRegularFile(e.dir().resolve(region))) {
                hit = e;
                break;
            }
        }
        if (hit == null) return EMPTY;
        final DiskEntry use = hit;
        CompletableFuture<Optional<CompoundTag>> out = new CompletableFuture<>();
        WorkerPool.submit(WorkerPool.PRIO_LOD, () -> {
            try {
                CompoundTag tag;
                synchronized (use.storage()) {
                    tag = use.storage().read(pos);
                }
                // Andere Spielversion: Bobby selbst verlangt dann /bobby upgrade – nicht raten
                if (tag != null && tag.getIntOr("DataVersion", -1) != SharedConstants.getCurrentVersion().dataVersion().version()) tag = null;
                if (tag != null && ++diskHits == 1) LOG.info("[vulkanfish] LOD: erster Chunk direkt aus dem Bobby-Cache ({})", use.dir());
                out.complete(Optional.ofNullable(tag));
            } catch (Throwable t) {
                out.complete(Optional.empty());
            }
        });
        return out;
    }

    /** Cache-Ordner wie Bobby ihn anlegt (FakeChunkManager): .bobby/Server/Seed-Hash/Namespace/Pfad. */
    private void openDisk(ClientLevel level) {
        closeDisk();
        diskLevel = level;
        try {
            Minecraft mc = Minecraft.getInstance();
            var server = mc.getCurrentServer();
            String name;
            var sp = mc.getSingleplayerServer();
            if (sp != null && Boolean.getBoolean("vulkanfish.testBobbyDisk")) {
                name = sp.getWorldData().getLevelName(); // Selbsttest: Bobbys Einzelspieler-Ordner
            } else {
                if (server == null || mc.isLocalServer()) return; // Einzelspieler: der Spielstand ist besser
                name = server.isRealm() ? "realms" : server.ip.replace(':', '_');
            }
            Path root = mc.gameDirectory.toPath().resolve(".bobby");
            Path serverDir = root.resolve(name);
            if (!Files.isDirectory(serverDir)) serverDir = root.resolve(escape(name));
            if (!Files.isDirectory(serverDir)) return;
            long seedHash = ((simon.vulkanfish.client.mixin.BiomeManagerAccessor) level.getBiomeManager()).vulkanfish$zoomSeed();
            var id = level.dimension().identifier();
            // Kandidaten: exakter Seed zuerst, dann alle anderen Seed-Ordner (ohne Seed-Einstellung
            // trotzdem alles Gecachte nutzen). Jüngste zuerst, damit aktuelle Welt gewinnt.
            java.util.List<Path> candidates = new java.util.ArrayList<>();
            Path exact = serverDir.resolve(Long.toString(seedHash)).resolve(id.getNamespace()).resolve(id.getPath());
            if (Files.isDirectory(exact)) candidates.add(exact);
            try (var stream = Files.list(serverDir)) {
                java.util.List<Path> seeds = stream.filter(Files::isDirectory).sorted((a, b) -> {
                    try {
                        return Long.compare(Files.getLastModifiedTime(b).toMillis(), Files.getLastModifiedTime(a).toMillis());
                    } catch (Throwable ignored) {
                        return 0;
                    }
                }).toList();
                for (Path seedDir : seeds) {
                    Path dir = seedDir.resolve(id.getNamespace()).resolve(id.getPath());
                    if (Files.isDirectory(dir) && !candidates.contains(dir)) candidates.add(dir);
                }
            } catch (Throwable ignored) {
            }
            for (Path dir : candidates) {
                try {
                    var storage = new RegionFileStorage(new RegionStorageInfo("bobby", level.dimension(), "chunk"), dir, false);
                    disks.add(new DiskEntry(dir, storage));
                } catch (Throwable t) {
                    LOG.warn("[vulkanfish] LOD: Bobby-Cache {} nicht lesbar ({})", dir, t.toString());
                }
            }
            if (!disks.isEmpty()) {
                diskDir = disks.get(0).dir();
                disk = disks.get(0).storage();
                LOG.info("[vulkanfish] LOD: Bobby-Cache ohne Bobby gefunden, lese direkt aus {} Ordner(n): {}", disks.size(), diskDir);
                // Abdeckung einmalig im Hintergrund laden (Header-Scan, keine Chunkdaten)
                bobbyChunks = Set.of();
                scanGen++;
                scanPending = true;
                scanning.set(true);
                lastScanMs = System.currentTimeMillis();
                java.util.List<Path> dirs = java.util.List.copyOf(disks.stream().map(DiskEntry::dir).toList());
                int gen = scanGen;
                WorkerPool.submit(WorkerPool.PRIO_LOD, () -> scanDirs(dirs, gen));
            } else {
                scanPending = false;
            }
        } catch (Throwable t) {
            LOG.warn("[vulkanfish] LOD: Bobby-Cache nicht lesbar ({})", t.toString());
            closeDisk();
        }
    }

    /** Aktuelle Cache-Ordner (je Seed-Dir, exakter zuerst) – fuer Abdeckung und Summary-Cache. */
    java.util.List<Path> snapshotDirs() {
        java.util.List<Path> out = new java.util.ArrayList<>(disks.size());
        for (DiskEntry e : disks) out.add(e.dir());
        return out;
    }

    /** Bobbys Ersatz fuer unzulaessige Ordnernamen (Guava PercentEscaper mit ".-_ " als sicher). */
    private static String escape(String s) {
        StringBuilder b = new StringBuilder();
        for (byte c : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int u = c & 0xFF;
            if ((u >= 'a' && u <= 'z') || (u >= 'A' && u <= 'Z') || (u >= '0' && u <= '9') || ".-_ ".indexOf(u) >= 0) b.append((char) u);
            else b.append('%').append(String.format("%02X", u));
        }
        return b.toString();
    }

    private void closeDisk() {
        scanGen++; // laufende Scans verwerfen
        bobbyChunks = Set.of();
        scanPending = false;
        for (DiskEntry e : disks) {
            try {
                synchronized (e.storage()) {
                    e.storage().close();
                }
            } catch (Throwable ignored) {
            }
        }
        disks.clear();
        if (disk != null) {
            try {
                synchronized (disk) {
                    disk.close();
                }
            } catch (Throwable ignored) {
            }
        }
        disk = null;
        diskDir = null;
    }
}
