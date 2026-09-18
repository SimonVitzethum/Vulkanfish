package simon.vulkanfish.client.mixin;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Entity-Licht ohne Allokation: {@code BlockPos.containing} pro Entity pro Frame (getEyePosition
 * + Lichtprobe) wird zur gepoolten Mutable-Instanz. Alle Overrides nutzen die Position nur sofort
 * (Konstanten oder Durchreichen an super) – nie behalten.
 */
@Mixin(EntityRenderer.class)
public class EntityRendererMixin {
    @Redirect(method = "getPackedLightCoords",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/core/BlockPos;containing(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/core/BlockPos;"))
    private BlockPos vulkanfish$pooledPos(Vec3 v) {
        return simon.vulkanfish.client.render.PooledVanilla.containing(v);
    }
}
