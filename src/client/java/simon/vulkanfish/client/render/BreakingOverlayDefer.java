package simon.vulkanfish.client.render;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.state.level.BlockBreakingRenderState;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import simon.vulkanfish.client.mixin.PreparedFrameInvoker;

/**
 * Aufgeschobenes Riss-Overlay (siehe PreparedFrameMixin). Nur wenn gerade ein transluzenter
 * Block angeschlagen wird: Risse auf Bloecken unter Wasser muessen weiter VOR dem Wasser
 * entstehen, sonst verdeckt die Wassertiefe sie. Render-Thread.
 */
public final class BreakingOverlayDefer {
    private record Pending(FeatureRenderDispatcher.PreparedFrame frame, FeatureRenderPhase<?> phase, FeatureFrameContext context) {
    }

    private static final List<Pending> PENDING = new ArrayList<>();
    private static boolean translucentBreaking;
    private static boolean enabled;

    private BreakingOverlayDefer() {
    }

    /** Aus LevelRendererMixin beim Einreichen der Abbau-Animation. */
    public static void onSubmit(List<BlockBreakingRenderState> states, boolean nativeActive) {
        enabled = nativeActive;
        translucentBreaking = false;
        for (BlockBreakingRenderState s : states) {
            if (s.blockState().getBlock() instanceof HalfTransparentBlock || s.blockState().getBlock() instanceof StainedGlassPaneBlock) {
                translucentBreaking = true;
                break;
            }
        }
    }

    /** Unser Renderer zeichnet diesen Frame (Stand beim Einreichen der Abbau-Animation). */
    public static boolean nativeActive() {
        return enabled;
    }

    public static boolean active() {
        return enabled && translucentBreaking;
    }

    public static void add(FeatureRenderDispatcher.PreparedFrame frame, FeatureRenderPhase<?> phase, FeatureFrameContext context) {
        PENDING.add(new Pending(frame, phase, context));
    }

    /** Nach unserem Wasser-/Glas-Pass (ChunkSectionsToRenderMixin). */
    public static void flush() {
        for (Pending p : PENDING) ((PreparedFrameInvoker) (Object) p.frame()).vulkanfish$executePhase(p.phase(), p.context());
        PENDING.clear();
        translucentBreaking = false;
    }
}
