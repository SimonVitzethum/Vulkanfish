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
    private long vulkanfish$lastLightSig = Long.MIN_VALUE;

    @Inject(method = "render", at = @At("TAIL"))
    private void vulkanfish$ourLight(LightmapRenderState state, CallbackInfo ci) {
        var d = FrameDataCapture.last;
        if (d == null || !VulkanfishRenderer.vanillaOpaqueDisabled() || state.darknessEffectScale > 0.0f
                || state.nightVisionEffectIntensity > 0.0f && simon.vulkanfish.client.VulkanfishSettings.fullbright() <= 0.0f) {
            vulkanfish$lastLightSig = Long.MIN_VALUE; // Effektwechsel -> danach sicher neu hochladen
            return; // Dunkelheits-/Nachtsicht-Effekte (Trank, Warden) bleiben Vanillas
        }
        // 16x16-Upload nur bei geaenderten Eingaben (Eckwerte sind zeitlich glatt, meist statisch)
        long sig = signature(d);
        if (sig == vulkanfish$lastLightSig) return;
        vulkanfish$lastLightSig = sig;
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, EntityLightmap.compute(d), 0, 0, 0, 0, 16, 16);
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
