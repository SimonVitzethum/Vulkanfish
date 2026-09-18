package simon.vulkanfish.client.gpu;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import java.util.Set;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRRayQuery;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceMeshShaderFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Schaltet die Extensions/Features des GPU-driven Pfads auf MOJANGS VkDevice frei (26.3:
 * eigene {@link FeatureSet}s, die der Mixin an {@code VulkanFeatureSets.optionalFeatureSets}
 * haengt – Vanilla aktiviert sie nur bei Support, sonst Classic-Fallback/Vanilla).
 *
 * <p>Hintergrund: Mojang aktiviert nur dynamic_rendering, push_descriptor,
 * synchronization2, vertex_attribute_divisor, swapchain (+multi_draw).
 * Nachtraeglich lassen sich Extensions nicht aktivieren – vorher schon, und
 * LWJGL laedt dann die Function-Pointer (vkCmdDrawMeshTasks*EXT) automatisch
 * in Mojangs VkDevice-Wrapper. Damit teilen wir Device, Queue und Frame-
 * Submission mit Blaze3D und koennen direkt in dessen Main-Target rendern.
 *
 * <p>Flags werden in {@link #probeDevice} gesetzt (aus dem createDevice-Mixin, VOR
 * VMA-Erzeugung – die VMA-Flags haengen an rayQueryEnabled).
 */
public final class MeshShaderSupport {
    private static final Logger LOG = LoggerFactory.getLogger("vulkanfish");
    private static final String EXT_MESH = "VK_EXT_mesh_shader";
    // VK_EXT_mesh_shader haengt auf 1.2-Devices von VK_KHR_spirv_1_4 ab
    private static final String EXT_SPIRV_14 = "VK_KHR_spirv_1_4";

    private static final String EXT_AS = "VK_KHR_acceleration_structure";
    private static final String EXT_RAY_QUERY = "VK_KHR_ray_query";
    private static final String EXT_DEFERRED_HOST = "VK_KHR_deferred_host_operations";

    private static volatile boolean meshEnabled;
    private static volatile boolean indirectCountEnabled;
    private static volatile boolean rayQueryEnabled;
    private static volatile boolean float64Enabled;

    public static boolean float64Enabled() {
        return float64Enabled;
    }

    private MeshShaderSupport() {
    }

