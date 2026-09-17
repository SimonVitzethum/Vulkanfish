package simon.vulkanfish.client.mixin;

import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import simon.vulkanfish.client.gpu.TerrainStreamer;

/**
 * Trichter aller Section-Invalidierungen (Blockaenderung, Licht, Chunk-Laden):
 * dieselben Sections, die Vanilla neu kompiliert, meshen wir neu.
 *
 * <p>Zusaetzlich Entity-Staffelung (siehe EntityStagger): isEntityVisible ist Vanillas
 * Sichtbarkeits-Tor pro Entity – ferne Entities ueberspringen dort Extract + Submit + Draw.
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {
    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
    private void vulkanfish$sectionDirty(int sectionX, int sectionY, int sectionZ, boolean playerChanged, CallbackInfo ci) {
        TerrainStreamer.markDirty(sectionX, sectionY, sectionZ);
    }

    @Inject(method = "extractVisibleEntities", at = @At("HEAD"))
    private void vulkanfish$staggerNextFrame(net.minecraft.client.Camera camera,
            net.minecraft.client.renderer.culling.Frustum frustum,
            net.minecraft.client.DeltaTracker deltaTracker,
            net.minecraft.client.renderer.state.level.LevelRenderState state, CallbackInfo ci) {
        simon.vulkanfish.client.render.EntityStagger.nextFrame();
    }

    @Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
    private void vulkanfish$staggerFarEntities(net.minecraft.world.entity.Entity entity,
            net.minecraft.client.renderer.culling.Frustum frustum, double x, double y, double z,
            CallbackInfoReturnable<Boolean> cir) {
        if (!simon.vulkanfish.client.render.EntityStagger.visible(entity)) cir.setReturnValue(false);
    }
}
