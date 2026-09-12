package simon.vulkanfish.client.gpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Raytracing-Modul (VK_KHR_ray_tracing_pipeline + VK_KHR_acceleration_structure).
 *
 * <p>Strategie für 250 Chunks + 30k Entities:
 * <ul>
 *   <li>Terrain-BLAS: pro 2x2 Sections ein BLAS aus Meshlet-AABBs (procedural,
 *       kein Triangle-Soup-Rebuild pro Frame). Nur dirty Cluster werden
 *       refittet (UPDATE statt FULL_REBUILD).</li>
 *   <li>Entity-BLAS: 1 BLAS pro Archetyp (z.B. Spieler-Modell), instanziiert
 *       30k-fach in TLAS mit unterschiedlichen Transforms – kein 30k-BLAS.</li>
 *   <li>TLAS: 2 Instanzen (Terrain + Entities), pro Frame UPDATE auf
 *       Async-Compute-Queue, parallel zum Raster-Fortschritt.</li>
 *   <li>Effekte: RT-Schatten (1 SPP) + RTGI (0.5 SPP, halbe Auflösung) +
 *       Reflexionen via Ray-Query im Mesh/Fragment-Shader + SVGF-ähnlicher
 *       Denoiser als Compute. Während RT noch rechnet, baut Compute schon
 *       Meshlets/Indirect des nächsten Frames (Timeline-Semaphoren).</li>
 * </ul>
 */
public final class RaytracingModule {
    private static final Logger LOG = LoggerFactory.getLogger("vulkanfish");
    private final GpuDrivenConfig config;
    private boolean supported;

    public RaytracingModule(GpuDrivenConfig config) {
        this.config = config;
    }

    /** Capability-Check: VK_KHR_ray_tracing_pipeline + meshShader + indirect. */
    public boolean probe(long physicalDevice) {
        // TODO: via LWJGL vkEnumerateDeviceExtensionProperties prüfen.
        // Fallback: Raster-only (Hi-Z + Mesh-Shader bleiben aktiv).
        supported = config.enableRaytracing();
        LOG.info("[vulkanfish] Raytracing supported={}", supported);
        return supported;
    }

    public boolean isSupported() {
        return supported;
    }
}
