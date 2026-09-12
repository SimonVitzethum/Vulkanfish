package simon.vulkanfish.client.mixin;

import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import simon.vulkanfish.client.VulkanfishSettings;

/** Abschnitt "Vulkanfish" am Ende der Videoeinstellungen. */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends OptionsSubScreen {
    private VideoSettingsScreenMixin(Screen lastScreen, Options options, Component title) {
        super(lastScreen, options, title);
    }

    @Inject(method = "addOptions", at = @At("TAIL"))
    private void vulkanfish$addOptions(CallbackInfo ci) {
        if (this.list == null) return;
        OptionInstance<Integer> fullbright = new OptionInstance<>(
                "options.vulkanfish.fullbright",
                OptionInstance.cachedConstantTooltip(Component.translatable("options.vulkanfish.fullbright.tooltip")),
                (caption, value) -> value == 0
                        ? CommonComponents.optionNameValue(caption, CommonComponents.OPTION_OFF)
                        : Component.translatable("options.percent_value", caption, value),
                new OptionInstance.IntRange(0, 100),
                VulkanfishSettings.fullbrightPercent(),
                VulkanfishSettings::setFullbright);
        this.list.addHeader(Component.translatable("options.vulkanfish.header"));
        this.list.addSmall(fullbright);
    }
}
