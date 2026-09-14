package simon.vulkanfish.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import simon.vulkanfish.client.VulkanfishSettings;

/**
 * Vulkanfish-Einstellungen (aus den Videoeinstellungen): Licht/Bild und Fernfeld, im
 * Mehrspieler zusaetzlich der Seed des Servers fuer die Fernfeld-Generierung.
 */
public final class VulkanfishSettingsScreen extends OptionsSubScreen {
    public VulkanfishSettingsScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, Component.translatable("options.vulkanfish.title"));
    }

    @Override
    protected void addOptions() {
        list.addHeader(Component.translatable("options.vulkanfish.image.header"));
        list.addSmall(VulkanfishOptions.fullbright(), VulkanfishOptions.raytracing(), VulkanfishOptions.taa(), VulkanfishOptions.dlss(),
                VulkanfishOptions.rayReconstruction(), VulkanfishOptions.frameGeneration());
        list.addHeader(Component.translatable("options.vulkanfish.lod.header"));
        list.addSmall(VulkanfishOptions.lodDistance(), VulkanfishOptions.lodDetail(), VulkanfishOptions.lodBudget());
        addSeedRow();
    }

    /** Seed fuer den aktuellen Server (nur Mehrspieler: im Einzelspieler kennt die Mod die Welt). */
    private void addSeedRow() {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getCurrentServer();
        if (server == null || mc.isLocalServer()) return;
        String address = server.ip;
        list.addHeader(Component.translatable("options.vulkanfish.seed.header", address));
        EditBox box = new EditBox(font, 150, 20, Component.translatable("options.vulkanfish.seed"));
        box.setMaxLength(32);
        Long known = VulkanfishSettings.seedFor(address);
        if (known != null) box.setValue(Long.toString(known));
        box.setHint(Component.translatable("options.vulkanfish.seed.hint"));
        StringWidget status = new StringWidget(Component.translatable(known != null ? "options.vulkanfish.seed.saved" : "options.vulkanfish.seed.none"), font);
        Button apply = Button.builder(Component.translatable("options.vulkanfish.seed.apply"), b -> {
            String v = box.getValue().trim();
            if (v.isEmpty()) {
                VulkanfishSettings.setSeed(address, null);
                status.setMessage(Component.translatable("options.vulkanfish.seed.none"));
                return;
            }
            try {
                VulkanfishSettings.setSeed(address, Long.parseLong(v));
                status.setMessage(Component.translatable("options.vulkanfish.seed.saved"));
                // Fernfeld neu aufsetzen (Seed wird gegen den Hash des Servers geprueft)
                var lod = simon.vulkanfish.client.lod.LodManager.instance();
                if (lod != null) lod.reloadWorldgen();
            } catch (NumberFormatException e) {
                status.setMessage(Component.translatable("options.vulkanfish.seed.invalid"));
            }
        }).width(150).build();
        list.addSmall(box, apply);
        list.addSmall(status, null);
    }
}
