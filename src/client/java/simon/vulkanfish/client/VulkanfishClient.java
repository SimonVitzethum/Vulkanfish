package simon.vulkanfish.client;

import net.fabricmc.api.ClientModInitializer;
import simon.vulkanfish.client.gpu.AsyncFrameGraph;
import simon.vulkanfish.client.gpu.BlazeDeviceInterop;
import simon.vulkanfish.client.gpu.BobbyInterop;
import simon.vulkanfish.client.gpu.GpuDrivenConfig;
import simon.vulkanfish.client.gpu.VulkanfishRenderer;

public class VulkanfishClient implements ClientModInitializer {
    public static VulkanfishRenderer RENDERER;

    @Override
    public void onInitializeClient() {
        GpuDrivenConfig config = GpuDrivenConfig.load();
        RENDERER = new VulkanfishRenderer(config, new AsyncFrameGraph(config), new BlazeDeviceInterop(), new BobbyInterop());
        RENDERER.init();
    }
}
