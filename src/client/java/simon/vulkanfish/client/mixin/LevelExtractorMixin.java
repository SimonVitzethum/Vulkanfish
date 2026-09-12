package simon.vulkanfish.client.mixin;

import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import simon.vulkanfish.client.gpu.TerrainStreamer;

/**
 * Trichter aller Section-Invalidierungen (Blockaenderung, Licht, Chunk-Laden):
 * dieselben Sections, die Vanilla neu kompiliert, meshen wir neu.
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {
    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
    private void vulkanfish$sectionDirty(int sectionX, int sectionY, int sectionZ, boolean playerChanged, CallbackInfo ci) {
        TerrainStreamer.markDirty(sectionX, sectionY, sectionZ);
    }
}
