package simon.vulkanfish.client.mixin;

import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.core.BlockPos;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Partikel-Extract ohne Allokation. Anker ist bewusst die virtuelle Methode {@code extract}
 * (plus {@code extractRotatedQuad}): Kleine finale Helfer wie {@code getLightCoords} existieren im
 * Produktions-Jar nicht mehr (ProGuard liniert sie in den Aufrufer ein) – Redirects dorthin
 * wuerden beim Start crashen. Alle Redirects mit {@code require = 0}: Je nach Umgebung (Dev/
 * Produktion, Inlining-Grad) greift genau der passende Anker, der Rest bleibt still. Eine
 * fehlende Optimierung darf niemals abstuerzen.
 *
 * <p>Quaternionf: {@code new} pro Partikel pro Frame (16k Partikel = ~640 KiB/Frame GC-Druck)
 * wird zur gepoolten Instanz. Der folgende &lt;init&gt; setzt Identity, danach ueberschreibt
 * setRotation vollstaendig (beide Facing-Modi nutzen set, einziger Override nutzt LOOKAT_Y) –
 * exakt wie frisch. Roll-Anteil und Verbrauch sind unveraendert.
 *
 * <p>BlockPos: {@code containing} pro Partikel pro Frame wird zur gepoolten Mutable-Instanz
 * (gleiche Floors, synchroner Verbrauch in hasChunkAt/getLightCoords, nie behalten).
 */
@Mixin(SingleQuadParticle.class)
public class SingleQuadParticleMixin {
    @Redirect(method = "extract", at = @At(value = "NEW", target = "org/joml/Quaternionf"), require = 0)
    private Quaternionf vulkanfish$pooledQuat() {
        return simon.vulkanfish.client.render.PooledVanilla.QUAT;
    }

    @Redirect(method = "extract",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/core/BlockPos;containing(DDD)Lnet/minecraft/core/BlockPos;"),
            require = 0)
    private BlockPos vulkanfish$pooledPosFlat(double x, double y, double z) {
        return simon.vulkanfish.client.render.PooledVanilla.containing(x, y, z);
    }

    @Redirect(method = "extractRotatedQuad",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/core/BlockPos;containing(DDD)Lnet/minecraft/core/BlockPos;"),
            require = 0)
    private BlockPos vulkanfish$pooledPosNested(double x, double y, double z) {
        return simon.vulkanfish.client.render.PooledVanilla.containing(x, y, z);
    }
}
