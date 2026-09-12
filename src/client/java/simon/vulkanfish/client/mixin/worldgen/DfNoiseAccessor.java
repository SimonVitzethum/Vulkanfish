package simon.vulkanfish.client.mixin.worldgen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lesezugriff fuer den GPU-Worldgen-Compiler (DensityFunction-Baum -> GPU-Programm). */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$Noise")
public interface DfNoiseAccessor {
    @Accessor("noise")
    net.minecraft.world.level.levelgen.DensityFunction.NoiseHolder vf$Noise();

    @Accessor("xzScale")
    double vf$XzScale();

    @Accessor("yScale")
    double vf$YScale();
}
