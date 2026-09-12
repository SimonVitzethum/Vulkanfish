package simon.vulkanfish.client.mixin;

import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import simon.vulkanfish.client.VulkanfishSettings;

/**
 * Fullbright fuer alles, was Vanilla selbst beleuchtet (Entities, Hand, Partikel): die Lightmap
 * wie mit Nachtsicht, stufenlos nach der Einstellung. Unser Terrain hellt der Deferred-Pass auf.
 */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class LightmapExtractorMixin {
    @Inject(method = "extract", at = @At("TAIL"))
    private void vulkanfish$fullbright(LightmapRenderState state, float partialTicks, CallbackInfo ci) {
        float fb = VulkanfishSettings.fullbright();
        if (fb > 0.0f && state.nightVisionEffectIntensity < fb) {
            state.nightVisionEffectIntensity = fb;
            state.needsUpdate = true;
        }
    }
}
