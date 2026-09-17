package simon.vulkanfish.client.gpu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Zentrale Config. 26.2 hat nativ Vulkan (Blaze3D VulkanBackend),
 * deshalb kein VulkanMod nötig. Alles hier schaltet den GPU-driven Pfad.
 *
 * <p>250 Chunks Sichtweite = NUR mit LOD + Streaming machbar:
 * 250 Radius = ~196k Sections à 16x16x16. Volldetail unmöglich
 * (VRAM + Meshing). Darum: Nahfeld Meshlets voll, Fernfeld
 * geclusterte LOD-Meshlets mit Screen-Space-Error.
 */
public record GpuDrivenConfig(
        boolean enableMeshShaders,
        boolean enableHizCulling,
        boolean enableRaytracing,
        boolean enableAsyncCompute,
        boolean enableBobbyFarField,
        int maxEntities,
        int chunkViewDistance,
        int bobbyLodStart,
        int bobbyBudgetPerFrame,
        int framesInFlight,
        int meshletMaxVerts,
        int meshletMaxTris,
        boolean enableTaa,
        boolean enableLod,
        int lodDistanceChunks,
        float lodPixelError,
        float lodGpuBudgetMs) {

    // LOD-Fernfeld: Radius in Chunks und erlaubter Bildfehler eines LOD-Voxels (Pixel).
    // 1.0 = ein grober Voxel ist hoechstens ein Pixel gross -> kein sichtbarer Detailverlust.
    private static final int DEFAULT_LOD_CHUNKS = 64;
    private static final float DEFAULT_LOD_PIXEL_ERROR = 1.0f;
    // GPU-Zeit je Frame fuer LOD-Generierung + Meshing, solange Knoten fehlen
    private static final float DEFAULT_LOD_GPU_MS = 1.5f;

    public static GpuDrivenConfig defaults() {
        return new GpuDrivenConfig(true, true, true, true, true,
                32768, // 30k+ entities
                250,
                32,    // ab 32 Chunks: Bobby-Fernfeld als LOD (Vanilla macht Nahfeld)
                8,     // max 8 Fern-Chunks pro Frame ins GPU-Streaming
                3,     // triple buffering für async overlap
                64, 124, !"false".equals(System.getProperty("vulkanfish.taa")),
                !"false".equals(System.getProperty("vulkanfish.lod")),
                Integer.getInteger("vulkanfish.lodChunks", DEFAULT_LOD_CHUNKS),
                floatProp("vulkanfish.lodPixelError", DEFAULT_LOD_PIXEL_ERROR),
                floatProp("vulkanfish.lodGpuMs", DEFAULT_LOD_GPU_MS));
    }

    public static GpuDrivenConfig load() {
        try {
            Path p = Path.of("config", "vulkanfish.json");
            if (Files.exists(p)) {
                String json = Files.readString(p);
                boolean rt = !json.contains("\"enableRaytracing\": false");
                boolean mesh = !json.contains("\"enableMeshShaders\": false");
                boolean bobby = !json.contains("\"enableBobbyFarField\": false");
                boolean taa = !json.contains("\"enableTaa\": false") && !"false".equals(System.getProperty("vulkanfish.taa"));
                boolean lod = !json.contains("\"enableLod\": false") && !"false".equals(System.getProperty("vulkanfish.lod"));
                int lodChunks = Integer.getInteger("vulkanfish.lodChunks", (int) number(json, "lodDistance", DEFAULT_LOD_CHUNKS));
                float lodErr = floatProp("vulkanfish.lodPixelError", (float) number(json, "lodPixelError", DEFAULT_LOD_PIXEL_ERROR));
                float lodMs = floatProp("vulkanfish.lodGpuMs", (float) number(json, "lodGpuBudgetMs", DEFAULT_LOD_GPU_MS));
                return new GpuDrivenConfig(mesh, true, rt, true, bobby,
                        32768, 250, 32, 8, 3, 64, 124, taa, lod, lodChunks, lodErr, lodMs);
            }
        } catch (IOException ignored) {
        }
        return defaults();
    }

    private static float floatProp(String key, float def) {
        String v = System.getProperty(key);
        if (v == null) return def;
        try {
            return Float.parseFloat(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** "key": Zahl aus dem (flachen) Config-JSON, sonst def. */
    private static double number(String json, String key, double def) {
        var m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9.]+)").matcher(json);
        if (!m.find()) return def;
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
