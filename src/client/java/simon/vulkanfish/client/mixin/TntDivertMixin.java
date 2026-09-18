package simon.vulkanfish.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.TntRenderer;
import net.minecraft.client.renderer.entity.state.TntRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TNT in die eigene Entity-Pipeline (instanced): Zustand aufzeichnen, Vanilla-Submit
 * ueberspringen (keine Expansion, kein Staging, keine Draws). Virtuelle Methode (Override) –
 * produktionsfest; require = 0 (Optimierung crasht nie). Bereit-Pruefung + Fallback in
 * EntityInstancing (Vanilla zeichnet, wenn Pipeline/Bake/Modell fehlt).
 */
@Mixin(TntRenderer.class)
public class TntDivertMixin {
    @Inject(method = "submit", at = @At("HEAD"), cancellable = true, require = 0)
    private void vulkanfish$divertTnt(TntRenderState state, PoseStack poseStack,
                                      SubmitNodeCollector submitNodeCollector, CameraRenderState camera,
                                      CallbackInfo ci) {
        if (simon.vulkanfish.client.render.EntityInstancing.divertTnt(state)) ci.cancel();
    }
}
