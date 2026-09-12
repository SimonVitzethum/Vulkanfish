package simon.vulkanfish.client;

import net.fabricmc.api.ClientModInitializer;
import simon.vulkanfish.client.gpu.ArchetypeStore;
import simon.vulkanfish.client.gpu.AsyncFrameGraph;
import simon.vulkanfish.client.gpu.BlazeDeviceInterop;
import simon.vulkanfish.client.gpu.BobbyInterop;
import simon.vulkanfish.client.gpu.EntityInstanceBuffer;
import simon.vulkanfish.client.gpu.GpuDrivenConfig;
import simon.vulkanfish.client.gpu.GpuSceneManager;
import simon.vulkanfish.client.gpu.HizPyramid;
import simon.vulkanfish.client.gpu.MeshletManager;
import simon.vulkanfish.client.gpu.RaytracingModule;
import simon.vulkanfish.client.gpu.TlasManager;
import simon.vulkanfish.client.gpu.VulkanfishRenderer;

public class VulkanfishClient implements ClientModInitializer {
    public static VulkanfishRenderer RENDERER;

    @Override
    public void onInitializeClient() {
        GpuDrivenConfig config = GpuDrivenConfig.load();
        GpuSceneManager scene = new GpuSceneManager(config);
        HizPyramid hiz = new HizPyramid(config);
        MeshletManager meshlets = new MeshletManager(config);
        EntityInstanceBuffer entities = new EntityInstanceBuffer(config);
        AsyncFrameGraph frameGraph = new AsyncFrameGraph(config);
        RaytracingModule rt = new RaytracingModule(config);
        BlazeDeviceInterop device = new BlazeDeviceInterop();
        ArchetypeStore archetypes = new ArchetypeStore();
        TlasManager tlas = new TlasManager(config, device);
        BobbyInterop bobby = new BobbyInterop();
        RENDERER = new VulkanfishRenderer(config, scene, hiz, meshlets, entities,
                frameGraph, rt, device, archetypes, tlas, bobby);
        RENDERER.init();
    }
}
