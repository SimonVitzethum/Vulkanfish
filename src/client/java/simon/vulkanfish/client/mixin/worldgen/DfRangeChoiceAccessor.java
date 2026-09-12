package simon.vulkanfish.client.mixin.worldgen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lesezugriff fuer den GPU-Worldgen-Compiler (DensityFunction-Baum -> GPU-Programm). */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$RangeChoice")
public interface DfRangeChoiceAccessor {
    @Accessor("input")
    net.minecraft.world.level.levelgen.DensityFunction vf$Input();

    @Accessor("minInclusive")
    double vf$MinInclusive();

    @Accessor("maxExclusive")
    double vf$MaxExclusive();

    @Accessor("whenInRange")
    net.minecraft.world.level.levelgen.DensityFunction vf$WhenInRange();

    @Accessor("whenOutOfRange")
    net.minecraft.world.level.levelgen.DensityFunction vf$WhenOutOfRange();
}
