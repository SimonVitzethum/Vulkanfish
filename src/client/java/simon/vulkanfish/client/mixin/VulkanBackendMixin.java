package simon.vulkanfish.client.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import simon.vulkanfish.client.gpu.MeshShaderSupport;

/**
 * 26.3: Mojangs Device-Erzeugung laeuft ueber FeatureSets (statt loser Extension-/Feature-
 * Listen). Faehigkeiten abfragen (Flags fuer VMA/Renderer), bevor das Device genutzt wird.
 */
@Mixin(VulkanBackend.class)
public class VulkanBackendMixin {
    /** DLSS Frame Generation: eigene Present-Queue in Mojangs Grafik-Familie. */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
            method = "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;",
            at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;queueFamilyCreateInfoMap()Lit/unimi/dsi/fastutil/ints/Int2IntMap;"))
    private static it.unimi.dsi.fastutil.ints.Int2IntMap vulkanfish$presentQueue(VulkanPhysicalDevice physicalDevice,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<it.unimi.dsi.fastutil.ints.Int2IntMap> original) {
        return simon.vulkanfish.client.gpu.NgxBridge.withPresentQueue(original.call(physicalDevice), physicalDevice);
    }

    /** Flags setzen (VOR VMA-Erzeugung – deren Flags haengen an rayQueryEnabled). */
    @Inject(method = "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;",
            at = @At("HEAD"))
    private static void vulkanfish$probeDevice(FeatureSet enabledFeatures, VulkanPhysicalDevice physicalDevice,
                                               CallbackInfoReturnable<VkDevice> cir) {
        MeshShaderSupport.probeDevice(physicalDevice);
    }
}
