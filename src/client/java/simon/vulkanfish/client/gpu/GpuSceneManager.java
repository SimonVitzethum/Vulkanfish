package simon.vulkanfish.client.gpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GPU-Scene: ein Satz persistenter SSBOs, einmal allokiert, nie pro Frame
 * auf CPU neu aufgebaut ("nichts auf der CPU rechnen" im Hot Path).
 *
 * <ul>
 *   <li>ClusterBuffer: pro Chunk-Cluster AABB + LOD + Meshlet-Range (vom Mesher
 *       per Transfer-Queue gestreamt, CPU schreibt nur Voxel-Deltas in Ringbuffer).</li>
 *   <li>MeshletBuffer: Meshlet-Header (cone, error, lod) + komprimierte Indices.</li>
 *   <li>InstanceBuffer: siehe {@link EntityInstanceBuffer} – 30k+ Einträge.</li>
 *   <li>IndirectBuffer: VkDrawMeshTasksIndirectCommandEXT, wird AUSSCHLIESSLICH
 *       von Compute-/Task-Shadern geschrieben (Multi-Draw mit 1 Call).</li>
 * </ul>
 */
public final class GpuSceneManager {
    private static final Logger LOG = LoggerFactory.getLogger("vulkanfish");
    private final GpuDrivenConfig config;
    private boolean ready;

    public GpuSceneManager(GpuDrivenConfig config) {
        this.config = config;
    }

    public void init(long vkDevice, long physicalDevice) {
        // TODO: VMA-Allokation: Cluster (256 MB), Meshlets (1 GB, sparse),
        //  Indirect (4 MB), plus DescriptorSets. Handles als long.
        LOG.info("[vulkanfish] GpuScene: maxEntities={} viewDist={}",
                config.maxEntities(), config.chunkViewDistance());
        ready = true;
    }

    public boolean isReady() {
        return ready;
    }

    /** Nur Deltas (Chunk-Dirty, Entity-Teleport) in Ringbuffer schreiben, kein Full-Upload. */
    public void pushDeltas() {
    }

    public void shutdown() {
        ready = false;
    }
}
