package simon.vulkanfish.client.mixin;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Partikel-Ratenbremse (siehe ParticleThrottle): add() ist der einzige Spawn-Trichter –
 * Ueberzaehliges wird verworfen, bevor es je simuliert oder submittet wird.
 */
@Mixin(ParticleEngine.class)
public class ParticleEngineMixin {
    @Inject(method = "add(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
    private void vulkanfish$throttleSpawns(Particle particle, CallbackInfo ci) {
        if (!simon.vulkanfish.client.render.ParticleThrottle.allow()) ci.cancel();
    }
}
