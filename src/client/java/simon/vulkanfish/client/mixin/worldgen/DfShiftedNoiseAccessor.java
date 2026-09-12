package simon.vulkanfish.client.mixin.worldgen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lesezugriff fuer den GPU-Worldgen-Compiler (DensityFunction-Baum -> GPU-Programm). */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$ShiftedNoise")
public interface DfShiftedNoiseAccessor {
    @Accessor("shiftX")
    net.minecraft.world.level.levelgen.DensityFunction vf$ShiftX();

    @Accessor("shiftY")
    net.minecraft.world.level.levelgen.DensityFunction vf$ShiftY();

    @Accessor("shiftZ")
    net.minecraft.world.level.levelgen.DensityFunction vf$ShiftZ();

    @Accessor("xzScale")
    double vf$XzScale();

    @Accessor("yScale")
    double vf$YScale();

    @Accessor("noise")
    net.minecraft.world.level.levelgen.DensityFunction.NoiseHolder vf$Noise();
}
