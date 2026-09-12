package simon.vulkanfish.client.gpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TLAS-Verwaltung: genau 2 Top-Level-Instanzen (Terrain-BLAS + Entity-BLAS).
 *
 * <ul>
 *   <li>Terrain-BLAS: pro 2x2-Sections ein BLAS aus Meshlet-AABBs (procedural
 *       AABBs, kein Triangle-Rebuild). Dirty Cluster -&gt; UPDATE (Refit).</li>
 *   <li>Entity-BLAS: ein BLAS pro Archetyp ({@link ArchetypeStore}); 30k
 *       Instanzen teilen sich wenige BLAS, TLAS enthaelt sie als Instanzen mit
 *       eigenem Transform.</li>
 *   <li>Pro Frame: UPDATE wenn &lt; 25 % der Cluster dirty bzw. &lt; 4096
 *       Entity-Transforms neu, sonst FULL_REBUILD auf der Async-Compute-Queue,
 *       parallel zum Raster-Fortschritt (Timeline-Semaphoren, siehe
 *       {@link AsyncFrameGraph}).</li>
 * </ul>
 *
 * <p>Die echten {@code vkCmdBuildAccelerationStructuresKHR}-Calls laufen ueber
 * {@link BlazeDeviceInterop} (LWJGL); diese Klasse haelt Layout, Dirty-
 * Tracking und die Rebuild-Heuristik (rein CPU-seitig, O(dirty)).
 */
public final class TlasManager {
    private static final Logger LOG = LoggerFactory.getLogger("vulkanfish");
    private static final double FULL_REBUILD_DIRTY_RATIO = 0.25;
    private static final int FULL_REBUILD_ENTITY_MOVES = 4096;

    private final GpuDrivenConfig config;
    private final BlazeDeviceInterop device;
    private long terrainBlas;
    private long[] archetypeBlas = new long[0];
    private long tlas;
    private int dirtyClusters;
    private int totalClusters;
    private int movedEntities;
    private long buildFrame;

    public TlasManager(GpuDrivenConfig config, BlazeDeviceInterop device) {
        this.config = config;
        this.device = device;
    }

    public void onClusterBaked(int clusterCount) {
        this.totalClusters = clusterCount;
    }

    public void markClustersDirty(int n) {
        dirtyClusters += n;
    }

    public void markEntitiesMoved(int n) {
        movedEntities += n;
    }

    /** Einmal pro Frame vom Render-Thread: waehlt UPDATE vs. REBUILD. */
    public BuildPlan planFrame(long frameIndex) {
        boolean terrainFull = totalClusters > 0
                && (double) dirtyClusters / totalClusters >= FULL_REBUILD_DIRTY_RATIO;
        boolean entityFull = movedEntities >= FULL_REBUILD_ENTITY_MOVES;
        BuildPlan plan = new BuildPlan(frameIndex, !terrainFull, !entityFull);
        dirtyClusters = 0;
        movedEntities = 0;
        buildFrame = frameIndex;
        return plan;
    }

    public record BuildPlan(long frame, boolean terrainUpdate, boolean entityUpdate) {
        public boolean terrainFullRebuild() {
            return !terrainUpdate;
        }

        public boolean entityFullRebuild() {
            return !entityUpdate;
        }
    }

    public boolean isReady() {
        return device.isReady() && device.supportsRaytracing() && tlas != 0L;
    }

    // Handle-Setter fuers native Backend (LWJGL-Seite)
    public void setHandles(long terrainBlas, long[] archetypeBlas, long tlas) {
        this.terrainBlas = terrainBlas;
        this.archetypeBlas = archetypeBlas.clone();
        this.tlas = tlas;
        LOG.info("[vulkanfish] TLAS bereit: terrain=0x{} entities={} tlas=0x{} (frame {})",
                Long.toHexString(terrainBlas), archetypeBlas.length,
                Long.toHexString(tlas), buildFrame);
    }

    public long tlasHandle() {
        return tlas;
    }
}
