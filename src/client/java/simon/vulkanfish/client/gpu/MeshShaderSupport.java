package simon.vulkanfish.client.gpu;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import java.util.Collection;
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
 * Schaltet die Extensions/Features des GPU-driven Pfads direkt auf MOJANGS
 * VkDevice frei (per Mixin in {@code VulkanBackend.createDevice}, BEVOR das
 * Device erzeugt wird).
 *
 * <p>Hintergrund: Mojang aktiviert nur dynamic_rendering, push_descriptor,
 * synchronization2, vertex_attribute_divisor, swapchain (+multi_draw).
 * Nachtraeglich lassen sich Extensions nicht aktivieren – vorher schon, und
 * LWJGL laedt dann die Function-Pointer (vkCmdDrawMeshTasks*EXT) automatisch
 * in Mojangs VkDevice-Wrapper. Damit teilen wir Device, Queue und Frame-
 * Submission mit Blaze3D und koennen direkt in dessen Main-Target rendern.
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

    /** Aus dem VulkanBackend-Mixin: Extension-/Feature-Sets vor vkCreateDevice ergaenzen. */
    public static void augment(Collection<String> extensions, VulkanPhysicalDevice physicalDevice,
                               Set<VulkanFeature> features) {
        try {
            if (!physicalDevice.hasDeviceExtension(EXT_MESH) || !physicalDevice.hasDeviceExtension(EXT_SPIRV_14)) {
                LOG.warn("[vulkanfish] GPU ohne {}/{} – GPU-driven Pfad aus, Vanilla rendert", EXT_MESH, EXT_SPIRV_14);
                return;
            }
            VulkanPNextStruct meshStruct = new VulkanPNextStruct(
                    EXTMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT,
                    VkPhysicalDeviceMeshShaderFeaturesEXT.SIZEOF);
            VulkanFeature mesh = new VulkanFeature(meshStruct, "meshShader",
                    VkPhysicalDeviceMeshShaderFeaturesEXT.MESHSHADER);
            VulkanFeature indirectCount = new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT,
                    "drawIndirectCount", VkPhysicalDeviceVulkan12Features.DRAWINDIRECTCOUNT);
            // Nicht genutzte Slang-Module (Denoise/LOD) schreiben RWTextures ohne Format
            VulkanFeature storageRead = new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT,
                    "shaderStorageImageReadWithoutFormat",
                    VkPhysicalDeviceFeatures.SHADERSTORAGEIMAGEREADWITHOUTFORMAT);
            VulkanFeature storageWrite = new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT,
                    "shaderStorageImageWriteWithoutFormat",
                    VkPhysicalDeviceFeatures.SHADERSTORAGEIMAGEWRITEWITHOUTFORMAT);
            // Glas/Eis: der Fragment-Shader haengt Fragmente per Atomik in Pro-Pixel-Listen
            VulkanFeature fragmentStores = new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT,
                    "fragmentStoresAndAtomics", VkPhysicalDeviceFeatures.FRAGMENTSTORESANDATOMICS);
            // GPU-Worldgen: Noise-Koordinaten in double wie Vanilla
            VulkanFeature float64 = new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT,
                    "shaderFloat64", VkPhysicalDeviceFeatures.SHADERFLOAT64);

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
                if (!mesh.get(query) || !fragmentStores.get(query)) {
                    LOG.warn("[vulkanfish] meshShader/fragmentStoresAndAtomics nicht unterstuetzt – Vanilla rendert");
                    return;
                }
                extensions.add(EXT_MESH);
                extensions.add(EXT_SPIRV_14);
                features.add(mesh);
                features.add(fragmentStores);
                meshEnabled = true;
                if (indirectCount.get(query)) {
                    features.add(indirectCount);
                    indirectCountEnabled = true;
                }
                if (float64.get(query)) {
                    features.add(float64);
                    float64Enabled = true;
                }
                if (storageRead.get(query)) features.add(storageRead);
                if (storageWrite.get(query)) features.add(storageWrite);
                augmentRayQuery(extensions, physicalDevice, features, fAs, fRq, f12, arena);
            }
            LOG.info("[vulkanfish] Mojang-Device erweitert: {} + {} (drawIndirectCount={})",
                    EXT_MESH, EXT_SPIRV_14, indirectCountEnabled);
        } catch (Throwable t) {
            meshEnabled = false;
            indirectCountEnabled = false;
            rayQueryEnabled = false;
            LOG.warn("[vulkanfish] Device-Erweiterung fehlgeschlagen – Vanilla rendert", t);
        }
    }

    /**
     * Hardware-Raytracing fuer die Blocklicht-Schatten: Ray Queries aus dem Deferred-
     * Compute gegen BLAS, die direkt aus unserem Vertexpuffer gebaut werden (R16G16B16_UNORM,
     * Stride 16). Ohne eines der Teile bleibt es beim Vanilla-Blocklicht.
     */
    private static void augmentRayQuery(Collection<String> extensions, VulkanPhysicalDevice physicalDevice,
                                        Set<VulkanFeature> features, VkPhysicalDeviceAccelerationStructureFeaturesKHR fAs,
                                        VkPhysicalDeviceRayQueryFeaturesKHR fRq, VkPhysicalDeviceVulkan12Features f12,
                                        Arena arena) {
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
        VulkanPNextStruct asStruct = new VulkanPNextStruct(
                KHRAccelerationStructure.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR,
                VkPhysicalDeviceAccelerationStructureFeaturesKHR.SIZEOF);
        VulkanPNextStruct rqStruct = new VulkanPNextStruct(
                KHRRayQuery.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR,
                VkPhysicalDeviceRayQueryFeaturesKHR.SIZEOF);
        extensions.add(EXT_AS);
        extensions.add(EXT_RAY_QUERY);
        extensions.add(EXT_DEFERRED_HOST);
        features.add(new VulkanFeature(asStruct, "accelerationStructure",
                VkPhysicalDeviceAccelerationStructureFeaturesKHR.ACCELERATIONSTRUCTURE));
        features.add(new VulkanFeature(rqStruct, "rayQuery", VkPhysicalDeviceRayQueryFeaturesKHR.RAYQUERY));
        features.add(new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, "bufferDeviceAddress",
                VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS));
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
