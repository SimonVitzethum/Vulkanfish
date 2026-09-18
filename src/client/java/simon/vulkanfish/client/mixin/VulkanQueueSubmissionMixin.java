package simon.vulkanfish.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSubmitInfo2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import simon.vulkanfish.client.gpu.FgPresenter;

/** Mojangs Queue-Submits unter der gemeinsamen Queue-Sperre (Present-Thread der Frame Generation). */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanQueue$Submission")
public class VulkanQueueSubmissionMixin {
    @WrapOperation(method = "close", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/KHRSynchronization2;vkQueueSubmit2KHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkSubmitInfo2$Buffer;J)I"))
    private int vulkanfish$lockSubmit(VkQueue queue, VkSubmitInfo2.Buffer submits, long fence, Operation<Integer> original) {
        FgPresenter.QUEUE_LOCK.lock();
        try {
            return original.call(queue, submits, fence);
        } finally {
            FgPresenter.QUEUE_LOCK.unlock();
        }
    }
}
