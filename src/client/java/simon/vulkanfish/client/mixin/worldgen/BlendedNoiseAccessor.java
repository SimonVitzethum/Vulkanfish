package simon.vulkanfish.client.mixin.worldgen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lesezugriff fuer den GPU-Worldgen-Compiler (DensityFunction-Baum -> GPU-Programm). */
@Mixin(net.minecraft.world.level.levelgen.synth.BlendedNoise.class)
public interface BlendedNoiseAccessor {
    @Accessor("minLimitNoise")
    net.minecraft.world.level.levelgen.synth.PerlinNoise vf$MinLimitNoise();

    @Accessor("maxLimitNoise")
    net.minecraft.world.level.levelgen.synth.PerlinNoise vf$MaxLimitNoise();

    @Accessor("mainNoise")
    net.minecraft.world.level.levelgen.synth.PerlinNoise vf$MainNoise();

    @Accessor("xzMultiplier")
    double vf$XzMultiplier();

    @Accessor("yMultiplier")
    double vf$YMultiplier();

    @Accessor("xzFactor")
    double vf$XzFactor();

    @Accessor("yFactor")
    double vf$YFactor();

    @Accessor("smearScaleMultiplier")
    double vf$SmearScaleMultiplier();
}
