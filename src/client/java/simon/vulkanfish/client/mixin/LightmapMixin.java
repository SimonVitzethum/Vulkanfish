package simon.vulkanfish.client.mixin;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
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

/** Solange unser Renderer das Terrain zeichnet: Lightmap im Vulkanfish-Look (GPU-Compute, 16x16). */
@Mixin(Lightmap.class)
public abstract class LightmapMixin {
    @Shadow @Final private GpuTexture texture;

    @Inject(method = "render", at = @At("TAIL"))
    private void vulkanfish$ourLight(LightmapRenderState state, CallbackInfo ci) {
        var d = FrameDataCapture.last;
        if (d == null || !VulkanfishRenderer.vanillaOpaqueDisabled() || state.darknessEffectScale > 0.0f
                || state.nightVisionEffectIntensity > 0.0f && simon.vulkanfish.client.VulkanfishSettings.fullbright() <= 0.0f) {
            VulkanfishRenderer.noteLightmap(0L, 0L); // Vanilla schreibt selbst (Effekte/Fallback)
            return; // Dunkelheits-/Nachtsicht-Effekte (Trank, Warden) bleiben Vanillas
        }
        // GPU rechnet + kopiert im Terrain-Frame (vor den Entity-Draws); nur Ziel + Signatur melden
        long img = texture instanceof VulkanGpuTexture vk ? vk.vkImage() : 0L;
        VulkanfishRenderer.noteLightmap(img, signature(d));
    }

    /** Hash aller Lightmap-Eingaben (float-Bits): gleich -> gleiche Textur, Upload entfaellt. */
    private static long signature(simon.vulkanfish.client.gpu.NativePassRunner.FrameUniformsData d) {
        long h = 1469598103934665603L;
        h = mix(h, Float.floatToIntBits(d.sunVisibility()));
        h = mix(h, Float.floatToIntBits(d.exposure()));
        h = mix(h, d.dimension());
        h = mix(h, Float.floatToIntBits(d.noonFactor()));
        h = mix(h, Float.floatToIntBits(d.moonBrightness()));
        h = mix(h, Float.floatToIntBits(d.rainFactor()));
        h = mix(h, Float.floatToIntBits(d.thunderFactor()));
        h = mix(h, Float.floatToIntBits(simon.vulkanfish.client.VulkanfishSettings.fullbright()));
        for (float v : d.skyColor()) h = mix(h, Float.floatToIntBits(v));
        for (float v : d.lightDir()) h = mix(h, Float.floatToIntBits(v));
        return h;
    }

    private static long mix(long h, long v) {
        return (h ^ (v + 0x9E3779B97F4A7C15L)) * 0x100000001B3L;
    }
}
