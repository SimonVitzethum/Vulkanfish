package simon.vulkanfish.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import simon.vulkanfish.client.render.BreakingOverlayDefer;

/**
 * Riss-Overlay (Abbau-Fortschritt) auf transluzenten Bloecken erst NACH unserem Wasser-/Glas-
 * Pass zeichnen. Vanilla zeichnet es vor dem transluzenten Terrain; bei uns landete es dann in
 * der Szenen-Kopie, die das Wasser bricht und einfaerbt, und das Eis darueber verdeckte es.
 */
@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
public abstract class PreparedFrameMixin {
    @Shadow
    private @Nullable SubmitNodeStorage submitNodeStorage;

    @WrapOperation(method = "executeTranslucent", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executePhase(Lnet/minecraft/client/renderer/feature/phase/FeatureRenderPhase;Lnet/minecraft/client/renderer/feature/FeatureFrameContext;)V"))
    private void vulkanfish$deferBreakingOverlay(FeatureRenderDispatcher.PreparedFrame self, FeatureRenderPhase<?> phase,
                                                 FeatureFrameContext context, Operation<Void> original) {
        if (BreakingOverlayDefer.active() && vulkanfish$isBreakingOverlay(phase)) {
            BreakingOverlayDefer.add(self, phase, context);
            return;
        }
        // Vanillas runde Entity-Schattenflecken: mit echten Sonnenschatten (Entities in der
        // Schattenkarte) doppelt -> weglassen, solange unser Renderer zeichnet
        if (BreakingOverlayDefer.nativeActive() && vulkanfish$isShadowBlobs(phase)) return;
        original.call(self, phase, context);
    }

    private boolean vulkanfish$isShadowBlobs(FeatureRenderPhase<?> phase) {
        if (submitNodeStorage == null) return false;
        for (SubmitNodeCollection c : submitNodeStorage.getSubmitsPerOrder().values()) {
            if (c.shadows == phase) return true;
        }
        return false;
    }

    private boolean vulkanfish$isBreakingOverlay(FeatureRenderPhase<?> phase) {
        if (submitNodeStorage == null) return false;
        for (SubmitNodeCollection c : submitNodeStorage.getSubmitsPerOrder().values()) {
            if (c.breakingOverlay == phase) return true;
        }
        return false;
    }
}
