package simon.vulkanfish.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.FallingBlockRenderer;
import net.minecraft.client.renderer.entity.state.FallingBlockRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fallende Bloecke in die eigene Entity-Pipeline (instanced, Modell-Cache je BlockState):
 * wie TntDivertMixin – aufzeichnen statt expandieren. Transluzente Modelle (Glas) und
 * Nicht-Modell-Shapes bleiben bewusst bei Vanilla.
 */
@Mixin(FallingBlockRenderer.class)
public class FallingDivertMixin {
    @Inject(method = "submit", at = @At("HEAD"), cancellable = true, require = 0)
    private void vulkanfish$divertFalling(FallingBlockRenderState state, PoseStack poseStack,
                                          SubmitNodeCollector submitNodeCollector, CameraRenderState camera,
                                          CallbackInfo ci) {
        if (simon.vulkanfish.client.render.EntityInstancing.divertFalling(state)) ci.cancel();
    }
}
