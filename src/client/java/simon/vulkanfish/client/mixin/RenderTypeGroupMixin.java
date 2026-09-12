package simon.vulkanfish.client.mixin;

import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import simon.vulkanfish.client.render.EntityShadowCapture;

/** Jede Zeichnung im geteilten Vertexpuffer mit ihrem RenderType melden (Entity-Schatten). */
@Mixin(targets = "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group")
public abstract class RenderTypeGroupMixin {
    @Inject(method = "getOrAddDraw", at = @At("RETURN"))
    private void vulkanfish$recordDraw(RenderType renderType, CallbackInfoReturnable<StagedVertexBuffer.Draw> cir) {
        EntityShadowCapture.onDraw(cir.getReturnValue(), renderType);
    }
}
