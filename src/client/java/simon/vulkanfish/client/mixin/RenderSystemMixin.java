package simon.vulkanfish.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import simon.vulkanfish.client.VulkanfishClient;

/**
 * Eigene Vulkan-Objekte freigeben, BEVOR Mojang Device + Instance zerstoert
 * (sonst: Validierungs-Leaks bzw. SIGSEGV im Treiber bei vkDestroyInstance).
 */
@Mixin(RenderSystem.class)
public class RenderSystemMixin {
    @Inject(method = "shutdownRenderer", at = @At("HEAD"))
    private static void vulkanfish$shutdown(CallbackInfo ci) {
        if (VulkanfishClient.RENDERER != null) {
            VulkanfishClient.RENDERER.shutdown();
        }
    }
}
