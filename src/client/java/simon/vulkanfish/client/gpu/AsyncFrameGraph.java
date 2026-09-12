package simon.vulkanfish.client.gpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Frame-Graph mit 3 Frames in Flight + Timeline-Semaphoren.
 *
 * <p>Idee:Während Grafik-Queue Frame N rastert / RT denoist,
 * rechnet Compute-Queue bereits Frame N+1 (Meshlet-Culling,
 * Entity-Compaction, BLAS-Refit) und Transfer-Queue streamt
 * Chunk-LODs für Frame N+2. Keine CPU wartet auf GPU (kein
 * vkQueueWaitIdle im Hot Path, nur Timeline-Werte).
 *
 * <p>Reine Java-Seite hält nur Frame-IDs + Fences; die echten
 * VkSemaphore / vkCmdDrawMeshTasksIndirectEXT Calls passieren in
 * {@link VulkanfishRenderer} via Blaze3D-Vulkan-Handles (LWJGL,
 * zur Laufzeit per Reflection auf VulkanBackend geholt, damit
 * der Mod auch ohne compile-time LWJGL-Vulkan baut).
 */
public final class AsyncFrameGraph {
    private static final Logger LOG = LoggerFactory.getLogger("vulkanfish");
    private final GpuDrivenConfig config;
    private long frameIndex;
    private final long[] timelineValues;

    public AsyncFrameGraph(GpuDrivenConfig config) {
        this.config = config;
        this.timelineValues = new long[config.framesInFlight()];
    }

    public void init() {
        LOG.info("[vulkanfish] AsyncFrameGraph: {} frames in flight, asyncCompute={}",
                config.framesInFlight(), config.enableAsyncCompute());
    }

    /** Vom Render-Thread pro Frame aufgerufen, gibt Slot 0..N-1 zurück. */
    public int beginFrame() {
        int slot = (int) (frameIndex % config.framesInFlight());
        timelineValues[slot] = frameIndex + 1;
        return slot;
    }

    public void endFrame(int slot) {
        frameIndex++;
    }

    public long currentFrame() {
        return frameIndex;
    }

    public void shutdown() {
    }
}
