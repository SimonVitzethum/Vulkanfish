package simon.vulkanfish.client.gpu;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holt die nativen Vulkan-Handles aus Mojangs RenderPearl (kein VulkanMod noetig).
 *
 * <p>Pfad: {@code RenderSystem.getDevice()} -&gt; Backend ({@code VulkanDevice}) -&gt;
 * {@code vkDevice()} (LWJGL), dazu Graphics/Compute/Transfer-Queues und
 * {@code VulkanPhysicalDevice.hasDeviceExtension(..)} zum Faehigkeits-Check.
 * Alles mit Graceful-Fallback: fehlt etwas, laeuft der Vanilla-Pfad weiter.
 */
public final class BlazeDeviceInterop {
    private static final Logger LOG = LoggerFactory.getLogger("vulkanfish");

    public static final String EXT_MESH_SHADER = "VK_EXT_mesh_shader";
    public static final String EXT_DESCRIPTOR_INDEXING = "VK_EXT_descriptor_indexing";
    public static final String EXT_SHADER_DRAW_PARAMS = "VK_KHR_shader_draw_parameters";
    public static final String KHR_RAY_TRACING_PIPELINE = "VK_KHR_ray_tracing_pipeline";
    public static final String KHR_ACCELERATION_STRUCTURE = "VK_KHR_acceleration_structure";
    public static final String KHR_DEFERRED_HOST_OPS = "VK_KHR_deferred_host_operations";
    public static final String KHR_DRAW_INDIRECT_COUNT = "VK_KHR_draw_indirect_count";

    private VulkanDevice device;
    private com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice physical;
    private boolean meshShading;
    private boolean raytracing;
    private boolean indirectCount;
    private long lastWarnMs;

    /** Muss auf dem Render-Thread nach Device-Erstellung laufen. */
    public boolean probe() {
        try {
            GpuDevice gpu = RenderSystem.getDevice();
            if (gpu == null) {
                LOG.warn("[vulkanfish] Noch kein GpuDevice, Fallback Vanilla");
                return false;
            }
            // GpuDevice ist ein Interface – das Backend steckt privat in der
            // Laufzeitklasse (FrontendGpuDevice.backend, 26.3 javap-verifiziert).
            var backendField = gpu.getClass().getDeclaredField("backend");
            backendField.setAccessible(true);
            Object backend = backendField.get(gpu);
            if (!(backend instanceof VulkanDevice vk)) {
                long now = System.currentTimeMillis();
                if (now - lastWarnMs > 10_000) {
                    lastWarnMs = now;
                    LOG.warn("[vulkanfish] Kein VulkanDevice ({}), Fallback Vanilla – "
                                    + "Grafik-API in den Videoeinstellungen auf Vulkan stellen und neu starten",
                            backend == null ? "null" : backend.getClass().getName());
                }
                return false;
            }
            this.device = vk;
            var phys = getPhysicalDevice(vk);
            this.physical = phys;
            // Entscheidend ist, was auf Mojangs Device AKTIVIERT wurde (Mixin), nicht nur vorhanden ist
            meshShading = MeshShaderSupport.meshEnabled();
            raytracing = has(phys, KHR_RAY_TRACING_PIPELINE)
                    && has(phys, KHR_ACCELERATION_STRUCTURE)
                    && has(phys, KHR_DEFERRED_HOST_OPS);
            indirectCount = MeshShaderSupport.indirectCountEnabled();
            LOG.info("[vulkanfish] BlazeDevice: {} mesh={} rt={} indirectCount={}",
                    phys.deviceName(), meshShading, raytracing, indirectCount);
            return true;
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            if (now - lastWarnMs > 10_000) {
                lastWarnMs = now;
                LOG.warn("[vulkanfish] Device-Probe fehlgeschlagen, Fallback Vanilla", t);
            }
            return false;
        }
    }

    private static com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice getPhysicalDevice(VulkanDevice vk) {
        // Mojang speichert den Wrapper nirgends, aber Mojangs VkDevice kennt
        // sein physisches Device -> exakt die GPU, auf der wir rendern.
        try {
            return new com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice(vk.vkDevice().getPhysicalDevice());
        } catch (com.mojang.renderpearl.api.device.BackendCreationException e) {
            throw new IllegalStateException("PhysicalDevice-Wrapper fehlgeschlagen", e);
        }
    }

    private static boolean has(com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice phys, String ext) {
        try {
            return phys.hasDeviceExtension(ext);
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isReady() {
        return device != null;
    }

    public boolean supportsMeshShading() {
        return meshShading;
    }

    public boolean supportsRaytracing() {
        return raytracing;
    }

    public boolean supportsIndirectCount() {
        return indirectCount;
    }

    public VkDevice vkDevice() {
        return device.vkDevice();
    }

    /** Das physische Device, auf dem Mojangs VkDevice WIRKLICH laeuft (nicht neu enumeriert). */
    public VkPhysicalDevice vkPhysicalDevice() {
        return device.vkDevice().getPhysicalDevice();
    }

    /** Mojangs Command-Encoder: eigene Command-Buffer per execute() in die Frame-Submission haengen. */
    public com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder commandEncoder() {
        return device.createCommandEncoder();
    }

    public VkInstance vkInstance() {
        return device.instance().vkInstance();
    }

    public long vmaAllocator() {
        return device.vma();
    }

    public VkQueue graphicsQueue() {
        return device.graphicsQueue().vkQueue();
    }

    public int graphicsQueueFamily() {
        return device.graphicsQueue().queueFamilyIndex();
    }

    public VkQueue computeQueue() {
        return device.computeQueue().vkQueue();
    }

    public int computeQueueFamily() {
        return device.computeQueue().queueFamilyIndex();
    }

    public VkQueue transferQueue() {
        return device.transferQueue().vkQueue();
    }

    public int transferQueueFamily() {
        return device.transferQueue().queueFamilyIndex();
    }
}
