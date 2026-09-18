package simon.vulkanfish.client.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import simon.vulkanfish.client.gpu.MeshShaderSupport;

/**
 * Eigene FeatureSets ans optionale Angebot haengen (26.3): Vanilla aktiviert sie nur bei
 * Support – Mesh nur auf Mesh-GPUs (sonst Classic-Fallback), float64/RT/NGX ebenso bedingt.
 */
@Mixin(VulkanFeatureSets.class)
public class VulkanFeatureSetsMixin {
    @Inject(method = "optionalFeatureSets()Ljava/util/Set;", at = @At("RETURN"))
    private static void vulkanfish$addFeatureSets(CallbackInfoReturnable<Set<FeatureSet>> cir) {
        Set<FeatureSet> sets = cir.getReturnValue();
        sets.add(MeshShaderSupport.baseFeatureSet());
        sets.add(MeshShaderSupport.meshFeatureSet());
        sets.add(MeshShaderSupport.float64FeatureSet());
        sets.add(MeshShaderSupport.rtFeatureSet());
        FeatureSet ngx = simon.vulkanfish.client.gpu.NgxBridge.ngxFeatureSet();
        if (ngx != null) sets.add(ngx);
    }
}
