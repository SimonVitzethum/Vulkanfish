package simon.vulkanfish.client.mixin.worldgen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lesezugriff fuer den GPU-Worldgen-Compiler (DensityFunction-Baum -> GPU-Programm). */
@Mixin(net.minecraft.world.level.levelgen.synth.NormalNoise.class)
public interface NormalNoiseAccessor {
    @Accessor("first")
    net.minecraft.world.level.levelgen.synth.PerlinNoise vf$First();

    @Accessor("second")
    net.minecraft.world.level.levelgen.synth.PerlinNoise vf$Second();

    @Accessor("valueFactor")
    double vf$ValueFactor();
}
