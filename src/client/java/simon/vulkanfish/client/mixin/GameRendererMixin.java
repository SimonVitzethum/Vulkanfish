package simon.vulkanfish.client.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import simon.vulkanfish.client.gpu.FrameDataCapture;

/**
 * Greift die ECHTE Level-Projektion ab (inkl. View-Bobbing/Nausea), damit
 * unsere Tiefe bitgenau zu Vanillas Entities/Partikeln passt, und setzt den
 * TAA-Jitter (in-place auf Vanillas lokaler Kopie).
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @ModifyArg(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"),
            index = 0)
    private Matrix4f vulkanfish$captureProjection(Matrix4f projection) {
        // Unveraendert merken (TAA-Reprojektion), dann Subpixel-Jitter fuer ALLE Level-Draws
        // (unser Terrain + Vanillas Entities/Wasser-Overlays/Wolken) -> konsistente Tiefe.
        FrameDataCapture.setLevelProjection(projection);
        return projection;
    }
}
