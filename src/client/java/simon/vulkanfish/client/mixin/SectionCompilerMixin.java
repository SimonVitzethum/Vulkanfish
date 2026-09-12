package simon.vulkanfish.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import simon.vulkanfish.client.gpu.VulkanfishRenderer;

/**
 * Solange der GPU-driven Pfad das Terrain liefert, meshet Vanilla keine Block-
 * und Fluid-Geometrie mehr (Opaque, Wasser, Glas/Eis zeichnen wir): kein
 * doppeltes CPU-Meshing, kein doppelter VRAM. Sichtbarkeit (VisGraph) und
 * Block-Entities berechnet Vanilla weiterhin. Faellt der native Pfad aus, schaltet der
 * Renderer das ab und laesst Vanilla alles neu kompilieren.
 */
@Mixin(SectionCompiler.class)
public class SectionCompilerMixin {
    @WrapOperation(method = "compile", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V"))
    private void vulkanfish$skipVanillaBlocks(ModelBlockRenderer renderer, BlockQuadOutput output, float x, float y, float z,
                                                  BlockAndTintGetter level, BlockPos pos, BlockState state,
                                                  BlockStateModel model, long seed, Operation<Void> original) {
        // Aktiver GPU-driven Pfad: alle Block-Quads (auch Glas/Eis) kommen von uns
        if (!VulkanfishRenderer.vanillaOpaqueDisabled()) {
            original.call(renderer, output, x, y, z, level, pos, state, model, seed);
        }
    }

    @WrapOperation(method = "compile", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/FluidRenderer;tesselate(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V"))
    private void vulkanfish$skipVanillaFluids(FluidRenderer renderer, BlockAndTintGetter level, BlockPos pos,
                                                  FluidRenderer.Output output, BlockState state, FluidState fluid,
                                                  Operation<Void> original) {
        if (!VulkanfishRenderer.vanillaOpaqueDisabled()) {
            original.call(renderer, level, pos, output, state, fluid);
        }
        // sonst: Lava und Wasser liefert der GPU-driven Pfad (G-Buffer bzw. Wasser-Pass)
    }
}
