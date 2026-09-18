package simon.vulkanfish.client.mixin;

import net.minecraft.client.particle.SingleQuadParticle;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Partikel-Extract ohne Allokation: {@code new Quaternionf()} pro Partikel pro Frame (bei 16k
 * Partikeln ~640 KiB/Frame GC-Druck) wird zur gepoolten Instanz. Der folgende &lt;init&gt; setzt
 * Identity, danach ueberschreibt setRotation vollstaendig (beide Facing-Modi nutzen set) –
 * exakt wie frisch. Roll-Anteil und Verbrauch sind unveraendert.
 */
@Mixin(SingleQuadParticle.class)
public class SingleQuadParticleMixin {
    @Redirect(method = "extract", at = @At(value = "NEW", target = "org/joml/Quaternionf"))
    private Quaternionf vulkanfish$pooledQuat() {
        return simon.vulkanfish.client.render.PooledVanilla.QUAT;
    }
}
