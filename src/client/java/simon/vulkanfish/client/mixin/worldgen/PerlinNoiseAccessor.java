package simon.vulkanfish.client.mixin.worldgen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lesezugriff fuer den GPU-Worldgen-Compiler (DensityFunction-Baum -> GPU-Programm). */
@Mixin(net.minecraft.world.level.levelgen.synth.PerlinNoise.class)
public interface PerlinNoiseAccessor {
    @Accessor("noiseLevels")
    net.minecraft.world.level.levelgen.synth.ImprovedNoise[] vf$NoiseLevels();

    @Accessor("amplitudes")
    it.unimi.dsi.fastutil.doubles.DoubleList vf$Amplitudes();

    @Accessor("lowestFreqInputFactor")
    double vf$LowestFreqInputFactor();

    @Accessor("lowestFreqValueFactor")
    double vf$LowestFreqValueFactor();
}
