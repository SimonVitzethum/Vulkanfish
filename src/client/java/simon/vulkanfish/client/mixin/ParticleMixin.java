package simon.vulkanfish.client.mixin;

import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Partikel-Licht ohne Allokation (Dev-Umgebung, Methode existiert dort): {@code BlockPos.containing}
 * pro Partikel pro Frame wird zur gepoolten Mutable-Instanz (gleiche Floors, synchroner Verbrauch).
 * {@code require = 0}: Im Produktions-Jar ist die Methode ggf. ein-liniert (dann greifen die
 * Anker in SingleQuadParticleMixin), auf fremden Versionen bleibt es still statt zu crashen.
 */
@Mixin(Particle.class)
public class ParticleMixin {
    @Redirect(method = "getLightCoords",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/core/BlockPos;containing(DDD)Lnet/minecraft/core/BlockPos;"),
            require = 0)
    private BlockPos vulkanfish$pooledPos(double x, double y, double z) {
        return simon.vulkanfish.client.render.PooledVanilla.containing(x, y, z);
    }
}
