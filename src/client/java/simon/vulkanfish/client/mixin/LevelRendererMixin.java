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
import org.spongepowered.asm.mixin.injection.Redirect;
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

    /** Entity-Schatten: welche Zeichnungen der Welt Schatten werfen (siehe EntityShadowCapture). */
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"))
    private net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame vulkanfish$captureEntityDraws(
            net.minecraft.client.renderer.feature.FeatureRenderDispatcher dispatcher, net.minecraft.client.renderer.SubmitNodeStorage storage,
            Operation<net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame> original) {
        simon.vulkanfish.client.render.EntityShadowCapture.begin();
        // Profil: Vanillas Submission (alle Entity-/Partikel-Submits in den Staged-Puffer)
        long t0 = System.nanoTime();
        var frame = original.call(dispatcher, storage);
        if (VulkanfishClient.RENDERER != null) VulkanfishClient.RENDERER.addSubmitNanos(System.nanoTime() - t0);
        simon.vulkanfish.client.render.EntityShadowCapture.end(
                ((FeatureRenderDispatcherAccessor) dispatcher).vulkanfish$stagedVertexBuffer());
        return frame;
    }

    /** Abbau-Animation: auf Eis/Glas das Riss-Overlay hinter unseren Wasser-/Glas-Pass schieben. */
    @Inject(method = "submitBlockDestroyAnimation", at = @At("HEAD"))
    private void vulkanfish$breakingOverlayOrder(com.mojang.blaze3d.vertex.PoseStack poseStack,
                                                 net.minecraft.client.renderer.SubmitNodeCollector collector,
                                                 LevelRenderState state, CallbackInfo ci) {
        simon.vulkanfish.client.render.BreakingOverlayDefer.onSubmit(state.blockBreakingRenderStates,
                VulkanfishClient.RENDERER != null && VulkanfishClient.RENDERER.useGpuDrivenPath());
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
     * Hybrid: Vanilla zeichnet nur noch Sections, die (noch) nicht in unserer GPU-Scene liegen
     * (frisch sichtbar, Scene voll, Fallback). Einmal je Section entschieden (leeres Mesh ->
     * alle Schichten fallen weg) statt je Section und Schicht: das waren zehntausende
     * Aufrufe samt Wrapper-Objekten pro Frame.
     */
    @Redirect(method = "prepareChunkRenders", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;getSectionMesh()Lnet/minecraft/client/renderer/chunk/SectionMesh;"))
    private SectionMesh vulkanfish$skipCoveredSections(SectionRenderDispatcher.RenderSection section) {
        if (VulkanfishClient.RENDERER != null && VulkanfishClient.RENDERER.coversSection(section.getSectionNode())) {
            return net.minecraft.client.renderer.chunk.CompiledSectionMesh.EMPTY;
        }
        return section.getSectionMesh();
    }
}
