package simon.vulkanfish.client.mixin;

import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import simon.vulkanfish.client.VulkanfishClient;

/**
 * Haengt den GPU-driven Terrain-Frame VOR Vanillas OPAQUE-Gruppe ein. Laeuft
 * im "main"-Pass des Frame-Graphs: Himmel ist schon im Main-Target. Danach
 * zeichnet Vanilla nur noch die Opaque-Sections, die wir nicht abdecken
 * (LevelRendererMixin filtert), sowie Entities, Partikel und TRANSLUCENT –
 * alles gegen unsere Tiefe im Main-Depth. Vor der TRANSLUCENT-Gruppe laeuft
 * unser Wasser-Pass (Entities unter Wasser sind dann schon im Bild).
 */
@Mixin(ChunkSectionsToRender.class)
public abstract class ChunkSectionsToRenderMixin {
    @Shadow
    public abstract GpuTextureView textureView();

    @Inject(method = "renderGroup", at = @At("HEAD"))
    private void vulkanfish$renderGpuDrivenTerrain(ChunkSectionLayerGroup group, GpuSampler sampler, CallbackInfo ci) {
        if (VulkanfishClient.RENDERER == null) return;
        if (group == ChunkSectionLayerGroup.OPAQUE) {
            VulkanfishClient.RENDERER.renderOpaqueTerrain(textureView());
        } else if (group == ChunkSectionLayerGroup.TRANSLUCENT) {
            // Nach Entities/transluzenten Features, vor Vanillas Glas/Eis: unser Wasser
            VulkanfishClient.RENDERER.renderWater();
            // Risse auf Eis/Glas erst jetzt, ueber dem fertigen Wasser-/Glas-Bild
            simon.vulkanfish.client.render.BreakingOverlayDefer.flush();
        }
    }
}
