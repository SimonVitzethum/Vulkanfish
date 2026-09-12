package simon.vulkanfish.client.gpu;

/**
 * Hi-Z Pyramide (Hierarchical Z).
 *
 * <p>Ablauf pro Frame (alles GPU, 0 CPU):
 * <ol>
 *   <li>Depth-Prepass / Vorframe-Depth kopieren (Mip 0).</li>
 *   <li>Compute-Downsample 2x2 Max-Reduction bis 1x1
 *       ({@code assets/vulkanfish/shaders/hiz_downsample.comp}).</li>
 *   <li>Task-Shader liest passende Mips per AABB-Projektion und verwirft
 *       verdeckte Meshlets VOR Expansion (frustum + occlusion).</li>
 * </ol>
 * Doppelgepuffert: Frame N cullt gegen Hi-Z von Frame N-1 (1 Frame Latenz,
 * dafür keine Pipeline-Bubble). Das ist Standard für GPU-driven Renderer.
 */
public final class HizPyramid {
    private final GpuDrivenConfig config;
    private int width, height, mipLevels;

    public HizPyramid(GpuDrivenConfig config) {
        this.config = config;
    }

    public void onResize(int w, int h) {
        this.width = w;
        this.height = h;
        this.mipLevels = 32 - Integer.numberOfLeadingZeros(Math.max(w, h));
    }

    /** Reine GPU-Barrieren + Dispatch-Größen, keine CPU-Pixelarbeit. */
    public int[] dispatchSizesForMip(int mip) {
        int w = Math.max(1, width >> mip);
        int h = Math.max(1, height >> mip);
        return new int[]{(w + 7) / 8, (h + 7) / 8, 1};
    }

    public int mipLevels() {
        return mipLevels;
    }
}
