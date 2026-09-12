package simon.vulkanfish.client.mixin.worldgen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lesezugriff fuer den GPU-Worldgen-Compiler (DensityFunction-Baum -> GPU-Programm). */
@Mixin(net.minecraft.world.level.levelgen.synth.ImprovedNoise.class)
public interface ImprovedNoiseAccessor {
    @Accessor("p")
    byte[] vf$P();
}
