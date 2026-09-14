package simon.vulkanfish.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import simon.vulkanfish.client.gpu.FgPresenter;

/**
 * DLSS Frame Generation: bei aktiver FG holt Mojang weder Swapchain-Bilder noch praesentiert es;
 * das uebernimmt der Present-Thread des FgPresenter (Zwischenbilder + echtes Bild, gleichmaessig
 * getaktet). Mojangs Present laeuft unter der gemeinsamen Queue-Sperre.
 */
@Mixin(VulkanGpuSurface.class)
public class VulkanGpuSurfaceMixin {
    @Inject(method = "acquireNextTexture", at = @At("HEAD"), cancellable = true)
    private void vulkanfish$acquire(CallbackInfo ci) throws SurfaceException {
        FgPresenter fp = FgPresenter.instance();
        if (fp == null) return;
        VulkanGpuSurfaceAccessor self = (VulkanGpuSurfaceAccessor) (Object) this;
        if (self.vulkanfish$outOfDate()) {
            // vom Present-Thread gesetzt: Minecraft legt die Swapchain im naechsten Frame neu an
            throw new SurfaceException("Swapchain out of date (DLSS Frame Generation)");
        }
        if (fp.onAcquire(self)) ci.cancel();
    }

    @Inject(method = "blitFromTexture", at = @At("HEAD"), cancellable = true)
    private void vulkanfish$blit(CommandEncoderBackend encoder, GpuTextureView view, CallbackInfo ci) {
        FgPresenter fp = FgPresenter.instance();
        if (fp == null || !fp.frameActive()) return;
        fp.onBlit(encoder, view);
        ci.cancel();
    }

    @Inject(method = "present", at = @At("HEAD"), cancellable = true)
    private void vulkanfish$present(CallbackInfo ci) {
        FgPresenter fp = FgPresenter.instance();
        if (fp == null || !fp.frameActive()) return;
        fp.onPresent((VulkanGpuSurfaceAccessor) (Object) this);
        ci.cancel();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void vulkanfish$close(CallbackInfo ci) {
        FgPresenter fp = FgPresenter.instance();
        if (fp != null) fp.quiesce();
    }

    @WrapMethod(method = "configure")
    private void vulkanfish$configure(GpuSurface.Configuration config, Operation<Void> original) {
        FgPresenter fp = FgPresenter.instance();
        if (fp == null) {
            original.call(config);
            return;
        }
        fp.beforeConfigure();
        try {
            original.call(config);
        } finally {
            fp.afterConfigure();
        }
    }

    @WrapOperation(method = "present", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"))
    private int vulkanfish$lockPresent(VkQueue queue, VkPresentInfoKHR info, Operation<Integer> original) {
        FgPresenter.QUEUE_LOCK.lock();
        try {
            return original.call(queue, info);
        } finally {
            FgPresenter.QUEUE_LOCK.unlock();
        }
    }
}
