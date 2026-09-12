package simon.vulkanfish.client.mixin.worldgen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lesezugriff fuer den GPU-Worldgen-Compiler (DensityFunction-Baum -> GPU-Programm). */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$YClampedGradient")
public interface DfYGradientAccessor {
    @Accessor("fromY")
    int vf$FromY();

    @Accessor("toY")
    int vf$ToY();

    @Accessor("fromValue")
    double vf$FromValue();

    @Accessor("toValue")
    double vf$ToValue();
}
