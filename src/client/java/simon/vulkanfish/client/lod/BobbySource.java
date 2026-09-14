package simon.vulkanfish.client.lod;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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

    // ---------- ohne Bobby: Cache-Dateien direkt ----------

    private CompletableFuture<Optional<CompoundTag>> loadFromDisk(ClientLevel level, ChunkPos pos) {
        if (level != diskLevel) openDisk(level);
        RegionFileStorage storage = disk;
        if (storage == null) return EMPTY;
        // Nur vorhandene Regionen anfassen: RegionFileStorage legte fehlende sonst leer an
        if (!Files.isRegularFile(diskDir.resolve("r." + (pos.x() >> 5) + "." + (pos.z() >> 5) + ".mca"))) return EMPTY;
        CompletableFuture<Optional<CompoundTag>> out = new CompletableFuture<>();
        WorkerPool.submit(WorkerPool.PRIO_LOD, () -> {
            try {
                CompoundTag tag;
                synchronized (storage) {
                    tag = storage.read(pos);
                }
                // Andere Spielversion: Bobby selbst verlangt dann /bobby upgrade – nicht raten
                if (tag != null && tag.getIntOr("DataVersion", -1) != SharedConstants.getCurrentVersion().dataVersion().version()) tag = null;
                if (tag != null && ++diskHits == 1) LOG.info("[vulkanfish] LOD: erster Chunk direkt aus dem Bobby-Cache ({})", diskDir);
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
            long seedHash = ((simon.vulkanfish.client.mixin.BiomeManagerAccessor) level.getBiomeManager()).vulkanfish$zoomSeed();
            var id = level.dimension().identifier();
            Path dir = serverDir.resolve(Long.toString(seedHash)).resolve(id.getNamespace()).resolve(id.getPath());
            if (!Files.isDirectory(dir)) return;
            diskDir = dir;
            disk = new RegionFileStorage(new RegionStorageInfo("bobby", level.dimension(), "chunk"), dir, false);
            LOG.info("[vulkanfish] LOD: Bobby-Cache ohne Bobby gefunden, lese direkt: {}", dir);
        } catch (Throwable t) {
            LOG.warn("[vulkanfish] LOD: Bobby-Cache nicht lesbar ({})", t.toString());
            closeDisk();
        }
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