    private static VulkanFeature meshFeature() {
        return new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceMeshShaderFeaturesEXT.class,
                EXTMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT,
                VkPhysicalDeviceMeshShaderFeaturesEXT.SIZEOF),
                "meshShader", VkPhysicalDeviceMeshShaderFeaturesEXT.MESHSHADER);
    }

    private static VulkanFeature indirectCountFeature() {
        return new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT,
                "drawIndirectCount", VkPhysicalDeviceVulkan12Features.DRAWINDIRECTCOUNT);
    }

    private static VulkanFeature storageReadFeature() {
        return new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT,
                "shaderStorageImageReadWithoutFormat",
                VkPhysicalDeviceFeatures.SHADERSTORAGEIMAGEREADWITHOUTFORMAT);
    }

    private static VulkanFeature storageWriteFeature() {
        return new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT,
                "shaderStorageImageWriteWithoutFormat",
                VkPhysicalDeviceFeatures.SHADERSTORAGEIMAGEWRITEWITHOUTFORMAT);
    }

    private static VulkanFeature fragmentStoresFeature() {
        return new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT,
                "fragmentStoresAndAtomics", VkPhysicalDeviceFeatures.FRAGMENTSTORESANDATOMICS);
    }

    private static VulkanFeature float64Feature() {
        return new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT,
                "shaderFloat64", VkPhysicalDeviceFeatures.SHADERFLOAT64);
    }

    /** Basis (Classic-Raster + Compute + OIT): ohne fragmentStores laeuft nur Vanilla. */
    public static FeatureSet baseFeatureSet() {
        return new FeatureSet("Vulkanfish base", Set.of(),
                Set.of(fragmentStoresFeature(), indirectCountFeature(), storageReadFeature(), storageWriteFeature()));
    }

    /** Mesh-Stage (sonst Classic-Raster-Fallback, Culling bleibt GPU-driven). */
    public static FeatureSet meshFeatureSet() {
        return new FeatureSet("Vulkanfish mesh", Set.of(EXT_MESH, EXT_SPIRV_14), Set.of(meshFeature()));
    }

    /** GPU-Worldgen: Noise-Koordinaten in double wie Vanilla (sonst CPU-Fallback dort). */
    public static FeatureSet float64FeatureSet() {
        return new FeatureSet("Vulkanfish float64", Set.of(), Set.of(float64Feature()));
    }

    /**
     * Hardware-Raytracing fuer die Blocklicht-Schatten: Ray Queries aus dem Deferred-
     * Compute gegen BLAS, die direkt aus unserem Vertexpuffer gebaut werden (R16G16B16_UNORM,
     * Stride 16). Ohne eines der Teile bleibt es beim Vanilla-Blocklicht.
     */
    public static FeatureSet rtFeatureSet() {
        return new FeatureSet("Vulkanfish raytracing", Set.of(EXT_AS, EXT_RAY_QUERY, EXT_DEFERRED_HOST),
                Set.of(new VulkanFeature(new VulkanPNextStruct(
                                VkPhysicalDeviceAccelerationStructureFeaturesKHR.class,
                                KHRAccelerationStructure.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR,
                                VkPhysicalDeviceAccelerationStructureFeaturesKHR.SIZEOF),
                                "accelerationStructure",
                                VkPhysicalDeviceAccelerationStructureFeaturesKHR.ACCELERATIONSTRUCTURE),
                        new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceRayQueryFeaturesKHR.class,
                                KHRRayQuery.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR,
                                VkPhysicalDeviceRayQueryFeaturesKHR.SIZEOF),
                                "rayQuery", VkPhysicalDeviceRayQueryFeaturesKHR.RAYQUERY),
                        new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "bufferDeviceAddress",
                                VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS)));
    }

    /**
     * Aus dem createDevice-Mixin (VOR vkCreateDevice-Nutzung/VMA): Faehigkeiten abfragen und
     * Flags setzen. Entscheidend ist, was auf Mojangs Device AKTIVIERT wird (FeatureSets oben),
     * die Abfrage hier spiegelt exakt deren Bedingungen (Support = aktiviert).
     */
    public static void probeDevice(VulkanPhysicalDevice physicalDevice) {
        try {
            boolean hasMeshExt = physicalDevice.hasDeviceExtension(EXT_MESH)
                    && physicalDevice.hasDeviceExtension(EXT_SPIRV_14);
            try (Arena arena = new Arena()) {
                VkPhysicalDeviceFeatures2 query = VkPhysicalDeviceFeatures2.calloc(arena.stack()).sType$Default();
                VkPhysicalDeviceVulkan12Features f12 = VkPhysicalDeviceVulkan12Features.calloc(arena.stack()).sType$Default();
                VkPhysicalDeviceMeshShaderFeaturesEXT fMesh =
                        VkPhysicalDeviceMeshShaderFeaturesEXT.calloc(arena.stack()).sType$Default();
                VkPhysicalDeviceAccelerationStructureFeaturesKHR fAs =
                        VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(arena.stack()).sType$Default();
                VkPhysicalDeviceRayQueryFeaturesKHR fRq = VkPhysicalDeviceRayQueryFeaturesKHR.calloc(arena.stack()).sType$Default();
                query.pNext(f12.address());
                f12.pNext(fMesh.address());
                fMesh.pNext(fAs.address());
                fAs.pNext(fRq.address());
                VK12.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), query);
                // Basis zuerst (Classic-Raster + Compute + OIT kommen ohne Mesh-Stage aus)
                if (!fragmentStoresFeature().get(query)) {
                    LOG.warn("[vulkanfish] fragmentStoresAndAtomics nicht unterstuetzt – Vanilla rendert");
                    return;
                }
                meshEnabled = false;
                indirectCountEnabled = indirectCountFeature().get(query);
                float64Enabled = float64Feature().get(query);
                // Mesh-Stage nur mit Extension + Feature (sonst Classic-Raster-Fallback)
                if (hasMeshExt && meshFeature().get(query)) {
                    meshEnabled = true;
                } else {
                    LOG.info("[vulkanfish] GPU ohne {}/{} oder meshShader-Feature – Classic-Raster-Fallback (Compute-Culling bleibt)",
                            EXT_MESH, EXT_SPIRV_14);
                }
                probeRayQuery(physicalDevice, fAs, fRq, f12, arena);
            }
            LOG.info("[vulkanfish] Mojang-Device erweitert: mesh={} (drawIndirectCount={})",
                    meshEnabled, indirectCountEnabled);
        } catch (Throwable t) {
            meshEnabled = false;
            indirectCountEnabled = false;
            rayQueryEnabled = false;
            LOG.warn("[vulkanfish] Device-Abfrage fehlgeschlagen – Vanilla rendert", t);
        }
    }

    private static void probeRayQuery(VulkanPhysicalDevice physicalDevice,
                                      VkPhysicalDeviceAccelerationStructureFeaturesKHR fAs,
                                      VkPhysicalDeviceRayQueryFeaturesKHR fRq, VkPhysicalDeviceVulkan12Features f12,
                                      Arena arena) {
        rayQueryEnabled = false;
        if (!GpuDrivenConfig.load().enableRaytracing()) {
            LOG.info("[vulkanfish] Raytracing per Config aus");
            return;
        }
        if (!physicalDevice.hasDeviceExtension(EXT_AS) || !physicalDevice.hasDeviceExtension(EXT_RAY_QUERY)
                || !physicalDevice.hasDeviceExtension(EXT_DEFERRED_HOST)) {
            LOG.info("[vulkanfish] GPU ohne Ray Queries – Blocklicht ohne Raytracing");
            return;
        }
        if (!fAs.accelerationStructure() || !fRq.rayQuery() || !f12.bufferDeviceAddress()) {
            LOG.info("[vulkanfish] Ray-Query-Features fehlen – Blocklicht ohne Raytracing");
            return;
        }
        VkFormatProperties fp = VkFormatProperties.calloc(arena.stack());
        VK10.vkGetPhysicalDeviceFormatProperties(physicalDevice.vkPhysicalDevice(), VK10.VK_FORMAT_R16G16B16_UNORM, fp);
        if ((fp.bufferFeatures() & KHRAccelerationStructure.VK_FORMAT_FEATURE_ACCELERATION_STRUCTURE_VERTEX_BUFFER_BIT_KHR) == 0) {
            LOG.info("[vulkanfish] R16G16B16_UNORM nicht als BLAS-Vertexformat – Blocklicht ohne Raytracing");
            return;
        }
        rayQueryEnabled = true;
        LOG.info("[vulkanfish] Mojang-Device erweitert: {} + {} (Raytracing fuer Blocklicht-Schatten)", EXT_AS, EXT_RAY_QUERY);
    }

    /** Ray Queries + Buffer-Device-Address auf Mojangs Device aktiv (vor der VMA-Erzeugung gesetzt). */
    public static boolean rayQueryEnabled() {
        return rayQueryEnabled;
    }

    public static boolean meshEnabled() {
        return meshEnabled;
    }

    public static boolean indirectCountEnabled() {
        return indirectCountEnabled;
    }
}
