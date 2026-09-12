package simon.vulkanfish.client.mixin.worldgen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lesezugriff fuer den GPU-Worldgen-Compiler (DensityFunction-Baum -> GPU-Programm). */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$Clamp")
public interface DfClampAccessor {
    @Accessor("input")
    net.minecraft.world.level.levelgen.DensityFunction vf$Input();

    @Accessor("minValue")
    double vf$MinValue();

    @Accessor("maxValue")
    double vf$MaxValue();
}
