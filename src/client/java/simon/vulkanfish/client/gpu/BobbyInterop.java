package simon.vulkanfish.client.gpu;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optionale Bobby-Anbindung (Soft-Dependency, nur Reflection, kein
 * Compile-Depot). Bobby (Mod-ID {@code bobby}, Johni0702) cached Chunks auf
 * Platte und stellt sie als {@code FakeChunk}s ueber die Sichtweite des
 * Servers hinaus bereit – exakt das Fernfeld, das unser 250-Chunk-Renderer
 * braucht.
 *
 * <p>Ablauf: {@link #probe()} prueft Mod-Praesenz; {@link #snapshotFarChunks}
 * liefert alle Fake-Chunks jenseits der Vanilla-Sichtweite als Sections +
 * Heightmaps an {@link MeshletManager} / LOD-Builder. Ohne Bobby ist alles
 * No-Op (Vanilla-Sichtweite + prozedurales Fernfeld-Fallback).
 */
public final class BobbyInterop {
    private static final Logger LOG = LoggerFactory.getLogger("vulkanfish");
    public static final String MOD_ID = "bobby";

    private Method mGetFakeChunkManager;
    private Method mGetChunk;
    private Method mGetFakeChunks;
    private boolean available;

    public boolean probe() {
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
            LOG.info("[vulkanfish] Bobby nicht installiert – Fernfeld ohne Platten-Cache");
            return false;
        }
        try {
            Class<?> ext = Class.forName("de.johni0702.minecraft.bobby.ext.ClientChunkCacheExt");
            Class<?> mgr = Class.forName("de.johni0702.minecraft.bobby.FakeChunkManager");
            mGetFakeChunkManager = ext.getMethod("bobby_getFakeChunkManager");
            mGetChunk = mgr.getMethod("getChunk", int.class, int.class);
            mGetFakeChunks = mgr.getMethod("getFakeChunks");
            available = true;
            LOG.info("[vulkanfish] Bobby-Interop aktiv (Reflection, keine harte Abhaengigkeit)");
            return true;
        } catch (ReflectiveOperationException e) {
            LOG.warn("[vulkanfish] Bobby da, aber API abweichend – Interop aus", e);
            return false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /** Ein Snapshot-Eintrag: Chunk-Pos + Sections + Heightmap (MOTION_BLOCKING). */
    public record FarChunk(int x, int z, LevelChunkSection[] sections, boolean[][] opaque) {
    }

    /**
     * Sammelt Fake-Chunks ausserhalb {@code vanillaRadius} um (centerX, centerZ).
     * Begrenzt auf {@code maxChunks} pro Aufruf (Streaming-Budget, Rest folgt).
     */
    @SuppressWarnings("unchecked")
    public List<FarChunk> snapshotFarChunks(int centerX, int centerZ, int vanillaRadius, int maxChunks) {
        List<FarChunk> out = new ArrayList<>();
        if (!available) return out;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return out;
        try {
            Object mgr = mGetFakeChunkManager.invoke(level.getChunkSource());
            if (mgr == null) return out;
            Collection<LevelChunk> chunks = (Collection<LevelChunk>) mGetFakeChunks.invoke(mgr);
            for (LevelChunk chunk : chunks) {
                if (out.size() >= maxChunks) break;
                int cx = chunk.getPos().x();
                int cz = chunk.getPos().z();
                int cheb = Math.max(Math.abs(cx - centerX), Math.abs(cz - centerZ));
                if (cheb <= vanillaRadius) continue; // Nahfeld gehoert Vanilla/Mesher
                out.add(toFarChunk(chunk));
            }
        } catch (ReflectiveOperationException e) {
            LOG.warn("[vulkanfish] Bobby-Snapshot fehlgeschlagen", e);
            available = false;
        }
        return out;
    }

    /** Einzelabfrage (fuer Dirty-Tracking / gezielte Updates). */
    public LevelChunk getFarChunk(int x, int z) {
        if (!available) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        try {
            Object mgr = mGetFakeChunkManager.invoke(mc.level.getChunkSource());
            if (mgr == null) return null;
            Object chunk = mGetChunk.invoke(mgr, x, z);
            return chunk instanceof LevelChunk lc ? lc : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static FarChunk toFarChunk(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        // grobe Opazitaetsmaske aus Heightmap (fuer LOD-Farben, 16x16)
        boolean[][] opaque = new boolean[16][16];
        try {
            for (int x = 0; x < 16; x++)
                for (int z = 0; z < 16; z++)
                    opaque[x][z] = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)
                            > chunk.getMinY();
        } catch (Throwable ignored) {
        }
        return new FarChunk(chunk.getPos().x(), chunk.getPos().z(), sections, opaque);
    }

    /** Section-Index aus Welt-Y (Versions-tolerant ueber SectionPos). */
    public static int sectionIndex(ClientLevel level, int worldY) {
        return SectionPos.blockToSectionCoord(worldY - level.dimensionType().minY());
    }
}
