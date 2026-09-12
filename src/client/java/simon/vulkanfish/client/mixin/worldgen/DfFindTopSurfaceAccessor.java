package simon.vulkanfish.client.mixin.worldgen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lesezugriff fuer den GPU-Worldgen-Compiler (DensityFunction-Baum -> GPU-Programm). */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$FindTopSurface")
public interface DfFindTopSurfaceAccessor {
    @Accessor("density")
    net.minecraft.world.level.levelgen.DensityFunction vf$Density();

    @Accessor("upperBound")
    net.minecraft.world.level.levelgen.DensityFunction vf$UpperBound();

    @Accessor("lowerBound")
    int vf$LowerBound();

    @Accessor("cellHeight")
    int vf$CellHeight();
}
