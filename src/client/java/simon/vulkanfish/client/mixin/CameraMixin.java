package simon.vulkanfish.client.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import simon.vulkanfish.client.lod.LodManager;

/**
 * Far-Plane bis zum Ende des LOD-Fernfelds (Vanilla: 4x Sichtweite). Reverse-Z mit Float-Tiefe:
 * die groessere Entfernung kostet praktisch keine Tiefenpraezision.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    private float depthFar;

    @Inject(method = "update", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Camera;depthFar:F",
            opcode = org.objectweb.asm.Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void vulkanfish$lodFarPlane(CallbackInfo ci) {
        LodManager lod = LodManager.instance();
        if (lod != null) depthFar = Math.max(depthFar, lod.farBlocks() * 1.25f + 512.0f);
    }
}
