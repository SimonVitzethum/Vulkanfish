package simon.vulkanfish.client.mixin;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import java.util.Collection;
import java.util.Set;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import simon.vulkanfish.client.gpu.MeshShaderSupport;

/**
 * Ergaenzt Mojangs Extension-/Feature-Sets (mutable HashSet/ObjectOpenHashSet)
 * unmittelbar vor vkCreateDevice um Mesh-Shader + drawIndirectCount.
 */
@Mixin(VulkanBackend.class)
public class VulkanBackendMixin {
    @Inject(method = "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;",
            at = @At("HEAD"))
    private static void vulkanfish$enableMeshShaders(Collection<String> deviceExtensions,
                                                     VulkanPhysicalDevice physicalDevice,
                                                     Set<VulkanFeature> vulkanFeatures,
                                                     CallbackInfoReturnable<VkDevice> cir) {
        MeshShaderSupport.augment(deviceExtensions, physicalDevice, vulkanFeatures);
    }
}
