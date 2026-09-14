package simon.vulkanfish.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugEntryFps;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import simon.vulkanfish.client.gpu.FgPresenter;

/** F3: mit DLSS Frame Generation die angezeigten FPS zeigen (und die gerenderten dahinter). */
@Mixin(DebugEntryFps.class)
public class DebugEntryFpsMixin {
    @ModifyArg(method = "display", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/debug/DebugScreenDisplayer;addPriorityLine(Ljava/lang/String;)V"))
    private String vulkanfish$dlssFps(String line) {
        FgPresenter fp = FgPresenter.instance();
        int shown = fp != null ? fp.displayedFps() : -1;
        if (shown < 0) return line;
        int rendered = Minecraft.getInstance().getFps();
        int cut = line.indexOf(" fps");
        String rest = cut >= 0 ? line.substring(cut + 4) : "";
        return shown + " fps (DLSS FG " + fp.currentMultiplier() + "x, " + rendered + " rendered)" + rest;
    }
}
