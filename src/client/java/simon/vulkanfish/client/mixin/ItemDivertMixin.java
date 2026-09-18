package simon.vulkanfish.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Items in die eigene Entity-Pipeline (instanced, Modell-Cache je Art): Bob/Spin/Count-Layer
 * exakt nach Vanilla-Mathe, ein Draw pro Modell. Virtuelle Methode (Override) –
 * produktionsfest; require = 0 (Optimierung crasht nie). Sauberkeitspruefung + Fallback in
 * ItemBaker (Foil/Tints/transluzent/exotische Atlanten/Special bleiben Vanilla).
 */
@Mixin(ItemEntityRenderer.class)
public class ItemDivertMixin {
    @Inject(method = "submit", at = @At("HEAD"), cancellable = true, require = 0)
    private void vulkanfish$divertItem(ItemEntityRenderState state, PoseStack poseStack,
                                       SubmitNodeCollector submitNodeCollector, CameraRenderState camera,
                                       CallbackInfo ci) {
        if (simon.vulkanfish.client.render.ItemBaker.divertItem(state)) ci.cancel();
    }
}
