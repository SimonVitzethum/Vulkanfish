package simon.vulkanfish.client.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import it.unimi.dsi.fastutil.longs.LongList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Swapchain-Zustand fuer den Present-Thread der DLSS Frame Generation (FgPresenter). */
@Mixin(VulkanGpuSurface.class)
public interface VulkanGpuSurfaceAccessor {
    @Accessor("swapchain")
    long vulkanfish$swapchain();

    @Accessor("swapchainImages")
    LongList vulkanfish$swapchainImages();

    @Accessor("swapchainWidth")
    int vulkanfish$width();

    @Accessor("swapchainHeight")
    int vulkanfish$height();

    @Accessor("swapchainOutOfDate")
    boolean vulkanfish$outOfDate();

    @Accessor("swapchainOutOfDate")
    void vulkanfish$setOutOfDate(boolean value);

    @Accessor("swapchainSuboptimal")
    void vulkanfish$setSuboptimal(boolean value);
}
