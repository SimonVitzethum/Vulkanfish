package simon.vulkanfish.client.mixin;

import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Zugriff auf PreparedFrame.executePhase: aufgeschobene Phasen spaeter im Frame ausfuehren. */
@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
public interface PreparedFrameInvoker {
    @Invoker("executePhase")
    void vulkanfish$executePhase(FeatureRenderPhase<?> phase, FeatureFrameContext context);
}
