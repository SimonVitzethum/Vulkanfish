package simon.vulkanfish.client.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import simon.vulkanfish.client.gpu.MeshShaderSupport;

/**
 * Mojangs VMA-Allocator mit Buffer-Device-Address anlegen, wenn Ray Queries aktiv sind:
 * BLAS-Builds lesen unseren (per VMA allokierten) Vertexpuffer ueber seine Device-Adresse.
 */
@Mixin(VulkanBackend.class)
public class VulkanBackendVmaMixin {
    @ModifyArg(method = "createVma",
            at = @At(value = "INVOKE",
                    target = "Lorg/lwjgl/util/vma/Vma;vmaCreateAllocator(Lorg/lwjgl/util/vma/VmaAllocatorCreateInfo;Lorg/lwjgl/PointerBuffer;)I"),
            index = 0)
    private static VmaAllocatorCreateInfo vulkanfish$bufferDeviceAddress(VmaAllocatorCreateInfo info) {
        if (MeshShaderSupport.rayQueryEnabled()) {
            info.flags(info.flags() | Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT);
        }
        return info;
    }
}
