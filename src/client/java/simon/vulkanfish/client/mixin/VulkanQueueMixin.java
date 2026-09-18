package simon.vulkanfish.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import org.lwjgl.vulkan.VkQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import simon.vulkanfish.client.gpu.FgPresenter;

/** vkQueueWaitIdle unter der gemeinsamen Queue-Sperre (Present-Thread der Frame Generation). */
@Mixin(VulkanQueue.class)
public class VulkanQueueMixin {
    @WrapOperation(method = "waitIdle", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkQueueWaitIdle(Lorg/lwjgl/vulkan/VkQueue;)I"))
    private int vulkanfish$lockWaitIdle(VkQueue queue, Operation<Integer> original) {
        FgPresenter.QUEUE_LOCK.lock();
        try {
            return original.call(queue);
        } finally {
            FgPresenter.QUEUE_LOCK.unlock();
        }
    }
}
