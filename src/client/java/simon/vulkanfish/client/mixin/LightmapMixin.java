package simon.vulkanfish.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import simon.vulkanfish.client.gpu.FrameDataCapture;
import simon.vulkanfish.client.gpu.VulkanfishRenderer;
import simon.vulkanfish.client.render.EntityLightmap;

/** Solange unser Renderer das Terrain zeichnet: Lightmap im Vulkanfish-Look (EntityLightmap). */
@Mixin(Lightmap.class)
public abstract class LightmapMixin {
    @Shadow @Final private GpuTexture texture;

    @Inject(method = "render", at = @At("TAIL"))
    private void vulkanfish$ourLight(LightmapRenderState state, CallbackInfo ci) {
        var d = FrameDataCapture.last;
        if (d == null || !VulkanfishRenderer.vanillaOpaqueDisabled() || state.darknessEffectScale > 0.0f
                || state.nightVisionEffectIntensity > 0.0f && simon.vulkanfish.client.VulkanfishSettings.fullbright() <= 0.0f) {
            return; // Dunkelheits-/Nachtsicht-Effekte (Trank, Warden) bleiben Vanillas
        }
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, EntityLightmap.compute(d), 0, 0, 0, 0, 16, 16);
    }
}
