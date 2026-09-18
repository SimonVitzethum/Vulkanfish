package simon.vulkanfish.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import simon.vulkanfish.client.gpu.RenderScale;

/** DLSS Super Resolution: Welt in ein kleineres Ziel (RenderScale), Hand/GUI in voller Aufloesung. */
@Mixin(GameRenderer.class)
public class GameRendererScaleMixin {
    @Shadow @Final private RenderTarget mainRenderTarget;

    @Inject(method = "mainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void vulkanfish$scaledTarget(CallbackInfoReturnable<RenderTarget> cir) {
        if (RenderScale.active()) cir.setReturnValue(RenderScale.redirect(mainRenderTarget));
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void vulkanfish$beginLevel(CallbackInfo ci) {
        RenderScale.beginLevel(mainRenderTarget);
    }

    /**
     * Vor Hand/GUI (26.3: eigene render3dHud-Methode): hat niemand hochskaliert
     * (Renderer aus), das kleine Bild notfalls aufziehen.
     */
    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
    private void vulkanfish$beforeHand(CallbackInfo ci) {
        if (!RenderScale.active()) return;
        RenderTarget low = RenderScale.redirect(mainRenderTarget);
        RenderScale.endLevel();
        simon.vulkanfish.client.gpu.VulkanfishRenderer.upscaleFallback(low, mainRenderTarget);
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void vulkanfish$endLevel(CallbackInfo ci) {
        RenderScale.endLevel();
    }
}
