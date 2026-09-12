package simon.vulkanfish.client.gpu;

/**
 * 30k Entities ohne CPU-Flaschenhals.
 *
 * <p>Layout pro Instance (64 Byte, GPU-coherent):
 * modelMatrix (48B, als 3x4 fp16/fp32) + color/flags (8B) + aabb (8B index).
 * CPU schreibt NUR neue/veränderte Transforms in einen persistent-gemappten
 * Ringbuffer (kein Shuffle, kein Sort, keine Matrix-Math im Render-Thread).
 * Ein Compute-Shader ({@code entity_cull.comp}) macht Frustum+AABB+Hi-Z-Test
 * und compactet sichtbare in den Indirect-Draw-Buffer. Ein einziger
 * vkCmdDrawMeshTasksIndirectEXT zeichnet dann alle Entities (Multi-Draw).
 */
public final class EntityInstanceBuffer {
    private final GpuDrivenConfig config;
    private int liveCount;

    public EntityInstanceBuffer(GpuDrivenConfig config) {
        this.config = config;
    }

    public int capacity() {
        return config.maxEntities();
    }

    public void setLiveCount(int n) {
        this.liveCount = Math.min(n, capacity());
    }

    public int liveCount() {
        return liveCount;
    }
}
