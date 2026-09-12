package simon.vulkanfish.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import simon.vulkanfish.client.VulkanfishClient;

/**
 * Einstiegspunkt pro Frame: ruft den GPU-driven Pfad auf dem Render-Thread
 * auf (Device existiert hier garantiert) und reicht Vanillas Kamera- +
 * Himmels-State weiter – keine doppelte Matrizen-/Himmels-Logik auf CPU.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    @Shadow
    private LevelRenderState levelRenderState;

    @Inject(method = "render", at = @At("HEAD"))
    private void vulkanfish$onFrameStart(GraphicsResourceAllocator allocator,
                                         DeltaTracker deltaTracker,
                                         boolean renderBlockOutline,
                                         CameraRenderState cameraState,
                                         Matrix4fc viewMatrix,
                                         GpuBufferSlice projectionSlice,
                                         Vector4f fogColor,
                                         boolean detailedSky,
                                         CallbackInfo ci) {
        if (VulkanfishClient.RENDERER != null) {
            VulkanfishClient.RENDERER.onFrameStart(cameraState,
                    levelRenderState != null ? levelRenderState.skyRenderState : null);
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void vulkanfish$onFrameEnd(CallbackInfo ci) {
        if (VulkanfishClient.RENDERER != null) VulkanfishClient.RENDERER.onFrameEnd();
    }

    /** Overworld-Himmel zeichnet der Deferred-Pass selbst (Verlauf, Sonne, Mond, Sterne). */
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private boolean vulkanfish$skipVanillaSky(boolean shouldRenderSky) {
        return shouldRenderSky && (VulkanfishClient.RENDERER == null || !VulkanfishClient.RENDERER.replacesSky());
    }

    /**
     * Hybrid: Vanilla zeichnet SOLID/CUTOUT nur noch fuer Sections, die (noch)
     * nicht in unserer GPU-Scene liegen (frisch sichtbar, Scene voll, Fallback).
     * TRANSLUCENT bleibt immer bei Vanilla.
     */
    @WrapOperation(method = "prepareChunkRenders",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/SectionMesh;getSectionDraw(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;)Lnet/minecraft/client/renderer/chunk/SectionMesh$SectionDraw;"))
    private SectionMesh.SectionDraw vulkanfish$skipCoveredSections(SectionMesh mesh, ChunkSectionLayer layer,
                                                                  Operation<SectionMesh.SectionDraw> original,
                                                                  @Local SectionRenderDispatcher.RenderSection section) {
        if (VulkanfishClient.RENDERER != null
                && VulkanfishClient.RENDERER.coversSection(section.getSectionNode())) {
            return null;
        }
        return original.call(mesh, layer);
    }
}
