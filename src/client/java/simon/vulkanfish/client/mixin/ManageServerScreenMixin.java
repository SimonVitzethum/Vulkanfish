package simon.vulkanfish.client.mixin;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ManageServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import simon.vulkanfish.client.VulkanfishSettings;

/**
 * Serverliste -> Server bearbeiten/hinzufuegen: Feld fuer den Welt-Seed (Fernfeld-Generierung im
 * Mehrspieler). Gespeichert je Serveradresse beim Klick auf "Fertig", wie im Vulkanfish-Menue.
 */
@Mixin(ManageServerScreen.class)
public abstract class ManageServerScreenMixin extends Screen {
    @Shadow private EditBox ipEdit;
    @Shadow @Final private ServerData serverData;
    @Unique private EditBox vulkanfish$seed;
    @Unique private String vulkanfish$seedValue;

    private ManageServerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void vulkanfish$addSeed(CallbackInfo ci) {
        int y = this.height / 4 + 72;
        // Knoepfe ab der Seed-Zeile eine Zeile nach unten
        for (var child : this.children()) {
            if (child instanceof AbstractWidget w && w.getY() >= y) w.setY(w.getY() + 24);
        }
        String old = vulkanfish$seed != null ? vulkanfish$seed.getValue() : null;
        vulkanfish$seed = new EditBox(this.font, this.width / 2 - 100, y, 200, 20, Component.translatable("options.vulkanfish.seed"));
        vulkanfish$seed.setMaxLength(64);
        vulkanfish$seed.setHint(Component.translatable("manageServer.vulkanfish.seed.hint"));
        vulkanfish$seed.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("manageServer.vulkanfish.seed.tooltip")));
        if (old != null) {
            vulkanfish$seed.setValue(old);
        } else {
            Long known = serverData.ip == null || serverData.ip.isBlank() ? null : VulkanfishSettings.seedFor(serverData.ip);
            if (known != null) vulkanfish$seed.setValue(Long.toString(known));
        }
        this.addRenderableWidget(vulkanfish$seed);
    }

    @Inject(method = "onAdd", at = @At("TAIL"))
    private void vulkanfish$saveSeed(CallbackInfo ci) {
        if (vulkanfish$seed == null || ipEdit == null) return;
        String ip = ipEdit.getValue().trim();
        if (ip.isEmpty()) return;
        VulkanfishSettings.setSeed(ip, VulkanfishSettings.parseSeed(vulkanfish$seed.getValue()));
    }
}
