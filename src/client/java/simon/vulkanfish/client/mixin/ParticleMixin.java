package simon.vulkanfish.client.mixin;

import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Partikel-Licht ohne Allokation: {@code BlockPos.containing} pro Partikel pro Frame wird zur
 * gepoolten Mutable-Instanz (gleiche Floors, synchroner Verbrauch in hasChunkAt/getLightCoords).
 */
@Mixin(Particle.class)
public class ParticleMixin {
    @Redirect(method = "getLightCoords",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/core/BlockPos;containing(DDD)Lnet/minecraft/core/BlockPos;"))
    private BlockPos vulkanfish$pooledPos(double x, double y, double z) {
        return simon.vulkanfish.client.render.PooledVanilla.containing(x, y, z);
    }
}
