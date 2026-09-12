package simon.vulkanfish.client.mixin;

import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Abschnitt "Vulkanfish" am Ende der Videoeinstellungen. */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends OptionsSubScreen {
    private VideoSettingsScreenMixin(Screen lastScreen, Options options, Component title) {
        super(lastScreen, options, title);
    }

    @Inject(method = "addOptions", at = @At("TAIL"))
    private void vulkanfish$addOptions(CallbackInfo ci) {
        if (this.list == null) return;
        this.list.addHeader(Component.translatable("options.vulkanfish.header"));
        var more = net.minecraft.client.gui.components.Button.builder(Component.translatable("options.vulkanfish.more"),
                b -> this.minecraft.gui.setScreen(new simon.vulkanfish.client.gui.VulkanfishSettingsScreen(this, this.options))).width(150).build();
        this.list.addSmall(simon.vulkanfish.client.gui.VulkanfishOptions.fullbright().createButton(this.options), more);
    }
}
