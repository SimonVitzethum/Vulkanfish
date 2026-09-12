package simon.vulkanfish.client.lod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bobby-Cache als LOD-Quelle (optional, nur Reflection – keine harte Abhaengigkeit):
 * Bobby speichert jeden je gesehenen Chunk auf Platte. Wir lesen die gespeicherten Chunk-Tags
 * direkt aus seinem Speicher (auch weit ausserhalb der Sichtweite, ohne sie als Fake-Chunks
 * zu laden) – sie haben Vorrang vor Spielstand und Generator.
 *
 * <p>Erwartete Bobby-API: ClientChunkCache implementiert {@code ClientChunkCacheExt} mit
 * {@code bobby_getFakeChunkManager()}; der Manager haelt einen {@code FakeChunkStorage} mit
 * {@code loadTag(ChunkPos) -> CompletableFuture<Optional<CompoundTag>>}. Weicht die API ab,
 * schaltet sich die Quelle still ab.
 */
final class BobbySource {
    private static final Logger LOG = LoggerFactory.getLogger("vulkanfish");
    private static final CompletableFuture<Optional<CompoundTag>> EMPTY = CompletableFuture.completedFuture(Optional.empty());

    private boolean available;
    private Method getManager;
    private Field storageField;
    private Method loadTag;

    BobbySource() {
        if (!FabricLoader.getInstance().isModLoaded("bobby")) return;
        try {
            Class<?> ext = Class.forName("de.johni0702.minecraft.bobby.ext.ClientChunkCacheExt");
            getManager = ext.getMethod("bobby_getFakeChunkManager");
            Class<?> mgr = Class.forName("de.johni0702.minecraft.bobby.FakeChunkManager");
            for (Field f : mgr.getDeclaredFields()) {
                if (f.getType().getSimpleName().contains("FakeChunkStorage")) {
                    f.setAccessible(true);
                    storageField = f;
                    break;
                }
            }
            if (storageField == null) throw new NoSuchFieldException("FakeChunkStorage");
            loadTag = storageField.getType().getMethod("loadTag", ChunkPos.class);
            available = true;
            LOG.info("[vulkanfish] LOD: Bobby-Cache als Quelle aktiv (Vorrang vor Spielstand und Generator)");
        } catch (Throwable t) {
            LOG.warn("[vulkanfish] LOD: Bobby gefunden, aber API abweichend – Bobby-Quelle aus ({})", t.toString());
        }
    }

    boolean available() {
        return available;
    }

    @SuppressWarnings("unchecked")
    CompletableFuture<Optional<CompoundTag>> load(ClientLevel level, ChunkPos pos) {
        if (!available || level == null) return EMPTY;
        try {
            Object manager = getManager.invoke(level.getChunkSource());
            if (manager == null) return EMPTY;
            Object storage = storageField.get(manager);
            if (storage == null) return EMPTY;
            Object result = loadTag.invoke(storage, pos);
            if (result instanceof CompletableFuture<?> f) {
                return ((CompletableFuture<Object>) f).thenApply(o -> o instanceof Optional<?> opt && opt.isPresent()
                        && opt.get() instanceof CompoundTag tag ? Optional.of(tag) : Optional.<CompoundTag>empty())
                        .exceptionally(t -> Optional.empty());
            }
            if (result instanceof Optional<?> opt && opt.isPresent() && opt.get() instanceof CompoundTag tag) {
                return CompletableFuture.completedFuture(Optional.of(tag));
            }
            return EMPTY;
        } catch (Throwable t) {
            available = false;
            LOG.warn("[vulkanfish] LOD: Bobby-Zugriff fehlgeschlagen – Bobby-Quelle aus ({})", t.toString());
            return EMPTY;
        }
    }
}
