package simon.vulkanfish.client.mixin.worldgen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lesezugriff fuer den GPU-Worldgen-Compiler (DensityFunction-Baum -> GPU-Programm). */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$IntervalSelect")
public interface DfIntervalSelectAccessor {
    @Accessor("input")
    net.minecraft.world.level.levelgen.DensityFunction vf$Input();

    @Accessor("thresholds")
    it.unimi.dsi.fastutil.doubles.DoubleList vf$Thresholds();

    @Accessor("functions")
    java.util.List<net.minecraft.world.level.levelgen.DensityFunction> vf$Functions();
}
