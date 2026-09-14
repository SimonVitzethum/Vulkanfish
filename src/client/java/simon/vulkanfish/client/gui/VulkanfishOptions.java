package simon.vulkanfish.client.gui;

import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import simon.vulkanfish.client.VulkanfishSettings;

/** Optionen fuer Videoeinstellungen und den Vulkanfish-Bildschirm (Werte in VulkanfishSettings). */
public final class VulkanfishOptions {
    private VulkanfishOptions() {
    }

    private static <T> OptionInstance.TooltipSupplier<T> tip(String key) {
        return OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip"));
    }

    public static OptionInstance<Integer> fullbright() {
        return new OptionInstance<>("options.vulkanfish.fullbright", tip("options.vulkanfish.fullbright"),
                (caption, value) -> value == 0
                        ? CommonComponents.optionNameValue(caption, CommonComponents.OPTION_OFF)
                        : Component.translatable("options.percent_value", caption, value),
                new OptionInstance.IntRange(0, 100), VulkanfishSettings.fullbrightPercent(), VulkanfishSettings::setFullbright);
    }

    public static OptionInstance<Boolean> raytracing() {
        return OptionInstance.createBoolean("options.vulkanfish.raytracing", tip("options.vulkanfish.raytracing"),
                VulkanfishSettings.raytracing(), VulkanfishSettings::setRaytracing);
    }

    public static OptionInstance<Boolean> taa() {
        return OptionInstance.createBoolean("options.vulkanfish.taa", tip("options.vulkanfish.taa"),
                VulkanfishSettings.taa(), VulkanfishSettings::setTaa);
    }

    public static OptionInstance<Boolean> dlss() {
        return OptionInstance.createBoolean("options.vulkanfish.dlss", tip("options.vulkanfish.dlss"),
                VulkanfishSettings.dlss(), VulkanfishSettings::setDlss);
    }

    /** DLSS-Modus (Renderaufloesung): DLAA, Qualitaet, Ausgewogen, Leistung, Ultra-Leistung. */
    public static OptionInstance<Integer> dlssMode() {
        return new OptionInstance<>("options.vulkanfish.dlssMode", tip("options.vulkanfish.dlssMode"),
                (caption, value) -> CommonComponents.optionNameValue(caption, Component.translatable("options.vulkanfish.dlssMode." + value)),
                new OptionInstance.IntRange(0, 4), VulkanfishSettings.dlssMode(), VulkanfishSettings::setDlssMode);
    }

    public static OptionInstance<Boolean> rayReconstruction() {
        return OptionInstance.createBoolean("options.vulkanfish.rayReconstruction", tip("options.vulkanfish.rayReconstruction"),
                VulkanfishSettings.rayReconstruction(), VulkanfishSettings::setRayReconstruction);
    }

    /** DLSS Frame Generation: aus, 2x..6x. */
    public static OptionInstance<Integer> frameGeneration() {
        return new OptionInstance<>("options.vulkanfish.frameGen", tip("options.vulkanfish.frameGen"),
                (caption, value) -> value <= 1
                        ? CommonComponents.optionNameValue(caption, CommonComponents.OPTION_OFF)
                        : Component.translatable("options.vulkanfish.times", caption, value),
                new OptionInstance.IntRange(1, 6), VulkanfishSettings.frameGeneration(), VulkanfishSettings::setFrameGeneration);
    }

    /** Fernfeld-Radius in Schritten zu 32 Chunks (32..1024). */
    public static OptionInstance<Integer> lodDistance() {
        return new OptionInstance<>("options.vulkanfish.lodDistance", tip("options.vulkanfish.lodDistance"),
                (caption, value) -> Component.translatable("options.vulkanfish.chunks", caption, value * 32),
                new OptionInstance.IntRange(1, 32, false), Math.max(1, Math.round(VulkanfishSettings.lodChunks() / 32f)),
                v -> VulkanfishSettings.setLodChunks(v * 32));
    }

    /** Erlaubter Bildfehler eines Fernfeld-Voxels: 0,5..4 px (kleiner = mehr Detail). */
    public static OptionInstance<Integer> lodDetail() {
        return new OptionInstance<>("options.vulkanfish.lodDetail", tip("options.vulkanfish.lodDetail"),
                (caption, value) -> Component.translatable("options.vulkanfish.pixels", caption, String.format(java.util.Locale.ROOT, "%.1f", value * 0.5f)),
                new OptionInstance.IntRange(1, 8, false), Math.max(1, Math.round(VulkanfishSettings.lodPixelError() * 2f)),
                v -> VulkanfishSettings.setLodPixelError(v * 0.5f));
    }

    /** GPU-Zeit je Frame fuer den Fernfeld-Aufbau: 0,25..5 ms. */
    public static OptionInstance<Integer> lodBudget() {
        return new OptionInstance<>("options.vulkanfish.lodBudget", tip("options.vulkanfish.lodBudget"),
                (caption, value) -> Component.translatable("options.vulkanfish.ms", caption, String.format(java.util.Locale.ROOT, "%.2f", value * 0.25f)),
                new OptionInstance.IntRange(1, 20), Math.max(1, Math.round(VulkanfishSettings.lodGpuMs() * 4f)),
                v -> VulkanfishSettings.setLodGpuMs(v * 0.25f));
    }
}
