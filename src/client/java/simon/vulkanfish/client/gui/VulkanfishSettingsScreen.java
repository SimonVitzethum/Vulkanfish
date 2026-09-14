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
        Boolean match = known != null ? simon.vulkanfish.client.lod.gen.WorldgenSource.seedMatches(mc.level, known) : null;
        StringWidget status = new StringWidget(Component.translatable(known == null ? "options.vulkanfish.seed.none"
                : match == null ? "options.vulkanfish.seed.saved" : match ? "options.vulkanfish.seed.match" : "options.vulkanfish.seed.mismatch"), font);
        Button apply = Button.builder(Component.translatable("options.vulkanfish.seed.apply"), b -> {
            String v = box.getValue().trim();
            if (v.isEmpty()) {
                VulkanfishSettings.setSeed(address, null);
                status.setMessage(Component.translatable("options.vulkanfish.seed.none"));
                return;
            }
            long seed = VulkanfishSettings.parseSeed(v);
            VulkanfishSettings.setSeed(address, seed);
            // Sofort pruefen: der Server schickt einen Hash des Seeds
            Boolean ok = simon.vulkanfish.client.lod.gen.WorldgenSource.seedMatches(mc.level, seed);
            status.setMessage(Component.translatable(ok == null ? "options.vulkanfish.seed.saved"
                    : ok ? "options.vulkanfish.seed.match" : "options.vulkanfish.seed.mismatch"));
            // Fernfeld neu aufsetzen
            var lod = simon.vulkanfish.client.lod.LodManager.instance();
            if (lod != null) lod.reloadWorldgen();
        }).width(150).build();
        list.addSmall(box, apply);
        list.addSmall(status, null);
    }
}
