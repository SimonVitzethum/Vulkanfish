package simon.vulkanfish.client.gpu;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkClearDepthStencilValue;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Native Vulkan-Seite des Renderers – laeuft auf MOJANGS VkDevice (per
 * {@link MeshShaderSupport} um Mesh-Shader erweitert) und haengt pro Frame
 * EINEN Command-Buffer per {@link VulkanCommandEncoder#execute} in Mojangs
 * Frame-Submission, genau vor Vanillas Opaque-Terrain.
 *
 * <p>Frame: Uploads -> Hi-Z (Vorframe-Tiefe) -> Meshlet-Cull (Kamera + Licht)
 * -> Schatten-Map -> G-Buffer (Mesh-Shader) -> Deferred-Licht (HDR) -> Bloom
 * -> Tonemap (RGBA8) -> Kopie ins Main-Target + Tiefe ins Main-Depth.
 * Zweiter Command-Buffer (in Vanillas TRANSLUCENT-Gruppe, also nach den
 * Entities): Wasser direkt ins Main-Target, mit Kopien fuer Brechung/SSR.
 * Wie Mojang bleiben alle Images dauerhaft im Layout GENERAL.
 *
 * <p>Synchronisation: eine Timeline-Semaphore, die Mojangs Submission nach
 * unserem Command-Buffer signalisiert; 3 Slots, praktisch nie ein CPU-Stall.
 * GPU-Zeiten pro Pass kommen aus Timestamp-Queries (Log alle 10 s).
 */
public final class NativePassRunner {
    private static final Logger LOG = LoggerFactory.getLogger("vulkanfish");

    // Formate
    private static final int FMT_GBUF = VK10.VK_FORMAT_R8G8B8A8_UNORM;   // 2x 4 Byte/Pixel
    private static final int FMT_HDR = VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
    private static final int FMT_LDR = VK10.VK_FORMAT_R8G8B8A8_UNORM;
    private static final int FMT_DEPTH = VK10.VK_FORMAT_D32_SFLOAT;
    private static final int FMT_HIZ = VK10.VK_FORMAT_R32_SFLOAT;
    // GPU-Scene-Kapazitaet in Quads (Stufe 0; bei VRAM-Mangel halbiert).
    // 6 M Quads = 384 MiB Vertices + 48 MiB Dreiecke + 32 MiB Meshlets: reicht fuer
    // Sichtweite 32 rundum; was nicht passt, zeichnet Vanilla (Hybrid).
    private static final int MAX_QUADS = 6 * 1024 * 1024;
    private static final int MIN_QUADS = 512 * 1024;
    public static final int CLUSTER_BYTES = 64;
    // Upload-Ziele
    public static final int TARGET_CLUSTERS = 0;
    public static final int TARGET_MESHLETS = 1;
    public static final int TARGET_VERTS = 2;
    public static final int TARGET_TRIS = 3;
    public static final int TARGET_LOD_QUADS = 4;
    public static final int TARGET_LOD_MESHLETS = 5;
    public static final int TARGET_LOD_CLUSTERS = 6;
    private static final int UBO_BYTES = 528;
    private static final int FRAMES = 3;
    private static final long STAGING_BYTES = 16L << 20;
    private static final long WAIT_TIMEOUT_NS = 2_000_000_000L;
    private static final long STAGE2_ALL_COMMANDS = 0x00010000L;
    public static final int SHADOW_RES = 2048;
    private static final int BLOOM_MIPS = 6;
    // Timestamps: Grenzen zwischen den Passes
    private static final String[] PASS_NAMES = {"upload", "rt-bau", "hiz", "cull", "schatten", "gbuffer", "licht", "bloom", "final"};
    private static final int TS_TERRAIN = PASS_NAMES.length + 1;
    private static final int TS_PER_FRAME = TS_TERRAIN + 7; // + Wasser-Zwischenstempel (3) // + Wasser/Glas (Start/Ende) + TAA (Start/Ende)
    private static final int TAA_UBO_BYTES = 160;

    private final BlazeDeviceInterop blaze;
    private VkDevice dev;
    private long vma; // Mojangs VmaAllocator (thread-sicher, lebt bis zum Device-Ende)
    private VkPhysicalDevice phys;
    private VkQueue gfxQ;
    private int gfxFam;
    private boolean ready;
    private String disableReason = "nicht initialisiert";
    private final boolean hizEnabled;

    // Handles (fuer destroy())
    private final List<Long> shaderModules = new ArrayList<>();
    private final List<Long> setLayouts = new ArrayList<>();
    private final List<Long> pipelineLayouts = new ArrayList<>();
    private final List<Long> pipelines = new ArrayList<>();
    private final List<Buf> buffers = new ArrayList<>();
    private long linearSampler;
    private long atlasSampler;
    private long shadowSampler;
    private long pool;
    private long timeline;
    private long timelineValue;
    private long queryPool;
    private float timestampPeriodNs;
    private final long[] slotValue = new long[FRAMES];
    private final boolean[] slotHasTimestamps = new boolean[FRAMES];
    private final VkCommandBuffer[] cmds = new VkCommandBuffer[FRAMES];
    private final double[] passMsSum = new double[PASS_NAMES.length];
    private int passSamples;
    private double waterMsSum;
    private int waterSamples;
    private final VkCommandBuffer[] waterCmds = new VkCommandBuffer[FRAMES];
    private final VkCommandBuffer[] taaCmds = new VkCommandBuffer[FRAMES];
    private final Buf[] taaUniforms = new Buf[FRAMES];
    private double taaMsSum;
    private int taaSamples;
    private long layoutTaa;
    private long pipeTaa;
    private Img[] history = new Img[2];
    private Img taaOut;
    private int historyIndex;
    private boolean historyValid;
    private float[] prevViewProjUnjittered;
    private FrameUniformsData frameData; // Daten des aktuellen Frames (fuer TAA)
    private int waterSlot = -1; // Slot, dessen Terrain-Frame gerade aufgenommen wurde (Wasser folgt)

    // Pipelines + Layouts
    private long layoutCull;
    private long layoutTerrain;
    private long layoutHiz;
    private long layoutDeferred;
    private long layoutBloom;
    private long layoutFinal;
    private long pipeCull;
    private long pipeGbuffer;
    private long pipeShadow;
    private long pipeHiz;
    private long pipeDeferred;
    private long pipeBloomDown;
    private long pipeBloomUp;
    private long pipeFinal;
    private long layoutWater;
    private long waterMeshModule;
    private long waterFragModule;
    private long transMeshModule;
    private long transFragModule;
    private long pipeTrans;
    private long lastAtlasView;
    private long pipeWater;
    private long lodWaterMeshModule, lodWaterFragModule, pipeLodWater, pipeLodWaterDepth; // Fernfeld-Wasser
    private int waterColorFormat;
    // Exakte Transparenz (Pro-Pixel-Listen, oit.slang)
    private long pipeWaterDepth;     // Tiefen-Vorpass der Wasseroberflaeche (trennt davor/dahinter)
    private long layoutOit;
    private long pipeOitResolve;
    private long layoutOitComposite;
    private long oitFsMeshModule;
    private long oitCompositeFragModule;
    private long pipeOitComposite;   // lazy im Main-Format
    private long pipeEntityShade, layoutEntityShade, entityShadeFragModule; // Sonnenschatten auf Vanilla-Entities
    private boolean shadowsOn; // Schattenkarte in diesem Frame gezeichnet
    private Buf oitNodes;
    private Buf oitCounter;
    private int oitCapacity;
    // Raytracing-Blocklicht (nur mit Ray Queries auf Mojangs Device)
    private RtAccel rt;
    private long rtCpuNanos;
    private int rtCpuFrames;
    private boolean rtWanted;
    private long layoutDeferredRt;
    private long pipeDeferredRt;
    private long layoutLightBin;
    private long pipeLightBin;
    private Buf cellCount;
    private Buf cellLights;

    // Buffer
    private Buf clusters;
    private Buf meshlets;
    private Buf verts;
    private Buf tris;
    private Buf visMeshlets;
    private Buf visMeshlets2;   // Phase 2: neu sichtbare Meshlets
    // LOD-Fernfeld (Quads a 8 Byte, eigene Meshlet-/Cluster-Hierarchie, gleiche Cull-Pipeline)
    private Buf lodQuads;
    private Buf lodVisWater, lodWaterIndirect;
    // Zeichnen: Aussehen (3 x uint4 je ID) und Biomfarben, unabhaengig vom GPU-Generator (auch CPU-Mesher)
    private Buf lodTexTable, lodDrawBiome;
    private int lodTexUploaded, lodDrawBiomeUploaded, lodTexGeneration = -1;
    private Buf lodMeshlets;
    private Buf lodClusters;
    private Buf lodVis1;
    private Buf lodVis2;
    private Buf lodVisBits;
    private Buf lodIndirect1;
    private Buf lodIndirect2;
    private Buf lodClusterTotal;
    private Buf lodNearMask;
    private boolean lodReady;
    private boolean lodClustersCleared;
    private simon.vulkanfish.client.lod.LodManager lod;

    // ---- GPU-Worldgen: Dichteprogramm + Noise-Tabellen der Welt (fuer lod_gen.slang) ----
    private Buf genProg, genConst, genPerm, genOffs, genOct, genNormal, genNormalFactor, genBlendD, genBlendOct;
    private int[] genProgOffset = new int[3];
    private int genFlatSlots, genInterpSlots, genResultReg, genMinY, genHeight, genSea, genLava;
    private boolean genReady;

    /**
     * Programm + Noise-Tabellen einer Welt hochladen (Render-Thread). height/minY der Dimension,
     * lavaLevel = min(-54, Meeresspiegel) wie Vanillas globale Fluessigkeitsregel.
     */
    public boolean setupGenerator(simon.vulkanfish.client.lod.gen.DensityProgram p, int minY, int height, int seaLevel) {
        genReady = false;
        if (pipeLodGen == 0L || p.regCount[0] > 32 || p.regCount[1] > 32 || p.regCount[2] > 32) return false;
        try (Arena arena = new Arena()) {
            waitAll();
            for (Buf b : new Buf[]{genProg, genConst, genPerm, genOffs, genOct, genNormal, genNormalFactor, genBlendD, genBlendOct}) {
                if (b != null) destroyBuffer(b);
            }
            int stor = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
            int host = VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
            // Programm: drei Stufen hintereinander
            int words = p.code[0].size() + p.code[1].size() + p.code[2].size() + p.code[3].size();
            genProg = makeBuffer(arena, words * 4L, stor | VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, host);
            ByteBuffer pb = MemoryUtil.memByteBuffer(genProg.mapped(), words * 4);
            int off = 0;
            for (int st = 0; st < 4; st++) {
                if (st < 3) genProgOffset[st] = off;
                for (int i = 0; i < p.code[st].size(); i++) pb.putInt((off + i) * 4, p.code[st].getInt(i));
                off += p.code[st].size();
            }
            genConst = makeBuffer(arena, Math.max(4, p.consts.size()) * 4L, stor | VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, host);
            ByteBuffer cb = MemoryUtil.memByteBuffer(genConst.mapped(), p.consts.size() * 4);
            for (int i = 0; i < p.consts.size(); i++) cb.putFloat(i * 4, p.consts.getFloat(i));
            int nImp = Math.max(1, p.improved.size());
            genPerm = makeBuffer(arena, nImp * 256L, stor | VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, host);
            genOffs = makeBuffer(arena, nImp * 24L, stor | VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, host);
            ByteBuffer permB = MemoryUtil.memByteBuffer(genPerm.mapped(), nImp * 256);
            ByteBuffer offB = MemoryUtil.memByteBuffer(genOffs.mapped(), nImp * 24);
            for (int i = 0; i < p.improved.size(); i++) {
                var n = p.improved.get(i);
                byte[] perm = ((simon.vulkanfish.client.mixin.worldgen.ImprovedNoiseAccessor) (Object) n).vf$P();
                for (int j = 0; j < 256; j++) permB.put(i * 256 + j, perm[j]);
                offB.putDouble(i * 24, n.xo).putDouble(i * 24 + 8, n.yo).putDouble(i * 24 + 16, n.zo);
            }
            int nOct = Math.max(1, p.octaves.size());
            genOct = makeBuffer(arena, nOct * 16L, stor | VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, host);
            ByteBuffer ob = MemoryUtil.memByteBuffer(genOct.mapped(), nOct * 16);
            for (int i = 0; i < p.octaves.size(); i++) {
                double[] o = p.octaves.get(i);
                ob.putInt(i * 16, (int) o[0]).putFloat(i * 16 + 4, (float) o[2]).putDouble(i * 16 + 8, o[1]);
            }
            int nNorm = Math.max(1, p.normalFactor.size());
            genNormal = makeBuffer(arena, nNorm * 16L, stor | VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, host);
            genNormalFactor = makeBuffer(arena, nNorm * 8L, stor | VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, host);
            ByteBuffer nb = MemoryUtil.memByteBuffer(genNormal.mapped(), nNorm * 16);
            ByteBuffer nf = MemoryUtil.memByteBuffer(genNormalFactor.mapped(), nNorm * 8);
            for (int i = 0; i < p.normalFactor.size(); i++) {
                for (int j = 0; j < 4; j++) nb.putInt(i * 16 + j * 4, p.normals.getInt(i * 4 + j));
                nf.putDouble(i * 8, p.normalFactor.get(i));
            }
            int nBl = Math.max(1, p.blended.size());
            genBlendD = makeBuffer(arena, nBl * 64L, stor | VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, host);
            genBlendOct = makeBuffer(arena, nBl * 160L, stor | VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, host);
            ByteBuffer bd = MemoryUtil.memByteBuffer(genBlendD.mapped(), nBl * 64);
            ByteBuffer bo = MemoryUtil.memByteBuffer(genBlendOct.mapped(), nBl * 160);
            for (int i = 0; i < p.blended.size(); i++) {
                double[] d = p.blended.get(i);
                for (int j = 0; j < 8; j++) bd.putDouble(i * 64 + j * 8, d[j]);
                for (int j = 0; j < 40; j++) bo.putInt(i * 160 + j * 4, (int) d[8 + j]);
            }
            genFlatSlots = Math.max(1, p.flatSlots);
            genInterpSlots = Math.max(1, p.interpSlots);
            genResultReg = p.resultReg;
            genMinY = minY;
            genHeight = height;
            genSea = seaLevel;
            genLava = Math.min(-54, seaLevel);
            genReady = true;
            LOG.info("[vulkanfish] GPU-Worldgen bereit: {} Worte Programm, {} Improved-Noises", words, p.improved.size());
            return true;
        } catch (Throwable t) {
            LOG.warn("[vulkanfish] GPU-Worldgen-Setup fehlgeschlagen", t);
            return false;
        }
    }

    // ---- LOD komplett auf der GPU: Generierung (lod_gen) + Greedy-Meshing (lod_mesher) ----
    /** Auftrag fuer einen LOD-Knoten; runHeads (34*34*2) / runs (je 4 int) nur bei echten Daten. */
    public record LodGpuJob(long nodeKey, int serial, int level, int nx, int nz, boolean generate, int[] runHeads, int[] runs) {
    }

    /** Zuteilung im Pool fuer einen gemeshten Auftrag (Index in der Liste des Batches). */
    public record LodGpuCommit(int jobIndex, int quadStart, int meshletStart, int cluster, int level, int nx, int nz, int[] dirQuads) {
    }

    public interface LodGpuClient {
        java.util.List<LodGpuJob> nextLodJobs(int max);

        /** counts: je Auftrag 10 Werte (6 Seiten, Ueberlauf, yMin, yMax, frei). */
        java.util.List<LodGpuCommit> lodMeshed(java.util.List<LodGpuJob> jobs, int[] counts);

        /** Angenommene Auftraege, die nie fertig werden (Batches verworfen, z. B. neuer Generator). */
        void lodCancelled(java.util.List<LodGpuJob> jobs);
    }

    public static final int LOD_BATCH = 8;
    private static final int LOD_TMP_PER_DIR = 24 * 1024;
    private static final int COMMIT_BYTES = 88; // lod_mesher.slang CommitJob: 8 + 7 + 7 uints
    private static final int LAT = 37;
    private long layoutLodGen, pipeLodGen, layoutLodMesh, pipeLodMesh;
    // GPU-Zeiten der LOD-Durchgaenge (eigene Query-Pool: Commit, F, C, S, COL, DECO, RUNS, Oberkanten, Greedy)
    private static final int LG_TS = 16;
    private long lgQueryPool;
    private final double[] lgMsSum = new double[7]; // je Stufe + gesamt
    private int lgMsN;
    private int lgJobsSum;
    private double lgBudgetMs = 1.5;

    // Messung: -Dvulkanfish.lodBench=<Stufe> generiert jeden Frame dieselben 8 Knoten (ohne Uebernahme)
    private static final int LOD_BENCH = Integer.getInteger("vulkanfish.lodBench", -1);

    private static java.util.List<LodGpuJob> benchJobs() {
        java.util.List<LodGpuJob> l = new java.util.ArrayList<>();
        for (int i = 0; i < Integer.getInteger("vulkanfish.lodBenchN", LOD_BATCH); i++) l.add(new LodGpuJob(-1, 0, LOD_BENCH, 40 + i, 17, true, null, null));
        return l;
    }

    public void setLodGpuBudget(double ms) {
        lgBudgetMs = Math.max(0.1, ms);
    }
    private simon.vulkanfish.client.lod.gen.NoiseTerms lgTerms;
    private Buf lgTermBuf, lgTermBase;
    private final Buf[] lgJobTerm = new Buf[FRAMES];
    private Buf lodBiomeTable, lodClimate, lodClimateBiome, lodClimateNode, lodMat, lodMatTint, lodBiomeCol;
    private Buf lgFlat, lgInterp, lgSamples, lgColumn, lgGrid, lgTop;
    private final Buf[] lgJobs = new Buf[FRAMES], lgMeshJobs = new Buf[FRAMES], lgCounts = new Buf[FRAMES];
    private final Buf[] lgTmp = new Buf[FRAMES], lgCommit = new Buf[FRAMES];
    private final Buf[] lgRunHead = new Buf[FRAMES], lgRuns = new Buf[FRAMES];
    private int lgSamplesPerJob, lgInterpPerJob, lgFlatPerJob, lgGridPerJob;
    private int lgStoneMat, lgWaterMat, lgLavaMat, lgFallbackBiome, lgClimatePoints;
    private int[] lgClimateRegs = new int[6];
    private int lgProgD;
    private int lgUploadedMats, lgUploadedBiomes;
    private LodGpuClient lodClient;
    private boolean lodGpuReady;

    /**
     * LOD-GPU-Pfad einrichten (nach setupGenerator: Programm/Noise-Tabellen liegen schon auf der GPU).
     * biomeTable/climate aus LodBiomeTable; stone/water/lava = Material-IDs.
     */
    public boolean setupLodGpu(simon.vulkanfish.client.lod.gen.DensityProgram p, int[] biomeTable,
                               simon.vulkanfish.client.lod.gen.ClimateTree tree, int fallbackBiome, int stoneMat, int waterMat, int lavaMat, LodGpuClient client) {
        lodGpuReady = false;
        if (pipeLodGen == 0L || pipeLodMesh == 0L || !genReady || p.regCount[3] > 32) return false;
        try (Arena arena = new Arena()) {
            waitAll();
            int stor = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, uni = VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
            int host = VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
            int dev = VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
            if (lodBiomeTable == null) {
                lodBiomeTable = makeBuffer(arena, biomeTable.length * 4L, stor | uni, host);
                lodMat = makeBuffer(arena, 65536L * 16, stor | uni, host);
                lodMatTint = makeBuffer(arena, 65536L * 4, stor | uni, host);
                lodBiomeCol = makeBuffer(arena, 256L * 16, stor | uni, host);
                lgFlat = makeBuffer(arena, (long) LOD_BATCH * LAT * LAT * Math.max(1, p.flatSlots) * 4, stor, dev);
                int latY = genHeight / 8 + 1;
                lgInterp = makeBuffer(arena, (long) LOD_BATCH * LAT * LAT * latY * Math.max(1, p.interpSlots) * 4, stor, dev);
                lgSamples = makeBuffer(arena, (long) LOD_BATCH * 35 * (genHeight + 1) * 2 * 4, stor, dev);
                lgColumn = makeBuffer(arena, (long) LOD_BATCH * 34 * 34 * 16, stor, dev);
                lgGrid = makeBuffer(arena, (long) LOD_BATCH * 34 * 34 * genHeight * 8, stor, dev);
                lgTop = makeBuffer(arena, (long) LOD_BATCH * 34 * 34 * 4, stor, dev);
                LongBuffer qp = arena.mallocLong(1);
                check(VK10.vkCreateQueryPool(this.dev, VkQueryPoolCreateInfo.calloc(arena.stack()).sType$Default()
                        .queryType(VK10.VK_QUERY_TYPE_TIMESTAMP).queryCount(FRAMES * LG_TS), null, qp), "lgQueryPool");
                lgQueryPool = qp.get(0);
                for (int i = 0; i < FRAMES; i++) {
                    lgJobs[i] = makeBuffer(arena, LOD_BATCH * 32L, stor | uni, host);
                    lgMeshJobs[i] = makeBuffer(arena, LOD_BATCH * 16L, stor | uni, host);
                    lgCounts[i] = makeBuffer(arena, LOD_BATCH * 40L, stor | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, host);
                    lgTmp[i] = makeBuffer(arena, (long) LOD_BATCH * 7 * LOD_TMP_PER_DIR * 8, stor, dev); // 6 Seiten + Wasser
                    lgCommit[i] = makeBuffer(arena, LOD_BATCH * (long) COMMIT_BYTES, stor | uni, host);
                    lgRunHead[i] = makeBuffer(arena, LOD_BATCH * 34L * 34 * 8, stor | uni, host);
                    lgRuns[i] = makeBuffer(arena, 1L << 20, stor | uni, host);
                }
            }
            if (lodClimate != null) destroyBuffer(lodClimate);
            if (lodClimateBiome != null) destroyBuffer(lodClimateBiome);
            if (lodClimateNode != null) destroyBuffer(lodClimateNode);
            // Noise-Terme (float-Koordinaten je Knoten, siehe NoiseTerms)
            lgTerms = new simon.vulkanfish.client.lod.gen.NoiseTerms(p);
            if (lgTermBuf != null) destroyBuffer(lgTermBuf);
            if (lgTermBase != null) destroyBuffer(lgTermBase);
            int nTerms = Math.max(1, lgTerms.count());
            lgTermBuf = makeBuffer(arena, nTerms * 48L, stor | uni, host);
            lgTerms.writeStatic(MemoryUtil.memByteBuffer(lgTermBuf.mapped(), nTerms * 48));
            lgTermBase = makeBuffer(arena, Math.max(1, lgTerms.termBase.length) * 4L, stor | uni, host);
            MemoryUtil.memIntBuffer(lgTermBase.mapped(), lgTerms.termBase.length).put(lgTerms.termBase);
            for (int i = 0; i < FRAMES; i++) {
                if (lgJobTerm[i] != null) destroyBuffer(lgJobTerm[i]);
                lgJobTerm[i] = makeBuffer(arena, (long) LOD_BATCH * nTerms * 16, stor | uni, host);
            }
            double[] climate = tree.climate;
            int[] climateBiome = tree.climateBiome;
            lodClimateNode = makeBuffer(arena, Math.max(16, tree.nodes.length) * 4L, stor | uni, host);
            MemoryUtil.memByteBuffer(lodClimateNode.mapped(), tree.nodes.length * 4).asFloatBuffer().put(tree.nodes);
            lodClimate = makeBuffer(arena, Math.max(8, climate.length) * 8L, stor | uni, host);
            lodClimateBiome = makeBuffer(arena, Math.max(1, climateBiome.length) * 4L, stor | uni, host);
            MemoryUtil.memByteBuffer(lodClimate.mapped(), climate.length * 8).asDoubleBuffer().put(climate);
            MemoryUtil.memByteBuffer(lodClimateBiome.mapped(), climateBiome.length * 4).asIntBuffer().put(climateBiome);
            MemoryUtil.memByteBuffer(lodBiomeTable.mapped(), biomeTable.length * 4).asIntBuffer().put(biomeTable);
            lgClimatePoints = climateBiome.length;
            lgFallbackBiome = fallbackBiome;
            lgStoneMat = stoneMat;
            lgWaterMat = waterMat;
            lgLavaMat = lavaMat;
            lgProgD = genProgOffset[2] + p.code[2].size(); // Stufe D liegt hinter V (siehe setupGenerator)
            System.arraycopy(p.directResults, 0, lgClimateRegs, 0, Math.min(6, p.directResults.length));
            lgFlatPerJob = LAT * LAT * Math.max(1, p.flatSlots);
            lgInterpPerJob = LAT * LAT * (genHeight / 8 + 1) * Math.max(1, p.interpSlots);
            lgSamplesPerJob = 35 * (genHeight + 1) * 2;
            lgGridPerJob = 34 * 34 * genHeight;
            lgUploadedMats = 0;
            lgUploadedBiomes = 0;
            // Laufende Batches werden verworfen: ihre Auftraege dem (bisherigen) Client zurueckgeben,
            // sonst bleiben die Knoten fuer immer "im Bau" und der Auftragszaehler waechst je Weltwechsel
            if (lodClient != null && LOD_BENCH < 0) {
                java.util.List<LodGpuJob> dropped = new java.util.ArrayList<>();
                if (lbBuilding != null) dropped.addAll(lbBuilding.jobs);
                for (LodBatch b : lbWaiting) dropped.addAll(b.jobs);
                if (!dropped.isEmpty()) lodClient.lodCancelled(dropped);
            }
            resetLodBatches();
            lodClient = client;
            lodGpuReady = true;
            LOG.info("[vulkanfish] LOD-GPU: Generierung + Meshing aktiv ({} Knoten pro Frame, {} Klimapunkte, D-Stufe {} Register)",
                    LOD_BATCH, lgClimatePoints, p.regCount[3]);
            return true;
        } catch (Throwable t) {
            LOG.warn("[vulkanfish] LOD-GPU-Setup fehlgeschlagen", t);
            return false;
        }
    }

    public boolean lodGpuReady() {
        return lodGpuReady;
    }

    /** Neue Materialien/Biome in die GPU-Tabellen schreiben (nur Zuwachs). */
    private int lgMatGeneration = -1;

    private void updateLodTables() {
        int gen = simon.vulkanfish.client.lod.LodMaterials.generation();
        if (gen != lgMatGeneration) {
            // Farben neu bestimmt (Resource-Reload): ganze Tabelle neu hochladen
            lgMatGeneration = gen;
            lgUploadedMats = 0;
        }
        var mats = simon.vulkanfish.client.lod.LodMaterials.snapshot();
        if (mats.length > lgUploadedMats) {
            ByteBuffer mb = MemoryUtil.memByteBuffer(lodMat.mapped(), 65536 * 16);
            ByteBuffer tb = MemoryUtil.memByteBuffer(lodMatTint.mapped(), 65536 * 4);
            for (int i = lgUploadedMats; i < mats.length && i < 65536; i++) {
                var m = mats[i];
                if (m == null) continue;
                int tint = m.tintType();
                for (int f = 0; f < 3; f++) {
                    int c = m.color()[f] & 0xFFFFFF;
                    int t = m.tinted()[f] ? tint : 0;
                    mb.putInt(i * 16 + f * 4, c | (t << 24));
                }
                int kind = m.kind();
                mb.putInt(i * 16 + 12, (kind & 7) | ((m.emission() & 15) << 4) | (m.fullCover() ? 1 << 8 : 0)
                        | ((m.texId() & 0x1FFF) << 16));
                tb.putInt(i * 4, m.constTint());
            }
            lgUploadedMats = mats.length;
        }
        int biomes = simon.vulkanfish.client.lod.LodMaterials.biomeCount();
        if (biomes > lgUploadedBiomes) {
            ByteBuffer bb = MemoryUtil.memByteBuffer(lodBiomeCol.mapped(), 256 * 16);
            for (int i = Math.max(0, lgUploadedBiomes); i < biomes && i < 256; i++) {
                int[] c = simon.vulkanfish.client.lod.LodMaterials.biomeColors(i);
                for (int k = 0; k < 4; k++) bb.putInt(i * 16 + k * 4, c[k]);
            }
            lgUploadedBiomes = biomes;
        }
    }

    private void lodGenPush(Arena arena, VkCommandBuffer cmd, int pass, int jobs, int jobFirst) {
        ByteBuffer pc = arena.malloc(128);
        int[] v = {pass, jobs, genProgOffset[0], genProgOffset[1], genProgOffset[2], lgProgD, genFlatSlots, genInterpSlots,
                genResultReg, genMinY, genHeight, genSea, genLava, lgClimatePoints, lgStoneMat, lgWaterMat, lgLavaMat,
                lgFallbackBiome, lgClimateRegs[0], lgClimateRegs[1], lgClimateRegs[2], lgClimateRegs[3], lgClimateRegs[4],
                lgClimateRegs[5], lgSamplesPerJob, lgInterpPerJob, lgFlatPerJob, lgGridPerJob, lgTerms.count(), 0, jobFirst, 0};
        for (int i = 0; i < v.length; i++) pc.putInt(i * 4, v[i]);
        VK10.vkCmdPushConstants(cmd, layoutLodGen, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
    }

    private void lodMeshPush(Arena arena, VkCommandBuffer cmd, int pass, int jobs, int commits) {
        ByteBuffer pc = arena.malloc(32);
        pc.putInt(0, pass).putInt(4, jobs).putInt(8, genHeight).putInt(12, lgGridPerJob).putInt(16, LOD_TMP_PER_DIR)
                .putInt(20, commits).putInt(24, 0).putInt(28, 0);
        VK10.vkCmdPushConstants(cmd, layoutLodMesh, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
    }

    private void computeBarrier(Arena arena, VkCommandBuffer cmd) {
        barrier(arena, cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
    }

    /**
     * LOD pro Frame (Render-Thread, im Command-Buffer des Frames). Ein Batch (bis LOD_BATCH
     * Knoten) laeuft in Stufen ueber mehrere Frames: F, C (in Teilmengen der Knoten), S,
     * Saeulen, Oberkanten, Greedy. Jeder Frame fuehrt so viele Stufen aus, wie das Zeitbudget
     * nach gemessenen Kosten (je Stufe und LOD-Stufe) erlaubt – mindestens eine. Fertig
     * gemeshte Batches werden FRAMES Frames spaeter uebernommen (Anzahlen lesen, Pool zuteilen,
     * Kopie + Meshlet-Header).
     */
    private void recordLodGpu(Arena arena, VkCommandBuffer cmd, int slot) {
        if (!lodGpuReady || lodClient == null) return;
        lodFrame++;
        long now = System.nanoTime();
        if (lodLastFrameNs != 0L) lodFrameMsEma = lodFrameMsEma * 0.95 + (now - lodLastFrameNs) / 1e6 * 0.05;
        lodLastFrameNs = now;
        updateLodTables();
        lodReadTimestamps(arena, slot);
        // Budget: fest eingestellt, mehr wenn die GPU sonst warten wuerde (CPU-Limit, FPS-Grenze)
        double idle = lodFrameMsEma - lodGpuFrameMsEma - 1.0;
        double budget = Math.min(Math.max(lgBudgetMs, idle), 8.0);
        int tq = slot * LG_TS;
        VK10.vkCmdResetQueryPool(cmd, lgQueryPool, tq, LG_TS);
        VK10.vkCmdWriteTimestamp(cmd, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, lgQueryPool, tq);
        lgUnits[slot] = 0;
        // Reihenfolge gegen die LOD-Arbeit frueherer Frames (gemeinsame Zwischenpuffer)
        computeBarrier(arena, cmd);
        // 1) Uebernahme eines fertigen Batches (lgCommit[slot] fasst einen je Frame)
        LodBatch w = lbWaiting.peek();
        if (w != null && lodFrame >= w.readyFrame) {
            lbWaiting.poll();
            lodCommit(arena, cmd, slot, w);
            lbBufFree[w.buf] = lodFrame + FRAMES; // lgTmp/lgMeshJobs liest dieser Frame noch
        }
        // 2) Stufen im Budget
        double spent = 0;
        while (lgUnits[slot] < LG_TS - 1) {
            if (lbBuilding == null && !lodStartBatch(arena)) break;
            LodBatch bt = lbBuilding;
            int stage = bt.stage, first = 0, count = bt.n;
            double est;
            if (stage == ST_C) {
                // C in Teilmengen: so viele Knoten, wie ins Restbudget passen (mindestens einer)
                first = bt.cursor;
                count = 0;
                est = 0;
                while (first + count < bt.n) {
                    double c = lgUnitCost[ST_C][bt.levels[first + count]];
                    if (count > 0 && spent + est + c > budget) break;
                    est += c;
                    count++;
                }
            } else {
                est = 0;
                for (int i = 0; i < bt.n; i++) est += lgUnitCost[stage][bt.levels[i]];
            }
            if (lgUnits[slot] > 0 && spent + est > budget) break;
            lodRecordStage(arena, cmd, bt, stage, first, count);
            spent += est;
            int u = lgUnits[slot]++;
            lgUnitStage[slot][u] = stage;
            lgUnitLevels[slot][u] = java.util.Arrays.copyOfRange(bt.levels, first, first + count);
            VK10.vkCmdWriteTimestamp(cmd, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, lgQueryPool, tq + 1 + u);
            // naechste Stufe
            if (stage == ST_C && first + count < bt.n) {
                bt.cursor = first + count;
            } else if (stage == ST_MESH1) {
                barrier(arena, cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_HOST_BIT,
                        VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_HOST_READ_BIT);
                bt.readyFrame = lodFrame + FRAMES;
                lbWaiting.add(bt);
                lbBuilding = null;
            } else {
                bt.stage = stage + 1;
                bt.cursor = 0;
            }
        }
        lgTsSlotValid[slot] = lgUnits[slot] > 0;
    }

    private static final int ST_F = 0, ST_C = 1, ST_S = 2, ST_COL = 3, ST_MESH0 = 4, ST_MESH1 = 5;
    private static final String[] ST_NAMES = {"F", "C", "S", "Saeulen", "Oberkanten", "Greedy"};

    private static final class LodBatch {
        java.util.List<LodGpuJob> jobs;
        int n, buf, stage, cursor;
        int[] levels;
        boolean anyGen, anyRuns;
        long readyFrame;
    }

    private LodBatch lbBuilding;
    private final java.util.ArrayDeque<LodBatch> lbWaiting = new java.util.ArrayDeque<>();
    private final long[] lbBufFree = new long[FRAMES];
    private final boolean[] lbBufUsed = new boolean[FRAMES];
    // gemessene GPU-Zeit je Knoten: [Stufe][LOD-Stufe] (Startwerte grob, gleiten auf die Messung)
    private final double[][] lgUnitCost = {
            {0.03, 0.03, 0.03, 0.03, 0.03}, {0.15, 0.3, 0.6, 0.6, 0.4}, {0.08, 0.06, 0.05, 0.03, 0.03},
            {0.1, 0.1, 0.1, 0.1, 0.1}, {0.1, 0.15, 0.15, 0.1, 0.06}, {0.2, 0.1, 0.06, 0.04, 0.03}};
    private final int[] lgUnits = new int[FRAMES];
    private final int[][] lgUnitStage = new int[FRAMES][LG_TS];
    private final int[][][] lgUnitLevels = new int[FRAMES][LG_TS][];
    private final boolean[] lgTsSlotValid = new boolean[FRAMES];
    private long lodFrame, lodLastFrameNs;
    private double lodFrameMsEma = 6.0, lodGpuFrameMsEma = 6.0;

    /** Zeitstempel des Slots (FRAMES Frames alt) auslesen: Kosten je Stufe nachfuehren, Statistik. */
    private void lodReadTimestamps(Arena arena, int slot) {
        if (!lgTsSlotValid[slot]) return;
        lgTsSlotValid[slot] = false;
        int units = lgUnits[slot];
        LongBuffer ts = arena.mallocLong(LG_TS);
        if (VK10.vkGetQueryPoolResults(dev, lgQueryPool, slot * LG_TS, units + 1, ts, 8, VK10.VK_QUERY_RESULT_64_BIT) != VK10.VK_SUCCESS) return;
        double frameMs = 0;
        for (int u = 0; u < units; u++) {
            double ms = Math.max(0, ts.get(u + 1) - ts.get(u)) * timestampPeriodNs / 1e6;
            frameMs += ms;
            int st = lgUnitStage[slot][u];
            int[] lv = lgUnitLevels[slot][u];
            lgMsSum[st] += ms;
            if (lv == null || lv.length == 0) continue;
            double est = 0;
            for (int l : lv) est += lgUnitCost[st][l];
            for (int l : lv) lgUnitCost[st][l] = lgUnitCost[st][l] * 0.8 + ms * lgUnitCost[st][l] / est * 0.2;
        }
        lgMsSum[ST_NAMES.length] += frameMs;
        lgMsN++;
        lodLastLodMs = frameMs;
    }

    private double lodLastLodMs;
    private final double[] waterPartMs = new double[4];
    private int waterPartN;
    private double spanMsSum;
    /** Wartezeit des Render-Threads auf die GPU (Slot-Wiederverwendung), fuer die Frame-Analyse. */
    public long slotWaitNs;

    /** Neuen Batch aufnehmen (Puffer frei?), Auftraege in die Host-Puffer schreiben. */
    private boolean lodStartBatch(Arena arena) {
        int a = -1;
        for (int i = 0; i < FRAMES; i++) {
            if (!lbBufUsed[i] && lodFrame >= lbBufFree[i]) {
                a = i;
                break;
            }
        }
        if (a < 0) return false;
        var jobs = LOD_BENCH >= 0 ? benchJobs() : lodClient.nextLodJobs(LOD_BATCH);
        if (jobs == null || jobs.isEmpty()) return false;
        int n = Math.min(LOD_BATCH, jobs.size());
        LodBatch bt = new LodBatch();
        bt.jobs = new java.util.ArrayList<>(jobs.subList(0, n));
        bt.n = n;
        bt.buf = a;
        bt.levels = new int[n];
        for (int i = 0; i < n; i++) bt.levels[i] = jobs.get(i).level();
        ByteBuffer jb = MemoryUtil.memByteBuffer(lgJobs[a].mapped(), LOD_BATCH * 32);
        ByteBuffer mj = MemoryUtil.memByteBuffer(lgMeshJobs[a].mapped(), LOD_BATCH * 16);
        IntBufferView counts = new IntBufferView(MemoryUtil.memIntBuffer(lgCounts[a].mapped(), LOD_BATCH * 10));
        int runWords = 0;
        for (int i = 0; i < n; i++) if (jobs.get(i).runs() != null) runWords += jobs.get(i).runs().length;
        if ((long) runWords * 4 > lgRuns[a].size()) {
            destroyBuffer(lgRuns[a]);
            lgRuns[a] = makeBuffer(arena, Math.max((long) runWords * 4 * 2, 1L << 20),
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                    VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        }
        java.nio.IntBuffer heads = MemoryUtil.memIntBuffer(lgRunHead[a].mapped(), LOD_BATCH * 34 * 34 * 2);
        java.nio.IntBuffer runs = MemoryUtil.memIntBuffer(lgRuns[a].mapped(), (int) (lgRuns[a].size() / 4));
        int runPos = 0;
        for (int i = 0; i < n; i++) {
            LodGpuJob job = jobs.get(i);
            int flags = (job.generate() ? 1 : 0) | (job.runHeads() != null ? 2 : 0);
            bt.anyGen |= job.generate();
            int o = i * 32;
            jb.putInt(o, job.level()).putInt(o + 4, job.nx()).putInt(o + 8, job.nz()).putInt(o + 12, flags)
                    .putInt(o + 16, i * 34 * 34).putInt(o + 20, 0).putInt(o + 24, 0).putInt(o + 28, 0);
            mj.putInt(i * 16, job.level()).putInt(i * 16 + 4, job.nx()).putInt(i * 16 + 8, job.nz()).putInt(i * 16 + 12, 0);
            if (job.generate()) {
                // wie blockX0/blockZ0 in lod_gen.slang
                lgTerms.writeAnchor(MemoryUtil.memByteBuffer(lgJobTerm[a].mapped(), (int) lgJobTerm[a].size()),
                        i * lgTerms.count() * 16, (job.nx() * 32 - 1) << job.level(), (job.nz() * 32 - 1) << job.level());
            }
            if (job.runHeads() != null) {
                bt.anyRuns = true;
                int runBase = runPos / 4;
                for (int c = 0; c < 34 * 34; c++) {
                    int start = job.runHeads()[c * 2], cnt = job.runHeads()[c * 2 + 1];
                    heads.put((i * 34 * 34 + c) * 2, cnt == -1 ? 0 : start + runBase);
                    heads.put((i * 34 * 34 + c) * 2 + 1, cnt);
                }
                runs.put(runPos, job.runs());
                runPos += job.runs().length;
            }
            for (int k = 0; k < 10; k++) counts.set(i * 10 + k, k == 7 ? -1 : 0);
        }
        bt.stage = bt.anyGen ? ST_F : ST_COL;
        lbBufUsed[a] = true;
        lbBuilding = bt;
        lgJobsSum += n;
        return true;
    }

    private void lodBindGen(Arena arena, VkCommandBuffer cmd, int a) {
        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeLodGen);
        push(arena, cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layoutLodGen,
                W.sb(0, genProg), W.sb(1, genConst), W.sb(2, genPerm), W.sb(3, genOffs), W.sb(4, genOct),
                W.sb(5, genNormal), W.sb(6, genNormalFactor), W.sb(7, genBlendD), W.sb(8, genBlendOct),
                W.sb(9, lgJobs[a]), W.sb(10, lgFlat), W.sb(11, lgInterp), W.sb(12, lgSamples), W.sb(13, lgColumn),
                W.sb(14, lgGrid), W.sb(15, lodBiomeTable), W.sb(16, lodClimate), W.sb(17, lodClimateBiome),
                W.sb(18, lgRunHead[a]), W.sb(19, lgRuns[a]), W.sb(20, lodClimateNode),
                W.sb(21, lgTermBuf), W.sb(22, lgTermBase), W.sb(23, lgJobTerm[a]));
    }

    /** Eine Stufe des Batches aufzeichnen (Knoten first..first+count bei C, sonst alle). */
    private void lodRecordStage(Arena arena, VkCommandBuffer cmd, LodBatch bt, int stage, int first, int count) {
        int a = bt.buf, n = bt.n;
        switch (stage) {
            case ST_F, ST_C, ST_S -> {
                lodBindGen(arena, cmd, a);
                int latY = genHeight / 8 + 1;
                int threads = stage == ST_F ? count * LAT * LAT : stage == ST_C ? count * LAT * LAT * latY : count * 35 * (genHeight + 1);
                lodGenPush(arena, cmd, stage, count, first);
                VK10.vkCmdDispatch(cmd, (threads + 63) / 64, 1, 1);
                computeBarrier(arena, cmd);
            }
            case ST_COL -> {
                lodBindGen(arena, cmd, a);
                if (bt.anyGen) {
                    for (int pass = 3; pass <= 4; pass++) {
                        lodGenPush(arena, cmd, pass, n, 0);
                        VK10.vkCmdDispatch(cmd, (n * 34 * 34 + 63) / 64, 1, 1);
                        computeBarrier(arena, cmd);
                    }
                }
                if (bt.anyRuns || !bt.anyGen) {
                    lodGenPush(arena, cmd, 5, n, 0);
                    VK10.vkCmdDispatch(cmd, (n * 34 * 34 + 63) / 64, 1, 1);
                    computeBarrier(arena, cmd);
                }
            }
            case ST_MESH0, ST_MESH1 -> {
                VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeLodMesh);
                push(arena, cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layoutLodMesh, lodMeshBindings(a, 0));
                lodMeshPush(arena, cmd, stage == ST_MESH0 ? 0 : 1, n, 0);
                VK10.vkCmdDispatch(cmd, stage == ST_MESH0 ? (n * 34 * 34 + 63) / 64 : n * (2 * 384 + 4 * 32 * 6), 1, 1);
                computeBarrier(arena, cmd);
            }
            default -> {
            }
        }
    }

    /** Gemeshten Batch (GPU seit FRAMES Frames fertig) uebernehmen: Anzahlen lesen, Pool zuteilen, Kopie. */
    private void lodCommit(Arena arena, VkCommandBuffer cmd, int slot, LodBatch bt) {
        lbBufUsed[bt.buf] = false;
        if (LOD_BENCH >= 0) return;
        int[] counts = new int[bt.n * 10];
        MemoryUtil.memIntBuffer(lgCounts[bt.buf].mapped(), counts.length).get(counts);
        var commits = lodClient.lodMeshed(bt.jobs, counts);
        if (commits == null || commits.isEmpty()) return;
        ByteBuffer cb = MemoryUtil.memByteBuffer(lgCommit[slot].mapped(), LOD_BATCH * COMMIT_BYTES);
        int meshlets = 0;
        for (int c = 0; c < commits.size(); c++) {
            LodGpuCommit cm = commits.get(c);
            int o = c * COMMIT_BYTES;
            cb.putInt(o, cm.jobIndex()).putInt(o + 4, cm.quadStart()).putInt(o + 8, cm.meshletStart()).putInt(o + 12, cm.cluster())
                    .putInt(o + 16, cm.level()).putInt(o + 20, cm.nx()).putInt(o + 24, cm.nz()).putInt(o + 28, genMinY);
            int rel = 0;
            for (int d = 0; d < 7; d++) { // 6 Seiten + Wasseroberflaeche
                cb.putInt(o + 32 + d * 4, cm.dirQuads()[d]);
                cb.putInt(o + 60 + d * 4, rel);
                rel += (cm.dirQuads()[d] + 31) / 32;
            }
            meshlets += rel;
        }
        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeLodMesh);
        push(arena, cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layoutLodMesh, lodMeshBindings(bt.buf, slot));
        lodMeshPush(arena, cmd, 2, 0, commits.size());
        VK10.vkCmdDispatch(cmd, (meshlets + 63) / 64, 1, 1);
        barrier(arena, cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
    }

    private W[] lodMeshBindings(int a, int frameSlot) {
        return new W[]{W.sb(0, lgGrid), W.sb(1, lodMat), W.sb(2, lodMatTint), W.sb(3, lodBiomeCol), W.sb(4, lgTop),
                W.sb(5, lgCounts[a]), W.sb(6, lgTmp[a]), W.sb(7, lgMeshJobs[a]), W.sb(8, lgCommit[frameSlot]),
                W.sb(9, lodQuads), W.sb(10, lodMeshlets)};
    }

    /** Laufende Batches verwerfen (vor dem Ersetzen der Puffer; die GPU ist per waitAll fertig). */
    private void resetLodBatches() {
        lbBuilding = null;
        lbWaiting.clear();
        java.util.Arrays.fill(lbBufUsed, false);
        java.util.Arrays.fill(lbBufFree, 0L);
        java.util.Arrays.fill(lgTsSlotValid, false);
    }

    /** Kleiner Helfer: IntBuffer mit set(). */
    private record IntBufferView(java.nio.IntBuffer b) {
        void set(int i, int v) {
            b.put(i, v);
        }
    }

    public void stopGenerator() {
        genReady = false;
    }

    /** TAA wieder an: alte History verwerfen (sonst ein Frame Nachziehen eines alten Bildes). */
    public void resetTaaHistory() {
        historyValid = false;
    }

    public void setLod(simon.vulkanfish.client.lod.LodManager manager) {
        lod = manager;
    }
    private int lodClusterCount;
    private int[] lodMaskData;
    private int lodMaskCamX, lodMaskCamZ, lodMaskMinSY;
    private boolean lodMaskPending;
    private long layoutLod;
    private long pipeLod;
    private long pipeLodShadow; // Fernfeld in die Schattenkarte (wo das Nahfeld nichts zeichnet)
    private static final boolean NO_LOD_SHADOW = Boolean.getBoolean("vulkanfish.noLodShadow"); // Messung
    private Buf lodVisShadow, lodShadowIndirect;
    private Buf visBits;        // pro Meshlet-Slot: im letzten Frame sichtbar (Zwei-Phasen-Culling)
    private Buf meshIndirect2;
    private boolean visBitsCleared;
    private Buf visShadow;
    private Buf visMeshletCount;
    private Buf visShadowCount;
    private Buf visWater;
    private Buf waterIndirect;
    private Buf visTrans;
    private Buf transIndirect;
    private Buf meshIndirect;
    private Buf shadowIndirect;
    private Buf meshletTotal;
    private final Buf[] uniforms = new Buf[FRAMES];
    private final Buf[] shadowUniforms = new Buf[FRAMES];
    private final Buf[] staging = new Buf[FRAMES];
    private int maxClusters;
    private int maxMeshlets;
    private int maxVerts;
    private int maxTris;

    // Upload-Ring des aktuellen Frames
    private int currentSlot;
    private long stagingCursor;
    private int meshletCount;
    private int clusterCount;
    private final List<long[]> copies = new ArrayList<>(); // {target, srcOff, dstOff, size}
    private final List<long[]> fills = new ArrayList<>();  // {target, dstOff, size}

    // Images (Main-Target-gross, bei Resize neu) + Schatten-Map (fest)
    private int width;
    private int height;
    private boolean imagesInitialized;
    private Img gAlbedo;
    private Img gNormal;
    private Img depth;
    private Img hiz;
    private long[] hizMipViews = new long[0];
    private Img hdr;
    private Img bloom;
    private long[] bloomMipViews = new long[0];
    private Img ldr;
    private Img background;
    private Img sceneCopy;   // Main-Target-Farbe vor dem Wasser (Brechung/SSR)
    private Img sceneDepth;  // Main-Tiefe vor dem Wasser (Terrain + Entities)
    private Img oitHead;     // R32UI: Kopf der Fragment-Liste pro Pixel
    private Img oitFront;    // RGBA16F: Glas vor dem Wasser, vormultipliziert (rgb, Resttransmission)
    private Img oitFrontDepth; // R32F: Tiefe der naechsten Glasschicht davor (0 = keine)
    private Img shadowMap;
    private boolean shadowInitialized;

    private boolean loggedOnce;
    private volatile int snapshotSource = -1; // -1=aus, 0=Endbild, 1=Albedo, 2=Normale/Licht

    // allocation = VmaAllocation (Mojangs Allocator)
    private record Buf(long buffer, long allocation, long size, long mapped) {}
    private record Img(long image, long allocation, long view, int w, int h, int mips, int format) {}

    private static final int SB = VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    private static final int UB = VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    private static final int SI = VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
    private static final int SM = VK10.VK_DESCRIPTOR_TYPE_SAMPLER;
    private static final int ST = VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;

    public NativePassRunner(BlazeDeviceInterop blaze, boolean hizEnabled) {
        this.blaze = blaze;
        this.hizEnabled = hizEnabled;
    }

    public boolean init(SlangShaderLoader loader) {
        try (Arena arena = new Arena()) {
            dev = blaze.vkDevice();
            vma = blaze.vmaAllocator();
            phys = blaze.vkPhysicalDevice();
            gfxQ = blaze.graphicsQueue();
            gfxFam = blaze.graphicsQueueFamily();
            if (dev.getCapabilities().vkCmdDrawMeshTasksIndirectEXT == 0L) {
                return fail("vkCmdDrawMeshTasksIndirectEXT fehlt auf Mojangs Device (Mixin nicht aktiv?)");
            }
            rtWanted = MeshShaderSupport.rayQueryEnabled()
                    && dev.getCapabilities().vkCmdBuildAccelerationStructuresKHR != 0L;
            createSamplers(arena);
            createFrameObjects(arena);
            createScene(arena);
            if (!createPipelines(arena, loader)) return fail("Pipeline-Erstellung fehlgeschlagen");
            if (rtWanted) initRt(arena, loader);
            initLod(arena, loader);
            initEntityShadow(arena, loader);
            shadowMap = makeImage(arena, SHADOW_RES, SHADOW_RES, 1, FMT_DEPTH,
                    VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT
                            | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK10.VK_IMAGE_ASPECT_DEPTH_BIT);
            ready = true;
            LOG.info("[vulkanfish] NativePassRunner bereit (Mojang-Device, Queue-Familie {}, hiz={}, Schatten {}²)",
                    gfxFam, hizEnabled, SHADOW_RES);
            return true;
        } catch (Throwable t) {
            LOG.warn("[vulkanfish] NativePassRunner-Init fehlgeschlagen", t);
            return fail("Init-Exception: " + t);
        }
    }

    private boolean fail(String reason) {
        disableReason = reason;
        LOG.warn("[vulkanfish] NativePassRunner deaktiviert: {}", reason);
        return false;
    }

    public boolean isReady() {
        return ready;
    }

    public String disableReason() {
        return disableReason;
    }

    // ---------- Erstellung ----------

    private void createSamplers(Arena arena) {
        VkSamplerCreateInfo si = VkSamplerCreateInfo.calloc(arena.stack()).sType$Default()
                .magFilter(VK10.VK_FILTER_LINEAR).minFilter(VK10.VK_FILTER_LINEAR)
                .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .minLod(0).maxLod(16).maxAnisotropy(1.0f);
        LongBuffer p = arena.mallocLong(1);
        check(VK10.vkCreateSampler(dev, si, null, p), "linearSampler");
        linearSampler = p.get(0);
        // Block-Atlas: Pixel-Look wie Vanilla (Nearest), Mips gegen Flimmern in der Ferne
        si.magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST);
        check(VK10.vkCreateSampler(dev, si, null, p), "atlasSampler");
        atlasSampler = p.get(0);
        // Schatten: Hardware-Vergleich + bilinear = 2x2-PCF pro Tap
        si.magFilter(VK10.VK_FILTER_LINEAR).minFilter(VK10.VK_FILTER_LINEAR).maxLod(0)
                .compareEnable(true).compareOp(VK10.VK_COMPARE_OP_LESS_OR_EQUAL);
        check(VK10.vkCreateSampler(dev, si, null, p), "shadowSampler");
        shadowSampler = p.get(0);
    }

    private void createFrameObjects(Arena arena) {
        VkCommandPoolCreateInfo cpi = VkCommandPoolCreateInfo.calloc(arena.stack()).sType$Default()
                .flags(VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(gfxFam);
        LongBuffer p = arena.mallocLong(1);
        check(VK10.vkCreateCommandPool(dev, cpi, null, p), "pool");
        pool = p.get(0);
        VkCommandBufferAllocateInfo ai = VkCommandBufferAllocateInfo.calloc(arena.stack()).sType$Default()
                .commandPool(pool).level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
        PointerBuffer pb = arena.mallocPointer(1);
        for (int i = 0; i < FRAMES; i++) {
            check(VK10.vkAllocateCommandBuffers(dev, ai, pb), "cmd");
            cmds[i] = new VkCommandBuffer(pb.get(0), dev);
            check(VK10.vkAllocateCommandBuffers(dev, ai, pb), "waterCmd");
            waterCmds[i] = new VkCommandBuffer(pb.get(0), dev);
            check(VK10.vkAllocateCommandBuffers(dev, ai, pb), "taaCmd");
            taaCmds[i] = new VkCommandBuffer(pb.get(0), dev);
        }
        VkSemaphoreTypeCreateInfo type = VkSemaphoreTypeCreateInfo.calloc(arena.stack()).sType$Default()
                .semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE).initialValue(0L);
        VkSemaphoreCreateInfo sci = VkSemaphoreCreateInfo.calloc(arena.stack()).sType$Default().pNext(type.address());
        check(VK10.vkCreateSemaphore(dev, sci, null, p), "timeline");
        timeline = p.get(0);
        VkQueryPoolCreateInfo qi = VkQueryPoolCreateInfo.calloc(arena.stack()).sType$Default()
                .queryType(VK10.VK_QUERY_TYPE_TIMESTAMP).queryCount(FRAMES * TS_PER_FRAME);
        check(VK10.vkCreateQueryPool(dev, qi, null, p), "queryPool");
        queryPool = p.get(0);
        VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(arena.stack());
        VK10.vkGetPhysicalDeviceProperties(phys, props);
        timestampPeriodNs = props.limits().timestampPeriod();
    }

    /**
     * Puffer ueber MOJANGS VMA-Allocator (Unterallokation statt eigener vkAllocateMemory,
     * gemeinsames Budget mit Blaze3D). memProps: DEVICE_LOCAL oder HOST_VISIBLE|COHERENT;
     * host-sichtbare Puffer sind persistent gemappt (Staging/Uniforms schreibkombiniert,
     * GPU-geschriebene Zaehler/Readback mit Cache fuer schnelles Lesen).
     */
    private Buf makeBuffer(Arena arena, long size, int usage, int memProps) {
        VkBufferCreateInfo bi = VkBufferCreateInfo.calloc(arena.stack()).sType$Default()
                .size(size).usage(usage).sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);

        VmaAllocationCreateInfo aci = VmaAllocationCreateInfo.calloc(arena.stack());
        boolean host = (memProps & VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0;
        if (host) {
            boolean writeOnly = (usage & (VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT)) != 0;
            aci.usage(Vma.VMA_MEMORY_USAGE_AUTO)
                    .flags(Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT | (writeOnly
                            ? Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
                            : Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT))
                    .requiredFlags(VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        } else {
            aci.usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
        }
        LongBuffer pb = arena.mallocLong(1);
        PointerBuffer pa = arena.mallocPointer(1);
        VmaAllocationInfo info = VmaAllocationInfo.calloc(arena.stack());
        check(Vma.vmaCreateBuffer(vma, bi, aci, pb, pa, info), "vmaCreateBuffer " + (size >> 20) + " MiB");
        long mapped = host ? info.pMappedData() : 0L;
        if (mapped != 0L) MemoryUtil.memSet(mapped, 0, size);
        Buf b = new Buf(pb.get(0), pa.get(0), size, mapped);
        buffers.add(b);
        return b;
    }

    /** GPU-Scene so gross wie moeglich anlegen (halbiert bei Out-of-Memory). */
    private void createScene(Arena arena) {
        int stor = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        int dst = VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        int devLocal = VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
        for (int quads = MAX_QUADS; ; quads /= 2) {
            List<Buf> made = new ArrayList<>();
            try {
                maxVerts = quads * 4;
                maxTris = quads * 2;
                maxMeshlets = quads / SectionMesher.QUADS_PER_MESHLET * 2; // Luft fuer angebrochene Meshlets
                maxClusters = 65535; // = Workgroups des Cluster-Culls (maxComputeWorkGroupCount[0] >= 65535)
                made.add(clusters = makeBuffer(arena, (long) maxClusters * CLUSTER_BYTES, stor | dst, devLocal));
                made.add(meshlets = makeBuffer(arena, (long) maxMeshlets * SectionMesher.MESHLET_BYTES, stor | dst, devLocal));
                // Mit Raytracing liest der BLAS-Build die Positionen direkt aus diesem Puffer
                int rtUsage = rtWanted ? VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                        | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR : 0;
                made.add(verts = makeBuffer(arena, (long) maxVerts * SectionMesher.VERTEX_BYTES, stor | dst | rtUsage, devLocal));
                made.add(tris = makeBuffer(arena, (long) maxTris * 4L, stor | dst, devLocal));
                made.add(visMeshlets = makeBuffer(arena, (long) maxMeshlets * 4L, stor, devLocal));
                made.add(visMeshlets2 = makeBuffer(arena, (long) maxMeshlets * 4L, stor, devLocal));
                made.add(visBits = makeBuffer(arena, (long) maxMeshlets * 4L, stor | dst, devLocal));
                made.add(visShadow = makeBuffer(arena, (long) maxMeshlets * 4L, stor, devLocal));
                made.add(visWater = makeBuffer(arena, (long) maxMeshlets * 4L, stor, devLocal));
                made.add(visTrans = makeBuffer(arena, (long) maxMeshlets * 4L, stor, devLocal));
                LOG.info("[vulkanfish] GPU-Scene: {} Quads Kapazitaet ({} MiB)", quads,
                        ((long) maxVerts * SectionMesher.VERTEX_BYTES + maxTris * 4L
                                + (long) maxMeshlets * (SectionMesher.MESHLET_BYTES + 12)) >> 20);
                break;
            } catch (IllegalStateException e) {
                for (Buf b : made) destroyBuffer(b);
                if (quads / 2 < MIN_QUADS) throw e;
                LOG.warn("[vulkanfish] GPU-Scene mit {} Quads passt nicht ({}), versuche {}", quads, e.getMessage(), quads / 2);
            }
        }
        createBuffers(arena);
    }

    public int maxClusters() {
        return maxClusters;
    }

    public int maxMeshlets() {
        return maxMeshlets;
    }

    public int maxVerts() {
        return maxVerts;
    }

    public int maxTris() {
        return maxTris;
    }

    private void createBuffers(Arena arena) {
        int devLocal = VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
        int hostVis = VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        int stor = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        int ind = VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
        int uni = VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
        int dst = VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        int src = VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        meshIndirect = makeBuffer(arena, 16, stor | ind | dst, devLocal);
        meshIndirect2 = makeBuffer(arena, 16, stor | ind | dst, devLocal);
        shadowIndirect = makeBuffer(arena, 16, stor | ind | dst, devLocal);
        waterIndirect = makeBuffer(arena, 16, stor | ind | dst, hostVis); // host-lesbar fuer die Statistik
        transIndirect = makeBuffer(arena, 16, stor | ind | dst, devLocal);
        meshletTotal = makeBuffer(arena, 16, stor | dst, devLocal);
        oitCounter = makeBuffer(arena, 16, stor | dst, devLocal);
        // Statistik-Zaehler host-sichtbar (Log liest ihn, GPU zaehlt per Atomik)
        visMeshletCount = makeBuffer(arena, 16, stor | dst, hostVis);
        visShadowCount = makeBuffer(arena, 16, stor | dst, hostVis);
        for (int i = 0; i < FRAMES; i++) {
            uniforms[i] = makeBuffer(arena, UBO_BYTES, uni, hostVis);
            shadowUniforms[i] = makeBuffer(arena, UBO_BYTES, uni, hostVis);
            staging[i] = makeBuffer(arena, STAGING_BYTES, src, hostVis);
            taaUniforms[i] = makeBuffer(arena, TAA_UBO_BYTES, uni, hostVis);
        }
    }

    private long module(Arena arena, SlangShaderLoader loader, String entry) {
        ByteBuffer spv = loader.get(entry);
        VkShaderModuleCreateInfo ci = VkShaderModuleCreateInfo.calloc(arena.stack()).sType$Default().pCode(spv);
        LongBuffer p = arena.mallocLong(1);
        check(VK10.vkCreateShaderModule(dev, ci, null, p), "module " + entry);
        shaderModules.add(p.get(0));
        return p.get(0);
    }

    private long setLayout(Arena arena, int[][] bindings, int stageFlags) {
        VkDescriptorSetLayoutBinding.Buffer bb = VkDescriptorSetLayoutBinding.calloc(bindings.length, arena.stack());
        for (int i = 0; i < bindings.length; i++) {
            bb.get(i).binding(bindings[i][0]).descriptorType(bindings[i][1]).descriptorCount(1).stageFlags(stageFlags);
        }
        // PUSH_DESCRIPTOR_BIT wie Mojangs VulkanBindGroupLayout: keine Pools/Sets
        VkDescriptorSetLayoutCreateInfo ci = VkDescriptorSetLayoutCreateInfo.calloc(arena.stack()).sType$Default()
                .flags(KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR).pBindings(bb);
        LongBuffer p = arena.mallocLong(1);
        check(VK10.vkCreateDescriptorSetLayout(dev, ci, null, p), "setLayout");
        setLayouts.add(p.get(0));
        return p.get(0);
    }

    private long pipelineLayout(Arena arena, long setLayout, int pushStages, int pushSize) {
        VkPipelineLayoutCreateInfo ci = VkPipelineLayoutCreateInfo.calloc(arena.stack()).sType$Default();
        ci.pSetLayouts(arena.longs(setLayout));
        if (pushSize > 0) {
            VkPushConstantRange.Buffer pr = VkPushConstantRange.calloc(1, arena.stack());
            pr.get(0).stageFlags(pushStages).offset(0).size(pushSize);
            ci.pPushConstantRanges(pr);
        }
        LongBuffer p = arena.mallocLong(1);
        check(VK10.vkCreatePipelineLayout(dev, ci, null, p), "pipeLayout");
        pipelineLayouts.add(p.get(0));
        return p.get(0);
    }

    private long computePipe(Arena arena, long layout, long mod) {
        VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(arena.stack()).sType$Default()
                .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(mod).pName(arena.utf8("main"));
        VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, arena.stack()).sType$Default()
                .stage(stage).layout(layout);
        LongBuffer p = arena.mallocLong(1);
        if (VK10.vkCreateComputePipelines(dev, 0L, ci, null, p) != VK10.VK_SUCCESS) return 0L;
        pipelines.add(p.get(0));
        return p.get(0);
    }

    private boolean createPipelines(Arena arena, SlangShaderLoader loader) {
        int C = VK10.VK_SHADER_STAGE_COMPUTE_BIT;
        int M = EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT;
        int F = VK10.VK_SHADER_STAGE_FRAGMENT_BIT;

        // meshletCull: 0S 1S 2U 3I 4SMP 5S 6S 7S 8S
        // + 13S Sichtbarkeits-Bits, push 4 (Phase: 0 Schatten, 1/2 Kamera-Zwei-Phasen)
        layoutCull = pipelineLayout(arena,
                setLayout(arena, new int[][]{{0, SB}, {1, SB}, {2, UB}, {3, SI}, {4, SM}, {5, SB}, {6, SB}, {7, SB}, {8, SB},
                        {9, SB}, {10, SB}, {11, SB}, {12, SB}, {13, SB}, {14, SB}}, C), C, 32);
        pipeCull = computePipe(arena, layoutCull, module(arena, loader, "meshletCull"));
        // Terrain (G-Buffer + Schatten teilen das Layout): 0S 1S 2S 3U 4I 5SMP 6S
        layoutTerrain = pipelineLayout(arena,
                setLayout(arena, new int[][]{{0, SB}, {1, SB}, {2, SB}, {3, UB}, {4, SI}, {5, SM}, {6, SB}}, M | F), 0, 0);
        pipeGbuffer = meshPipe(arena, layoutTerrain, module(arena, loader, "terrainMeshDirect"),
                module(arena, loader, "terrainFrag"), 2, FMT_GBUF, VK10.VK_COMPARE_OP_GREATER_OR_EQUAL, false);
        pipeShadow = meshPipe(arena, layoutTerrain, module(arena, loader, "terrainShadowMesh"),
                module(arena, loader, "terrainShadowFrag"), 0, FMT_GBUF, VK10.VK_COMPARE_OP_LESS_OR_EQUAL, true);
        // hiz: 0I 1ST 2SMP + push 16 (dstSize, srcMip, seed)
        layoutHiz = pipelineLayout(arena, setLayout(arena, new int[][]{{0, SI}, {1, ST}, {2, SM}}, C), C, 16);
        pipeHiz = computePipe(arena, layoutHiz, module(arena, loader, "hizDownsample"));
        // deferred: 0I 1I 2I(Tiefe) 3I(Schatten) 4SMP(Vergleich) 5I(Hintergrund) 6ST 7U 8SMP
        layoutDeferred = pipelineLayout(arena, setLayout(arena,
                new int[][]{{0, SI}, {1, SI}, {2, SI}, {3, SI}, {4, SM}, {5, SI}, {6, ST}, {7, UB}, {8, SM}}, C), 0, 0);
        pipeDeferred = computePipe(arena, layoutDeferred, module(arena, loader, "deferredMain"));
        // bloom: 0I 1ST 2SMP + push 16 (dstSize, srcMip, firstPass)
        layoutBloom = pipelineLayout(arena, setLayout(arena, new int[][]{{0, SI}, {1, ST}, {2, SM}}, C), C, 16);
        pipeBloomDown = computePipe(arena, layoutBloom, module(arena, loader, "bloomDown"));
        pipeBloomUp = computePipe(arena, layoutBloom, module(arena, loader, "bloomUp"));
        // final: 0I(HDR) 1I(Bloom) 2ST 3U 4SMP
        layoutFinal = pipelineLayout(arena, setLayout(arena, new int[][]{{0, SI}, {1, SI}, {2, ST}, {3, UB}, {4, SM}}, C), 0, 0);
        pipeFinal = computePipe(arena, layoutFinal, module(arena, loader, "finalMain"));
        // Wasser: Terrain-Bindings 0-3/6 + 4I(Szene) 5SMP 7I(Szenentiefe) 8I(Schatten) 9SMP(Vergleich).
        // Pipeline selbst entsteht lazy im Format des Main-Targets (renderWater).
        // + 12ST(OIT-Kopf) 13S(OIT-Knoten) 14S(OIT-Zaehler) fuer Glas/Eis
        // + 15S(LOD-Quads) 16S(Nahfeld-Maske) 17S(Biomfarben), push 16 fuers Fernfeld-Wasser
        layoutWater = pipelineLayout(arena, setLayout(arena, new int[][]{{0, SB}, {1, SB}, {2, SB}, {3, UB}, {4, SI},
                {5, SM}, {6, SB}, {7, SI}, {8, SI}, {9, SM}, {10, SI}, {11, SM}, {12, ST}, {13, SB}, {14, SB},
                {15, SB}, {16, SB}, {17, SB}}, M | F), M | F, 16);
        waterMeshModule = module(arena, loader, "waterMesh");
        waterFragModule = module(arena, loader, "waterFrag");
        lodWaterMeshModule = module(arena, loader, "lodWaterMesh");
        lodWaterFragModule = module(arena, loader, "lodWaterFrag");
        pipeLodWaterDepth = meshPipe(arena, layoutWater, lodWaterMeshModule, module(arena, loader, "lodWaterDepthFrag"), 0, FMT_LDR,
                VK10.VK_COMPARE_OP_GREATER_OR_EQUAL, false, BLEND_NONE, true, true);
        // Tiefen-Vorpass (nur Mesh-Stage) + Glas-Aufnahme: ohne Farbziel, formatunabhaengig
        pipeWaterDepth = meshPipe(arena, layoutWater, waterMeshModule, 0L, 0, FMT_LDR,
                VK10.VK_COMPARE_OP_GREATER_OR_EQUAL, false, BLEND_NONE, true, true);
        // oitResolve: 0ST(Kopf) 1S(Knoten) 2I(Wassertiefe) 3I(Szenentiefe) 4ST(Szene) 5ST(Front) 6S(Zaehler) + push 8
        layoutOit = pipelineLayout(arena, setLayout(arena,
                new int[][]{{0, ST}, {1, SB}, {2, SI}, {3, SI}, {4, ST}, {5, ST}, {6, SB}, {7, ST}}, C), C, 8);
        pipeOitResolve = computePipe(arena, layoutOit, module(arena, loader, "oitResolve"));
        layoutOitComposite = pipelineLayout(arena, setLayout(arena, new int[][]{{0, SI}, {1, SI}}, M | F), 0, 0);
        oitFsMeshModule = module(arena, loader, "oitFullscreenMesh");
        oitCompositeFragModule = module(arena, loader, "oitCompositeFrag");
        layoutEntityShade = pipelineLayout(arena, setLayout(arena,
                new int[][]{{0, UB}, {1, SI}, {2, SI}, {3, SI}, {4, SM}}, M | F), 0, 0);
        entityShadeFragModule = module(arena, loader, "entityShadeFrag");
        // TAA: 0I(aktuell) 1I(Tiefe) 2I(History) 3ST(History neu) 4ST(Ausgabe) 5SMP 6U
        layoutTaa = pipelineLayout(arena, setLayout(arena,
                new int[][]{{0, SI}, {1, SI}, {2, SI}, {3, ST}, {4, ST}, {5, SM}, {6, UB}}, C), 0, 0);
        pipeTaa = computePipe(arena, layoutTaa, module(arena, loader, "taaResolve"));
        transMeshModule = module(arena, loader, "translucentMesh");
        transFragModule = module(arena, loader, "translucentRecordFrag");
        // Glas/Eis einseitig wie Vanillas transluzentes Terrain (Scheiben haben eigene Rueckseiten):
        // sonst erscheinen Innenwaende benachbarter Bloecke durch das Eis hindurch
        pipeTrans = meshPipe(arena, layoutWater, transMeshModule, transFragModule, 0, FMT_LDR,
                VK10.VK_COMPARE_OP_GREATER_OR_EQUAL, false, BLEND_NONE, false, true, VK10.VK_CULL_MODE_FRONT_BIT);

        // Entities, RT-GI/Denoise und Bobby-LOD haben noch keinen Frame-Pass.
        return pipeCull != 0 && pipeGbuffer != 0 && pipeShadow != 0 && pipeHiz != 0 && pipeDeferred != 0
                && pipeBloomDown != 0 && pipeBloomUp != 0 && pipeFinal != 0
                && pipeWaterDepth != 0 && pipeTrans != 0 && pipeOitResolve != 0;
    }

    private static final int BLEND_NONE = 0;
    private static final int BLEND_PREMUL_UNDER = 1; // dst = src.rgb + dst.rgb * src.a (OIT-Composite)
    private static final int BLEND_MULTIPLY = 2;     // dst = dst.rgb * src.rgb (Entity-Schatten)

    private long meshPipe(Arena arena, long layout, long meshMod, long fragMod, int colorCount, int colorFormat,
                          int depthOp, boolean depthBias) {
        return meshPipe(arena, layout, meshMod, fragMod, colorCount, colorFormat, depthOp, depthBias, BLEND_NONE, true, true);
    }

    private long meshPipe(Arena arena, long layout, long meshMod, long fragMod, int colorCount, int colorFormat,
                          int depthOp, boolean depthBias, int blend, boolean depthWrite, boolean depthTest) {
        return meshPipe(arena, layout, meshMod, fragMod, colorCount, colorFormat, depthOp, depthBias, blend, depthWrite, depthTest,
                VK10.VK_CULL_MODE_NONE);
    }

    /** fragMod 0 = reine Tiefen-Pipeline (nur Mesh-Stage). */
    private long meshPipe(Arena arena, long layout, long meshMod, long fragMod, int colorCount, int colorFormat,
                          int depthOp, boolean depthBias, int blend, boolean depthWrite, boolean depthTest, int cullMode) {
        VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(fragMod != 0L ? 2 : 1, arena.stack());
        stages.get(0).sType$Default().stage(EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT).module(meshMod).pName(arena.utf8("main"));
        if (fragMod != 0L) {
            stages.get(1).sType$Default().stage(VK10.VK_SHADER_STAGE_FRAGMENT_BIT).module(fragMod).pName(arena.utf8("main"));
        }

        VkPipelineVertexInputStateCreateInfo vi = VkPipelineVertexInputStateCreateInfo.calloc(arena.stack()).sType$Default();
        VkPipelineInputAssemblyStateCreateInfo ia = VkPipelineInputAssemblyStateCreateInfo.calloc(arena.stack()).sType$Default()
                .topology(VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
        VkPipelineViewportStateCreateInfo vp = VkPipelineViewportStateCreateInfo.calloc(arena.stack()).sType$Default()
                .viewportCount(1).scissorCount(1);
        // Kein Backface-Cull im Rasterizer: Pflanzen-Quads sind einseitig, der
        // Meshlet-Ebenentest verwirft Rueckseiten schon im Cull-Shader.
        VkPipelineRasterizationStateCreateInfo rs = VkPipelineRasterizationStateCreateInfo.calloc(arena.stack()).sType$Default()
                .polygonMode(VK10.VK_POLYGON_MODE_FILL).cullMode(cullMode)
                .frontFace(VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1.0f);
        if (depthBias) {
            // Schatten: Slope-Bias gegen Akne an flach beleuchteten Flaechen
            rs.depthBiasEnable(true).depthBiasConstantFactor(1.5f).depthBiasSlopeFactor(2.0f);
        }
        VkPipelineMultisampleStateCreateInfo ms = VkPipelineMultisampleStateCreateInfo.calloc(arena.stack()).sType$Default()
                .rasterizationSamples(VK10.VK_SAMPLE_COUNT_1_BIT);
        VkPipelineDepthStencilStateCreateInfo ds = VkPipelineDepthStencilStateCreateInfo.calloc(arena.stack()).sType$Default()
                .depthTestEnable(depthTest).depthWriteEnable(depthWrite).depthCompareOp(depthOp);
        VkPipelineColorBlendStateCreateInfo cb = VkPipelineColorBlendStateCreateInfo.calloc(arena.stack()).sType$Default();
        if (colorCount > 0) {
            VkPipelineColorBlendAttachmentState.Buffer att = VkPipelineColorBlendAttachmentState.calloc(colorCount, arena.stack());
            for (int i = 0; i < colorCount; i++) {
                att.get(i).blendEnable(blend != BLEND_NONE).colorWriteMask(0xF);
                if (blend == BLEND_MULTIPLY) {
                    att.get(i).srcColorBlendFactor(VK10.VK_BLEND_FACTOR_DST_COLOR)
                            .dstColorBlendFactor(VK10.VK_BLEND_FACTOR_ZERO).colorBlendOp(VK10.VK_BLEND_OP_ADD)
                            .srcAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ZERO)
                            .dstAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ONE).alphaBlendOp(VK10.VK_BLEND_OP_ADD);
                }
                if (blend == BLEND_PREMUL_UNDER) {
                    att.get(i).srcColorBlendFactor(VK10.VK_BLEND_FACTOR_ONE)
                            .dstColorBlendFactor(VK10.VK_BLEND_FACTOR_SRC_ALPHA).colorBlendOp(VK10.VK_BLEND_OP_ADD)
                            .srcAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ZERO)
                            .dstAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ONE).alphaBlendOp(VK10.VK_BLEND_OP_ADD);
                }
            }
            cb.pAttachments(att);
        }
        VkPipelineDynamicStateCreateInfo dyn = VkPipelineDynamicStateCreateInfo.calloc(arena.stack()).sType$Default()
                .pDynamicStates(arena.ints(VK10.VK_DYNAMIC_STATE_VIEWPORT, VK10.VK_DYNAMIC_STATE_SCISSOR));
        VkPipelineRenderingCreateInfo rendering = VkPipelineRenderingCreateInfo.calloc(arena.stack()).sType$Default()
                .depthAttachmentFormat(FMT_DEPTH);
        if (colorCount > 0) {
            int[] formats = new int[colorCount];
            Arrays.fill(formats, colorFormat);
            rendering.colorAttachmentCount(colorCount).pColorAttachmentFormats(arena.ints(formats));
        }
        VkGraphicsPipelineCreateInfo.Buffer ci = VkGraphicsPipelineCreateInfo.calloc(1, arena.stack()).sType$Default()
                .pNext(rendering.address()).pStages(stages).pVertexInputState(vi).pInputAssemblyState(ia)
                .pViewportState(vp).pRasterizationState(rs).pMultisampleState(ms)
                .pDepthStencilState(ds).pColorBlendState(cb).pDynamicState(dyn).layout(layout);
        LongBuffer p = arena.mallocLong(1);
        if (VK10.vkCreateGraphicsPipelines(dev, 0L, ci, null, p) != VK10.VK_SUCCESS) return 0L;
        pipelines.add(p.get(0));
        return p.get(0);
    }

    // ---------- Entity-Schatten (Vertices aus Vanillas Frame-Puffer, siehe EntityShadowCapture) ----------
    private static final int ENTITY_QUAD_CHUNK = 1 << 18; // Quads je Draw (Indexpuffer-Groesse)
    private long layoutEntityShadow, entityShadowModule;
    private Buf quadIndex;
    private final java.util.Map<Long, Long> entityShadowPipes = new java.util.HashMap<>();

    private void initEntityShadow(Arena arena, SlangShaderLoader loader) {
        try {
            layoutEntityShadow = pipelineLayout(arena, setLayout(arena, new int[][]{{0, UB}}, VK10.VK_SHADER_STAGE_VERTEX_BIT), 0, 0);
            entityShadowModule = module(arena, loader, "entityShadowVert");
            long bytes = ENTITY_QUAD_CHUNK * 6L * 4;
            quadIndex = makeBuffer(arena, bytes, VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
                    VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            java.nio.IntBuffer ib = MemoryUtil.memIntBuffer(quadIndex.mapped(), (int) (bytes / 4));
            // Vanillas Quad-Reihenfolge (0 1 2, 2 3 0)
            for (int q = 0; q < ENTITY_QUAD_CHUNK; q++) {
                int v = q * 4, o = q * 6;
                ib.put(o, v).put(o + 1, v + 1).put(o + 2, v + 2).put(o + 3, v + 2).put(o + 4, v + 3).put(o + 5, v);
            }
        } catch (Throwable t) {
            LOG.warn("[vulkanfish] Entity-Schatten nicht verfuegbar", t);
            entityShadowModule = 0L;
        }
    }

    /** Tiefen-Pipeline fuer einen Vertex-Stride (Entity-/Block-/Item-Formate unterscheiden sich). */
    private long entityShadowPipe(Arena arena, int stride, int posOffset) {
        long key = ((long) stride << 32) | posOffset;
        Long cached = entityShadowPipes.get(key);
        if (cached != null) return cached;
        VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(1, arena.stack());
        stages.get(0).sType$Default().stage(VK10.VK_SHADER_STAGE_VERTEX_BIT).module(entityShadowModule).pName(arena.utf8("main"));
        VkVertexInputBindingDescription.Buffer vb = VkVertexInputBindingDescription.calloc(1, arena.stack());
        vb.get(0).binding(0).stride(stride).inputRate(VK10.VK_VERTEX_INPUT_RATE_VERTEX);
        VkVertexInputAttributeDescription.Buffer va = VkVertexInputAttributeDescription.calloc(1, arena.stack());
        va.get(0).location(0).binding(0).format(VK10.VK_FORMAT_R32G32B32_SFLOAT).offset(posOffset);
        VkPipelineVertexInputStateCreateInfo vi = VkPipelineVertexInputStateCreateInfo.calloc(arena.stack()).sType$Default()
                .pVertexBindingDescriptions(vb).pVertexAttributeDescriptions(va);
        VkPipelineInputAssemblyStateCreateInfo ia = VkPipelineInputAssemblyStateCreateInfo.calloc(arena.stack()).sType$Default()
                .topology(VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
        VkPipelineViewportStateCreateInfo vp = VkPipelineViewportStateCreateInfo.calloc(arena.stack()).sType$Default()
                .viewportCount(1).scissorCount(1);
        // wie der Terrain-Schatten: kein Cull (Modelle teils einseitig), Slope-Bias gegen Akne
        VkPipelineRasterizationStateCreateInfo rs = VkPipelineRasterizationStateCreateInfo.calloc(arena.stack()).sType$Default()
                .polygonMode(VK10.VK_POLYGON_MODE_FILL).cullMode(VK10.VK_CULL_MODE_NONE)
                .frontFace(VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1.0f)
                .depthBiasEnable(true).depthBiasConstantFactor(1.5f).depthBiasSlopeFactor(2.0f);
        VkPipelineMultisampleStateCreateInfo ms = VkPipelineMultisampleStateCreateInfo.calloc(arena.stack()).sType$Default()
                .rasterizationSamples(VK10.VK_SAMPLE_COUNT_1_BIT);
        VkPipelineDepthStencilStateCreateInfo ds = VkPipelineDepthStencilStateCreateInfo.calloc(arena.stack()).sType$Default()
                .depthTestEnable(true).depthWriteEnable(true).depthCompareOp(VK10.VK_COMPARE_OP_LESS_OR_EQUAL);
        VkPipelineColorBlendStateCreateInfo cb = VkPipelineColorBlendStateCreateInfo.calloc(arena.stack()).sType$Default();
        VkPipelineDynamicStateCreateInfo dyn = VkPipelineDynamicStateCreateInfo.calloc(arena.stack()).sType$Default()
                .pDynamicStates(arena.ints(VK10.VK_DYNAMIC_STATE_VIEWPORT, VK10.VK_DYNAMIC_STATE_SCISSOR));
        VkPipelineRenderingCreateInfo rendering = VkPipelineRenderingCreateInfo.calloc(arena.stack()).sType$Default()
                .depthAttachmentFormat(FMT_DEPTH);
        VkGraphicsPipelineCreateInfo.Buffer ci = VkGraphicsPipelineCreateInfo.calloc(1, arena.stack()).sType$Default()
                .pNext(rendering.address()).pStages(stages).pVertexInputState(vi).pInputAssemblyState(ia)
                .pViewportState(vp).pRasterizationState(rs).pMultisampleState(ms)
                .pDepthStencilState(ds).pColorBlendState(cb).pDynamicState(dyn).layout(layoutEntityShadow);
        LongBuffer p = arena.mallocLong(1);
        long pipe = VK10.vkCreateGraphicsPipelines(dev, 0L, ci, null, p) == VK10.VK_SUCCESS ? p.get(0) : 0L;
        if (pipe != 0L) pipelines.add(pipe);
        entityShadowPipes.put(key, pipe);
        return pipe;
    }

    /** Im laufenden Schatten-Rendering: Entity-Geometrie des Frames zeichnen. */
    private void recordEntityShadows(Arena arena, VkCommandBuffer cmd, int slot,
                                     java.util.List<simon.vulkanfish.client.render.EntityShadowCapture.Batch> batches) {
        if (entityShadowModule == 0L || batches.isEmpty()) return;
        push(arena, cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, layoutEntityShadow, W.ub(0, uniforms[slot]));
        VK10.vkCmdBindIndexBuffer(cmd, quadIndex.buffer(), 0L, VK10.VK_INDEX_TYPE_UINT32);
        long bound = 0L;
        for (var b : batches) {
            long pipe = entityShadowPipe(arena, b.stride(), b.posOffset());
            if (pipe == 0L) continue;
            if (pipe != bound) {
                VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipe);
                bound = pipe;
            }
            VK10.vkCmdBindVertexBuffers(cmd, 0, arena.longs(b.vkBuffer()), arena.longs(b.byteOffset()));
            if (b.quads()) {
                int quads = b.vertexCount() / 4;
                for (int q0 = 0; q0 < quads; q0 += ENTITY_QUAD_CHUNK) {
                    int n = Math.min(ENTITY_QUAD_CHUNK, quads - q0);
                    VK10.vkCmdDrawIndexed(cmd, n * 6, 1, 0, q0 * 4, 0);
                }
            } else {
                VK10.vkCmdDraw(cmd, b.vertexCount() - b.vertexCount() % 3, 1, 0, 0);
            }
        }
    }

    /** LOD-Fernfeld: Puffer (halbiert bei VRAM-Mangel) + Mesh-Pipeline in den G-Buffer. */
    private void initLod(Arena arena, SlangShaderLoader loader) {
        try {
            int stor = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, dst = VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            int ind = VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
            int dev = VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
            lodQuads = makeBuffer(arena, (long) simon.vulkanfish.client.lod.LodManager.MAX_QUADS
                    * simon.vulkanfish.client.lod.LodManager.QUAD_BYTES, stor | dst, dev);
            long meshlets = simon.vulkanfish.client.lod.LodManager.MAX_MESHLETS;
            lodMeshlets = makeBuffer(arena, meshlets * 64, stor | dst, dev);
            lodClusters = makeBuffer(arena, (long) simon.vulkanfish.client.lod.LodManager.MAX_CLUSTERS * CLUSTER_BYTES, stor | dst, dev);
            lodVis1 = makeBuffer(arena, meshlets * 4, stor, dev);
            lodVis2 = makeBuffer(arena, meshlets * 4, stor, dev);
            lodVisBits = makeBuffer(arena, meshlets * 4, stor | dst, dev);
            lodIndirect1 = makeBuffer(arena, 16, stor | ind | dst, dev);
            lodIndirect2 = makeBuffer(arena, 16, stor | ind | dst, dev);
            // Wasseroberflaechen des Fernfelds: eigene Liste fuer den Wasser-Pass
            lodVisWater = makeBuffer(arena, meshlets * 4, stor, dev);
            lodWaterIndirect = makeBuffer(arena, 16, stor | ind | dst, dev);
            lodClusterTotal = makeBuffer(arena, 16, stor | dst, dev);
            int n = simon.vulkanfish.client.lod.LodManager.NEAR_MASK_SIZE;
            lodNearMask = makeBuffer(arena, (long) n * n * 4, stor | dst, dev); // je Chunk ein uint (Section-Bits)
            int host = VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
            lodTexTable = makeBuffer(arena, (long) simon.vulkanfish.client.lod.LodMaterials.MAX_APPEARANCES * 48, stor, host);
            lodDrawBiome = makeBuffer(arena, 256L * 16, stor, host);
            int M = EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT, F = VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
            // 0 Meshlets, 1 Quads, 2 Nahfeld-Maske, 3 Frame, 4 Atlas, 5 Sampler, 6 Liste, 7 Aussehen, 8 Biomfarben
            layoutLod = pipelineLayout(arena, setLayout(arena, new int[][]{{0, SB}, {1, SB}, {2, SB}, {3, UB}, {4, SI}, {5, SM},
                    {6, SB}, {7, SB}, {8, SB}}, M | F), M | F, 16);
            pipeLod = meshPipe(arena, layoutLod, module(arena, loader, "lodMesh"), module(arena, loader, "lodFrag"),
                    2, FMT_GBUF, VK10.VK_COMPARE_OP_GREATER_OR_EQUAL, false);
            if (pipeLod == 0L) throw new IllegalStateException("LOD-Pipeline fehlgeschlagen");
            lodVisShadow = makeBuffer(arena, meshlets * 4, stor, dev);
            lodShadowIndirect = makeBuffer(arena, 16, stor | ind | dst, dev);
            pipeLodShadow = meshPipe(arena, layoutLod, module(arena, loader, "lodShadowMesh"), module(arena, loader, "lodShadowFrag"),
                    0, FMT_GBUF, VK10.VK_COMPARE_OP_LESS_OR_EQUAL, true);
            if (MeshShaderSupport.float64Enabled()) {
                int C = VK10.VK_SHADER_STAGE_COMPUTE_BIT;
                int[][] lb = new int[24][];
                for (int i = 0; i < 24; i++) lb[i] = new int[]{i, SB};
                layoutLodGen = pipelineLayout(arena, setLayout(arena, lb, C), C, 128);
                pipeLodGen = computePipe(arena, layoutLodGen, module(arena, loader, "lodGenMain"));
                int[][] mb = new int[11][];
                for (int i = 0; i < 11; i++) mb[i] = new int[]{i, SB};
                layoutLodMesh = pipelineLayout(arena, setLayout(arena, mb, C), C, 32);
                pipeLodMesh = computePipe(arena, layoutLodMesh, module(arena, loader, "lodMeshMain"));
            }
            lodReady = true;
            LOG.info("[vulkanfish] LOD-Fernfeld: {} M Quads, {} K Meshlets Kapazitaet",
                    simon.vulkanfish.client.lod.LodManager.MAX_QUADS >> 20, meshlets >> 10);
        } catch (Throwable t) {
            LOG.warn("[vulkanfish] LOD-Fernfeld nicht verfuegbar", t);
            lodReady = false;
        }
    }

    public boolean lodReady() {
        return lodReady;
    }

    /** Neue Aussehen/Biome fuer das LOD-Zeichnen hochladen (nur Zuwachs; bei Resource-Reload alles). */
    private void updateLodDrawTables() {
        int gen = simon.vulkanfish.client.lod.LodMaterials.generation();
        if (gen != lodTexGeneration) {
            lodTexGeneration = gen;
            lodTexUploaded = 0;
        }
        var app = simon.vulkanfish.client.lod.LodMaterials.appearances();
        if (app.length > lodTexUploaded) {
            ByteBuffer tb = MemoryUtil.memByteBuffer(lodTexTable.mapped(), simon.vulkanfish.client.lod.LodMaterials.MAX_APPEARANCES * 48);
            for (int i = lodTexUploaded; i < app.length; i++) {
                var a = app[i];
                for (int f = 0; f < 3; f++) {
                    int o = i * 48 + f * 16;
                    float[] r = a.rect();
                    tb.putInt(o, unorm16(r[f * 4]) | (unorm16(r[f * 4 + 1]) << 16));
                    tb.putInt(o + 4, unorm16(r[f * 4 + 2]) | (unorm16(r[f * 4 + 3]) << 16));
                    tb.putInt(o + 8, (a.color()[f] & 0xFFFFFF) | (a.tint()[f] << 24));
                    tb.putInt(o + 12, (a.constTint() & 0xFFFFFF) | ((a.luma()[f] & 0xFF) << 24));
                }
            }
            lodTexUploaded = app.length;
        }
        int biomes = simon.vulkanfish.client.lod.LodMaterials.biomeCount();
        if (biomes > lodDrawBiomeUploaded) {
            ByteBuffer bb = MemoryUtil.memByteBuffer(lodDrawBiome.mapped(), 256 * 16);
            for (int i = Math.max(0, lodDrawBiomeUploaded); i < biomes && i < 256; i++) {
                int[] c = simon.vulkanfish.client.lod.LodMaterials.biomeColors(i);
                for (int k = 0; k < 4; k++) bb.putInt(i * 16 + k * 4, c[k]);
            }
            lodDrawBiomeUploaded = biomes;
        }
    }

    private static int unorm16(float v) {
        return Math.round(Math.max(0f, Math.min(1f, v)) * 65535f);
    }

    /** Belegte LOD-Cluster-Slots (Dispatch-Umfang des Fernfeld-Culls). */
    public void setLodCounts(int clusters) {
        lodClusterCount = clusters;
    }

    /** Nahfeld-Abdeckung (je Chunk Section-Bits ab minSectionY, 128x128 toroidal) fuer LOD-Cull und -Mesh. */
    public void setLodNearMask(int[] mask, int camChunkX, int camChunkZ, int minSectionY) {
        lodMaskData = mask.clone();
        lodMaskCamX = camChunkX;
        lodMaskCamZ = camChunkZ;
        lodMaskMinSY = minSectionY;
        lodMaskPending = true;
    }

    /** Raytracing-Blocklicht: Szene (BLAS/TLAS), Lichtgitter, Deferred-Variante mit Ray Queries. */
    private void initRt(Arena arena, SlangShaderLoader loader) {
        try {
            int C = VK10.VK_SHADER_STAGE_COMPUTE_BIT;
            int AS = KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
            layoutDeferredRt = pipelineLayout(arena, setLayout(arena, new int[][]{{0, SI}, {1, SI}, {2, SI}, {3, SI}, {4, SM},
                    {5, SI}, {6, ST}, {7, UB}, {8, SM}, {9, AS}, {10, SB}, {11, SB}, {12, SB}, {13, SB}, {14, SI}, {15, SM}}, C), 0, 0);
            pipeDeferredRt = computePipe(arena, layoutDeferredRt, module(arena, loader, "deferredMainRt"));
            layoutLightBin = pipelineLayout(arena, setLayout(arena, new int[][]{{0, SB}, {1, SB}, {2, SB}}, C), C, 16);
            pipeLightBin = computePipe(arena, layoutLightBin, module(arena, loader, "lightBin"));
            if (pipeDeferredRt == 0L || pipeLightBin == 0L) throw new IllegalStateException("RT-Pipelines fehlgeschlagen");
            int cells = RtAccel.GRID_CELLS * RtAccel.GRID_CELLS * RtAccel.GRID_CELLS;
            int stor = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
            cellCount = makeBuffer(arena, cells * 4L, stor | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            cellLights = makeBuffer(arena, (long) cells * RtAccel.CELL_CAP * 4L, stor, VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            rt = new RtAccel(dev, phys, vma, FRAMES, verts.buffer());
            LOG.info("[vulkanfish] Raytracing-Blocklicht aktiv (Ray Queries, BLAS pro Section im Umkreis von {} Sections)",
                    RtAccel.WINDOW_SECTIONS);
        } catch (Throwable t) {
            LOG.warn("[vulkanfish] Raytracing-Blocklicht nicht verfuegbar – Vanilla-Blocklicht", t);
            rt = null;
        }
    }

    /** BLAS/TLAS-Bau + Lichtgitter (nach den Uploads, vor dem Deferred-Licht). */
    private void recordRt(Arena arena, VkCommandBuffer cmd, int slot, FrameUniformsData d) {
        long completed;
        try (Arena a = new Arena()) {
            LongBuffer v = a.mallocLong(1);
            check(VK12.vkGetSemaphoreCounterValue(dev, timeline, v), "timelineValue");
            completed = v.get(0);
        }
        long t0 = System.nanoTime();
        rt.record(arena, cmd, slot, d.camX(), d.camY(), d.camZ(), completed, timelineValue + 1);
        rtCpuNanos += System.nanoTime() - t0;
        rtCpuFrames++;
        VK10.vkCmdFillBuffer(cmd, cellCount.buffer(), 0, cellCount.size(), 0);
        barrier(arena, cmd, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_ACCESS_TRANSFER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
        int n = rt.lightCount();
        if (n > 0) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeLightBin);
            push(arena, cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layoutLightBin,
                    W.raw(0, rt.lightBuffer(slot), (long) RtAccel.MAX_LIGHTS * RtAccel.LIGHT_BYTES),
                    W.sb(1, cellCount), W.sb(2, cellLights));
            ByteBuffer pc = arena.malloc(16);
            pc.putInt(0, rt.gridX()).putInt(4, rt.gridY()).putInt(8, rt.gridZ()).putInt(12, n);
            VK10.vkCmdPushConstants(cmd, layoutLightBin, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
            VK10.vkCmdDispatch(cmd, (n + 63) / 64, 1, 1);
        }
        barrier(arena, cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT);
    }

    // Streamer-Hooks (Render-Thread): Geometrie/Lichter einer Section fuer das Raytracing
    void rtSectionApplied(long key, int quadStart, int solidQuads, int cutoutQuads, long rtHash, int[] lights) {
        if (rt != null) rt.sectionApplied(key, quadStart, solidQuads, cutoutQuads, rtHash, lights);
    }

    void rtSectionRemoved(long key) {
        if (rt != null) rt.sectionRemoved(key, timelineValue + 1);
    }

    void rtClear() {
        if (rt != null) rt.clear(timelineValue + 1);
    }

    // ---------- Images ----------

    private Img makeImage(Arena arena, int w, int h, int mips, int format, int usage, int aspect) {
        VkImageCreateInfo ci = VkImageCreateInfo.calloc(arena.stack()).sType$Default()
                .imageType(VK10.VK_IMAGE_TYPE_2D).format(format).mipLevels(mips).arrayLayers(1)
                .samples(VK10.VK_SAMPLE_COUNT_1_BIT).tiling(VK10.VK_IMAGE_TILING_OPTIMAL).usage(usage)
                .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
        ci.extent().width(w).height(h).depth(1);
        VmaAllocationCreateInfo aci = VmaAllocationCreateInfo.calloc(arena.stack())
                .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
        LongBuffer p = arena.mallocLong(1);
        PointerBuffer pa = arena.mallocPointer(1);
        check(Vma.vmaCreateImage(vma, ci, aci, p, pa, null), "vmaCreateImage");
        long img = p.get(0);
        return new Img(img, pa.get(0), makeView(arena, img, format, 0, mips, aspect), w, h, mips, format);
    }

    private long makeView(Arena arena, long img, int format, int baseMip, int mips, int aspect) {
        VkImageViewCreateInfo vi = VkImageViewCreateInfo.calloc(arena.stack()).sType$Default()
                .image(img).viewType(VK10.VK_IMAGE_VIEW_TYPE_2D).format(format);
        vi.subresourceRange().aspectMask(aspect).baseMipLevel(baseMip).levelCount(mips).baseArrayLayer(0).layerCount(1);
        LongBuffer p = arena.mallocLong(1);
        check(VK10.vkCreateImageView(dev, vi, null, p), "view");
        return p.get(0);
    }

    /** Neu anlegen, wenn sich die Main-Target-Groesse aendert (wartet vorher auf alle eigenen GPU-Arbeiten). */
    private void ensureSize(int w, int h) {
        if (w == width && h == height && gAlbedo != null) return;
        waitAll();
        destroyImages();
        width = w;
        height = h;
        try (Arena arena = new Arena()) {
            int sampled = VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
            int stor = VK10.VK_IMAGE_USAGE_STORAGE_BIT;
            int color = VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
            int src = VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
            int dst = VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            int ca = VK10.VK_IMAGE_ASPECT_COLOR_BIT;
            gAlbedo = makeImage(arena, w, h, 1, FMT_GBUF, color | sampled | src, ca);
            gNormal = makeImage(arena, w, h, 1, FMT_GBUF, color | sampled | src, ca);
            depth = makeImage(arena, w, h, 1, FMT_DEPTH,
                    VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | sampled | src | dst, VK10.VK_IMAGE_ASPECT_DEPTH_BIT);
            int mips = 32 - Integer.numberOfLeadingZeros(Math.max(w, h));
            hiz = makeImage(arena, w, h, mips, FMT_HIZ, sampled | stor | dst, ca);
            hizMipViews = new long[mips];
            for (int i = 0; i < mips; i++) hizMipViews[i] = makeView(arena, hiz.image(), FMT_HIZ, i, 1, ca);
            hdr = makeImage(arena, w, h, 1, FMT_HDR, sampled | stor, ca);
            int bw = Math.max(w / 2, 1);
            int bh = Math.max(h / 2, 1);
            int bmips = Math.min(BLOOM_MIPS, 32 - Integer.numberOfLeadingZeros(Math.max(bw, bh)));
            bloom = makeImage(arena, bw, bh, bmips, FMT_HDR, sampled | stor | dst, ca);
            bloomMipViews = new long[bmips];
            for (int i = 0; i < bmips; i++) bloomMipViews[i] = makeView(arena, bloom.image(), FMT_HDR, i, 1, ca);
            ldr = makeImage(arena, w, h, 1, FMT_LDR, stor | src, ca);
            background = makeImage(arena, w, h, 1, FMT_LDR, sampled | dst, ca);
            sceneCopy = makeImage(arena, w, h, 1, FMT_LDR, sampled | stor | dst, ca);
            oitHead = makeImage(arena, w, h, 1, VK10.VK_FORMAT_R32_UINT, stor | dst, ca);
            oitFront = makeImage(arena, w, h, 1, FMT_HDR, sampled | stor, ca);
            oitFrontDepth = makeImage(arena, w, h, 1, FMT_HIZ, sampled | stor, ca);
            // Fragment-Pool: im Mittel 2 Glasschichten pro Pixel (Glas bedeckt selten den ganzen Schirm),
            // 12 Byte pro Fragment; Ueberlauf verwirft nur Fragmente
            oitCapacity = (int) Math.min(Math.max(2L * w * h, 1L << 21), 12L << 20);
            oitNodes = makeBuffer(arena, oitCapacity * 12L, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            sceneDepth = makeImage(arena, w, h, 1, FMT_DEPTH, sampled | dst, VK10.VK_IMAGE_ASPECT_DEPTH_BIT);
            history[0] = makeImage(arena, w, h, 1, FMT_HDR, sampled | stor, ca);
            history[1] = makeImage(arena, w, h, 1, FMT_HDR, sampled | stor, ca);
            taaOut = makeImage(arena, w, h, 1, FMT_LDR, stor | src, ca);
        }
        imagesInitialized = false;
        historyValid = false;
    }

    private void destroyImg(Img img) {
        if (img == null) return;
        VK10.vkDestroyImageView(dev, img.view(), null);
        Vma.vmaDestroyImage(vma, img.image(), img.allocation());
    }

    private void destroyImages() {
        for (long v : hizMipViews) VK10.vkDestroyImageView(dev, v, null);
        for (long v : bloomMipViews) VK10.vkDestroyImageView(dev, v, null);
        hizMipViews = new long[0];
        bloomMipViews = new long[0];
        for (Img img : new Img[]{gAlbedo, gNormal, depth, hiz, hdr, bloom, ldr, background, sceneCopy, sceneDepth,
                history[0], history[1], taaOut, oitHead, oitFront, oitFrontDepth}) destroyImg(img);
        gAlbedo = gNormal = depth = hiz = hdr = bloom = ldr = background = sceneCopy = sceneDepth = taaOut = null;
        oitHead = oitFront = oitFrontDepth = null;
        if (oitNodes != null) destroyBuffer(oitNodes);
        oitNodes = null;
        history[0] = history[1] = null;
    }

    // ---------- Upload-Ring (vom TerrainStreamer waehrend der Aufnahme befuellt) ----------

    public long stagingFree() {
        return STAGING_BYTES - stagingCursor;
    }

    public void stageCopy(int target, long dstOffset, ByteBuffer data) {
        long size = data.remaining();
        if (size == 0) return;
        if (stagingCursor + size > STAGING_BYTES) throw new IllegalStateException("Staging-Ring voll");
        MemoryUtil.memByteBuffer(staging[currentSlot].mapped() + stagingCursor, (int) size).put(data.duplicate());
        copies.add(new long[]{target, stagingCursor, dstOffset, size});
        stagingCursor = (stagingCursor + size + 15) & ~15L;
    }

    public void stageFill(int target, long dstOffset, long size) {
        if (size > 0) fills.add(new long[]{target, dstOffset, size});
    }

    public void setMeshletTotal(int count) {
        meshletCount = count;
    }

    /** Belegte Cluster-Slots = Workgroups des hierarchischen Culls. */
    public void setClusterTotal(int count) {
        clusterCount = count;
    }

    public int lastVisibleMeshlets() {
        return visMeshletCount == null ? 0 : MemoryUtil.memGetInt(visMeshletCount.mapped());
    }

    public int lastWaterMeshlets() {
        return waterIndirect == null ? 0 : MemoryUtil.memGetInt(waterIndirect.mapped());
    }

    public int lastShadowMeshlets() {
        return visShadowCount == null ? 0 : MemoryUtil.memGetInt(visShadowCount.mapped());
    }

    /** Mittlere GPU-Zeiten pro Pass seit dem letzten Aufruf (ms), danach zurueckgesetzt. */
    public String passTimings() {
        if (passSamples == 0) return "keine Messung";
        StringBuilder sb = new StringBuilder();
        double total = 0;
        for (int i = 0; i < PASS_NAMES.length; i++) {
            double ms = passMsSum[i] / passSamples;
            total += ms;
            sb.append(PASS_NAMES[i]).append(' ').append(String.format(java.util.Locale.ROOT, "%.2f", ms)).append(", ");
            passMsSum[i] = 0;
        }
        passSamples = 0;
        if (waterSamples > 0) {
            double w = waterMsSum / waterSamples;
            total += w;
            sb.append("wasser ").append(String.format(java.util.Locale.ROOT, "%.2f", w)).append(", ");
        }
        waterMsSum = 0;
        waterSamples = 0;
        if (taaSamples > 0) {
            double a = taaMsSum / taaSamples;
            total += a;
            sb.append("taa ").append(String.format(java.util.Locale.ROOT, "%.2f", a)).append(", ");
        }
        taaMsSum = 0;
        taaSamples = 0;
        if (waterPartN > 0) {
            sb.append(String.format(java.util.Locale.ROOT, "[Spanne Terrain..TAA %.2f] ", spanMsSum / waterPartN));
            sb.append(String.format(java.util.Locale.ROOT, "[wasser: kopie %.2f, aufnahme %.2f, sortieren %.2f, farbe %.2f] ",
                    waterPartMs[0] / waterPartN, waterPartMs[1] / waterPartN, waterPartMs[2] / waterPartN, waterPartMs[3] / waterPartN));
        }
        java.util.Arrays.fill(waterPartMs, 0);
        waterPartN = 0;
        spanMsSum = 0;
        if (lgMsN > 0) {
            sb.append("[LOD-GPU je Frame:");
            for (int i = 0; i < ST_NAMES.length; i++) {
                sb.append(' ').append(ST_NAMES[i]).append(' ').append(String.format(java.util.Locale.ROOT, "%.2f", lgMsSum[i] / lgMsN));
                lgMsSum[i] = 0;
            }
            sb.append(" = ").append(String.format(java.util.Locale.ROOT, "%.2f", lgMsSum[ST_NAMES.length] / lgMsN)).append(" ms, ")
                    .append(lgJobsSum).append(" Knoten] ");
            lgMsSum[ST_NAMES.length] = 0;
            lgMsN = 0;
            lgJobsSum = 0;
        }
        if (rtCpuFrames > 0) {
            sb.append("[CPU rt ").append(String.format(java.util.Locale.ROOT, "%.3f", rtCpuNanos / 1e6 / rtCpuFrames)).append(" ms] ");
            rtCpuNanos = 0;
            rtCpuFrames = 0;
        }
        return sb.append("gesamt ").append(String.format(java.util.Locale.ROOT, "%.2f", total)).append(" ms").toString();
    }

    private Buf target(long t) {
        return switch ((int) t) {
            case TARGET_CLUSTERS -> clusters;
            case TARGET_MESHLETS -> meshlets;
            case TARGET_VERTS -> verts;
            case TARGET_LOD_QUADS -> lodQuads;
            case TARGET_LOD_MESHLETS -> lodMeshlets;
            case TARGET_LOD_CLUSTERS -> lodClusters;
            default -> tris;
        };
    }

    // ---------- Frame ----------

    /** CPU-seitige Frame-Treiber (skalar, O(1) – kein Szenen-Content auf CPU). */
    public record FrameUniformsData(float[] viewProj, float[] invViewProj, float[] shadowViewProj,
                                    float camX, float camY, float camZ, float time,
                                    float[] sunDir, float sunVisibility, float[] lightDir, float noonFactor,
                                    float[] skyColor, float renderDistance, float[] fogColor,
                                    float rainFactor, float thunderFactor, float shadowDistance,
                                    int dimension, int fogType, float moonBrightness, float caveFactor,
                                    float exposure, long frameIndex, float[] frustum,
                                    float[] shadowFrustum, float[] shadowEye, float[] viewProjUnjittered) {
    }

    /**
     * Nimmt den kompletten Terrain-Frame auf und haengt ihn in Mojangs aktuelle
     * Submission. Liefert false, wenn Vanilla stattdessen rendern soll.
     */
    public boolean renderFrame(int slot, FrameUniformsData d, TerrainStreamer streamer,
                               RenderTarget main, long atlasView) {
        waterSlot = -1;
        if (!ready) return false;
        if (!(main.getColorTexture() instanceof VulkanGpuTexture mainColor)
                || !(main.getDepthTexture() instanceof VulkanGpuTexture mainDepth)) {
            return false;
        }
        try (Arena arena = new Arena()) {
            long tw0 = System.nanoTime();
            if (!waitValue(slotValue[slot])) return false;
            slotWaitNs += System.nanoTime() - tw0;
            collectTimestamps(slot);
            ensureSize(main.width, main.height);
            if (snapshotSource >= 0) {
                int src = snapshotSource;
                snapshotSource = -1;
                writeSnapshot(src);
            }

            currentSlot = slot;
            stagingCursor = 0;
            copies.clear();
            fills.clear();
            streamer.flushUploads(this);
            if (lod != null && lodReady) lod.flushUploads(this);
            boolean shadows = d.shadowDistance() > 0f;
            shadowsOn = shadows;
            writeUniforms(uniforms[slot], d, false);
            if (shadows) writeUniforms(shadowUniforms[slot], d, true);

            VkCommandBuffer cmd = cmds[slot];
            check(VK10.vkResetCommandBuffer(cmd, 0), "reset");
            VkCommandBufferBeginInfo bi = VkCommandBufferBeginInfo.calloc(arena.stack()).sType$Default()
                    .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(VK10.vkBeginCommandBuffer(cmd, bi), "begin");
            int q0 = slot * TS_PER_FRAME;
            VK10.vkCmdResetQueryPool(cmd, queryPool, q0, TS_PER_FRAME);
            fullBarrier(arena, cmd); // Mojangs vorige Writes (Atlas-Uploads, Clear) sichtbar machen
            VK10.vkCmdWriteTimestamp(cmd, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, q0);
            if (!imagesInitialized) {
                initImages(arena, cmd);
                imagesInitialized = true;
            }
            if (lodReady && !lodClustersCleared) {
                // VMA-Speicher ist nicht genullt: freie Cluster-Slots muessen meshletCount 0 haben
                VK10.vkCmdFillBuffer(cmd, lodClusters.buffer(), 0, lodClusters.size(), 0);
                VK10.vkCmdFillBuffer(cmd, lodMeshlets.buffer(), 0, lodMeshlets.size(), 0);
                barrier(arena, cmd, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK10.VK_ACCESS_TRANSFER_WRITE_BIT, VK10.VK_ACCESS_TRANSFER_WRITE_BIT);
                lodClustersCleared = true;
            }
            recordUploads(arena, cmd, slot);
            stamp(cmd, q0 + 1);
            if (rt != null && !rtForceOff) recordRt(arena, cmd, slot, d);
            if (pipeLodGen != 0L) recordLodGpu(arena, cmd, slot);
            stamp(cmd, q0 + 2);
            if (!visBitsCleared) {
                VK10.vkCmdFillBuffer(cmd, visBits.buffer(), 0, visBits.size(), 0);
                if (lodReady) VK10.vkCmdFillBuffer(cmd, lodVisBits.buffer(), 0, lodVisBits.size(), 0);
                barrier(arena, cmd, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK10.VK_ACCESS_TRANSFER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
                visBitsCleared = true;
            }
            stamp(cmd, q0 + 3);
            // Phase 1: im Vorframe Sichtbares (ohne Hi-Z), Schatten-Cull wie gehabt
            recordCull(arena, cmd, uniforms[slot], visMeshlets, visMeshletCount, meshIndirect, 1);
            recordLodCull(arena, cmd, uniforms[slot], lodVis1, lodIndirect1, 1);
            if (shadows) {
                recordCull(arena, cmd, shadowUniforms[slot], visShadow, visShadowCount, shadowIndirect, 0);
                // Fernfeld als Schattenwerfer nur ab der Hoehe, ab der das Nahfeld sie selbst laedt
                // (TerrainStreamer.syncShadowCasters): dort fuellt es Luecken, bis die Sections gemesht sind
                float shadowFloor = (Math.floorDiv((int) Math.floor(d.camY()), 16) - TerrainStreamer.SHADOW_BELOW) * 16f;
                if (pipeLodShadow != 0L && !NO_LOD_SHADOW) recordLodCull(arena, cmd, shadowUniforms[slot], lodVisShadow, lodShadowIndirect, 0,
                        shadowFloor, lodMaskData != null ? TerrainStreamer.SHADOW_RADIUS * 16f : 1e30f); // Mitte = Masken-Chunk
            }
            barrier(arena, cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK10.VK_ACCESS_SHADER_READ_BIT);
            stamp(cmd, q0 + 4);
            if (shadows) recordShadow(arena, cmd, slot, atlasView);
            stamp(cmd, q0 + 5);
            if (d.dimension() != 0) {
                // Nether/End: Vanillas Himmel wird als Hintergrund weiterverwendet
                blit(arena, cmd, mainColor.vkImage(), background.image(), width, height);
            }
            recordGbuffer(arena, cmd, slot, atlasView, true, visMeshlets, meshIndirect, lodVis1, lodIndirect1);
            // Hi-Z aus der AKTUELLEN Tiefe (Phase 1), Phase 2 testet alles andere dagegen
            boolean hizOn = hizEnabled && !hizForceOff;
            if (hizOn) recordHiz(arena, cmd);
            recordCull(arena, cmd, uniforms[slot], visMeshlets2, visMeshletCount, meshIndirect2, 2);
            recordLodCull(arena, cmd, uniforms[slot], lodVis2, lodIndirect2, 2);
            barrier(arena, cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK10.VK_ACCESS_SHADER_READ_BIT);
            recordGbuffer(arena, cmd, slot, atlasView, false, visMeshlets2, meshIndirect2, lodVis2, lodIndirect2);
            stamp(cmd, q0 + 6);
            recordDeferred(arena, cmd, slot, atlasView);
            stamp(cmd, q0 + 7);
            recordBloom(arena, cmd);
            stamp(cmd, q0 + 8);
            recordFinal(arena, cmd, slot);
            // Ergebnis ins Main-Target, Tiefe ins Main-Depth (gleiche Reverse-Z-Projektion)
            blit(arena, cmd, ldr.image(), mainColor.vkImage(), width, height);
            copyDepth(arena, cmd, depth.image(), mainDepth.vkImage());
            stamp(cmd, q0 + 9);
            fullBarrier(arena, cmd);
            check(VK10.vkEndCommandBuffer(cmd), "end");

            VulkanCommandEncoder encoder = blaze.commandEncoder();
            encoder.execute(cmd);
            encoder.signalSemaphore(timeline, ++timelineValue, STAGE2_ALL_COMMANDS);
            slotValue[slot] = timelineValue;
            slotHasTimestamps[slot] = true;
            waterSlot = slot;
            frameData = d;
            lastAtlasView = atlasView;
            if (!loggedOnce) {
                loggedOnce = true;
                LOG.info("[vulkanfish] Erster GPU-driven Frame in Mojangs Submission ({}x{})", width, height);
            }
            return true;
        } catch (Throwable t) {
            LOG.warn("[vulkanfish] Native Submission deaktiviert, Vanilla rendert", t);
            ready = false;
            disableReason = t.toString();
            return false;
        }
    }

    /**
     * Wasser-Pass (aus Vanillas TRANSLUCENT-Gruppe, nach den Entities): Farbe/Tiefe
     * des Main-Targets kopieren, dann Wasser-Meshlets direkt hineinrendern.
     * Nutzt die Sichtbarkeitsliste aus dem Cull desselben Frames.
     */
    public void renderWater(RenderTarget main) {
        int slot = waterSlot; // bleibt gesetzt: TAA am Frame-Ende nutzt denselben Slot
        if (!ready || slot < 0) return;
        if (!(main.getColorTexture() instanceof VulkanGpuTexture mainColor)
                || !(main.getDepthTexture() instanceof VulkanGpuTexture mainDepth)
                || !(main.getColorTextureView() instanceof VulkanGpuTextureView colorView)
                || !(main.getDepthTextureView() instanceof VulkanGpuTextureView depthView)
                || main.width != width || main.height != height) {
            return;
        }
        try (Arena arena = new Arena()) {
            int format = VulkanConst.toVk(mainColor.getFormat());
            if (pipeWater == 0L || format != waterColorFormat) {
                // Kein Warten hier: der Terrain-Frame dieses Frames ist noch nicht eingereicht.
                // Eine alte Pipeline bleibt in 'pipelines' und wird erst beim Shutdown zerstoert.
                pipeWater = meshPipe(arena, layoutWater, waterMeshModule, waterFragModule, 1, format,
                        VK10.VK_COMPARE_OP_GREATER_OR_EQUAL, false);
                pipeLodWater = meshPipe(arena, layoutWater, lodWaterMeshModule, lodWaterFragModule, 1, format,
                        VK10.VK_COMPARE_OP_GREATER_OR_EQUAL, false);
                // schreibt die Tiefe der naechsten Glasschicht (wie Vanillas transluzentes Terrain):
                // Risse, Partikel, Regen danach werden von Glas/Eis richtig verdeckt
                pipeEntityShade = meshPipe(arena, layoutEntityShade, oitFsMeshModule, entityShadeFragModule, 1, format,
                        VK10.VK_COMPARE_OP_ALWAYS, false, BLEND_MULTIPLY, false, false);
                pipeOitComposite = meshPipe(arena, layoutOitComposite, oitFsMeshModule, oitCompositeFragModule, 1, format,
                        VK10.VK_COMPARE_OP_GREATER_OR_EQUAL, false, BLEND_PREMUL_UNDER, true, true);
                waterColorFormat = format;
                if (pipeWater == 0L || pipeOitComposite == 0L) throw new IllegalStateException("Wasser/Glas-Pipeline fehlgeschlagen");
            }
            VkCommandBuffer cmd = waterCmds[slot];
            check(VK10.vkResetCommandBuffer(cmd, 0), "resetWater");
            check(VK10.vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(arena.stack()).sType$Default()
                    .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "beginWater");
            int q = slot * TS_PER_FRAME + TS_TERRAIN;
            fullBarrier(arena, cmd); // Entities/Partikel sind jetzt im Main-Target
            VK10.vkCmdWriteTimestamp(cmd, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, q);
            copyDepth(arena, cmd, mainDepth.vkImage(), sceneDepth.image());
            if (shadowsOn && pipeEntityShade != 0L) {
                // Sonnenschatten auf Vanillas Entities/Block-Entities/Partikel (vor der Szenen-Kopie)
                fullBarrier(arena, cmd);
                VkRenderingAttachmentInfo.Buffer shadeColor = VkRenderingAttachmentInfo.calloc(1, arena.stack());
                shadeColor.get(0).sType$Default().imageView(colorView.vkImageView()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                        .loadOp(VK10.VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE);
                VkRenderingAttachmentInfo shadeDepth = VkRenderingAttachmentInfo.calloc(arena.stack()).sType$Default()
                        .imageView(depthView.vkImageView()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                        .loadOp(VK10.VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE);
                VkRenderingInfo sri = VkRenderingInfo.calloc(arena.stack()).sType$Default().layerCount(1)
                        .pColorAttachments(shadeColor).pDepthAttachment(shadeDepth);
                sri.renderArea().offset().set(0, 0);
                sri.renderArea().extent().set(width, height);
                KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, sri);
                setViewport(arena, cmd, width, height);
                VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeEntityShade);
                push(arena, cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, layoutEntityShade, W.ub(0, uniforms[slot]),
                        W.si(1, sceneDepth.view()), W.si(2, depth.view()), W.si(3, shadowMap.view()), W.sm(4, shadowSampler));
                EXTMeshShader.vkCmdDrawMeshTasksEXT(cmd, 1, 1, 1);
                KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
                fullBarrier(arena, cmd);
            }
            blit(arena, cmd, mainColor.vkImage(), sceneCopy.image(), width, height);
            // Listen leeren: Kopf = 0xFFFFFFFF, Zaehler 0, Kapazitaet fuer den Shader
            VkClearColorValue empty = VkClearColorValue.calloc(arena.stack());
            empty.uint32(0, -1).uint32(1, -1).uint32(2, -1).uint32(3, -1);
            VkImageSubresourceRange range = VkImageSubresourceRange.calloc(arena.stack())
                    .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VK10.vkCmdClearColorImage(cmd, oitHead.image(), VK10.VK_IMAGE_LAYOUT_GENERAL, empty, range);
            VK10.vkCmdUpdateBuffer(cmd, oitCounter.buffer(), 0, arena.ints(0, oitCapacity, 0, 0));
            fullBarrier(arena, cmd);
            stamp(cmd, slot * TS_PER_FRAME + TS_TERRAIN + 4);

            W[] waterBindings = {W.sb(0, meshlets), W.sb(1, verts), W.sb(2, tris), W.ub(3, uniforms[slot]),
                    W.si(4, sceneCopy.view()), W.sm(5, linearSampler), W.sb(6, visWater),
                    W.si(7, sceneDepth.view()), W.si(8, shadowMap.view()), W.sm(9, shadowSampler),
                    W.si(10, lastAtlasView), W.sm(11, atlasSampler),
                    W.st(12, oitHead.view()), W.sb(13, oitNodes), W.sb(14, oitCounter)};
            W[] transBindings = waterBindings.clone();
            transBindings[6] = W.sb(6, visTrans);
            boolean lodWater = lodReady && lodClusterCount > 0 && pipeLodWater != 0L && pipeLodWaterDepth != 0L;
            W[] lodWaterBindings = lodWater ? new W[]{W.sb(0, lodMeshlets), W.ub(3, uniforms[slot]),
                    W.si(4, sceneCopy.view()), W.sm(5, linearSampler), W.sb(6, lodVisWater),
                    W.si(7, sceneDepth.view()), W.si(8, shadowMap.view()), W.sm(9, shadowSampler),
                    W.si(10, lastAtlasView), W.sm(11, atlasSampler),
                    W.sb(15, lodQuads), W.sb(16, lodNearMask), W.sb(17, lodDrawBiome)} : null;
            ByteBuffer lodWaterPc = arena.malloc(16);
            lodWaterPc.putInt(0, lodMaskCamX).putInt(4, lodMaskCamZ).putInt(8, lodMaskData != null ? 1 : 0).putInt(12, lodMaskMinSY);

            // 1) Nur Tiefe: Glas/Eis-Fragmente in die Pro-Pixel-Listen (gegen Szene ohne Wasser),
            //    danach die Wasseroberflaeche als Tiefe (trennt Glas davor/dahinter)
            VkRenderingAttachmentInfo depthAtt = VkRenderingAttachmentInfo.calloc(arena.stack()).sType$Default()
                    .imageView(depthView.vkImageView()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .loadOp(VK10.VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE);
            VkRenderingInfo ri = VkRenderingInfo.calloc(arena.stack()).sType$Default();
            ri.renderArea().offset().set(0, 0);
            ri.renderArea().extent().set(width, height);
            ri.layerCount(1).pDepthAttachment(depthAtt);
            KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, ri);
            setViewport(arena, cmd, width, height);
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeTrans);
            push(arena, cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, layoutWater, transBindings);
            EXTMeshShader.vkCmdDrawMeshTasksIndirectEXT(cmd, transIndirect.buffer(), 0L, 1, 12);
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeWaterDepth);
            push(arena, cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, layoutWater, waterBindings);
            EXTMeshShader.vkCmdDrawMeshTasksIndirectEXT(cmd, waterIndirect.buffer(), 0L, 1, 12);
            if (lodWater) {
                VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeLodWaterDepth);
                push(arena, cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, layoutWater, lodWaterBindings);
                VK10.vkCmdPushConstants(cmd, layoutWater, EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT, 0, lodWaterPc);
                EXTMeshShader.vkCmdDrawMeshTasksIndirectEXT(cmd, lodWaterIndirect.buffer(), 0L, 1, 12);
            }
            KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
            fullBarrier(arena, cmd);
            stamp(cmd, slot * TS_PER_FRAME + TS_TERRAIN + 5);

            // 2) Listen sortieren: Glas hinter dem Wasser -> Szenen-Kopie (Brechung), davor -> oitFront
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeOitResolve);
            push(arena, cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layoutOit,
                    W.st(0, oitHead.view()), W.sb(1, oitNodes), W.si(2, depthView.vkImageView()),
                    W.si(3, sceneDepth.view()), W.st(4, sceneCopy.view()), W.st(5, oitFront.view()), W.sb(6, oitCounter),
                    W.st(7, oitFrontDepth.view()));
            ByteBuffer pc = arena.malloc(8);
            pc.putInt(0, width).putInt(4, height);
            VK10.vkCmdPushConstants(cmd, layoutOit, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
            VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
            fullBarrier(arena, cmd);
            stamp(cmd, slot * TS_PER_FRAME + TS_TERRAIN + 6);

            // 3) Wasser (liest die Szenen-Kopie inkl. Glas darunter), dann Glas davor ueberblenden
            VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(1, arena.stack());
            color.get(0).sType$Default().imageView(colorView.vkImageView()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .loadOp(VK10.VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE);
            ri.pColorAttachments(color);
            KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, ri);
            setViewport(arena, cmd, width, height);
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeWater);
            push(arena, cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, layoutWater, waterBindings);
            EXTMeshShader.vkCmdDrawMeshTasksIndirectEXT(cmd, waterIndirect.buffer(), 0L, 1, 12);
            if (lodWater) {
                // Fernfeld-Wasser mit derselben Schattierung (Brechung des LOD-Grundes, SSR, Wellen)
                VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeLodWater);
                push(arena, cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, layoutWater, lodWaterBindings);
                VK10.vkCmdPushConstants(cmd, layoutWater, EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT, 0, lodWaterPc);
                EXTMeshShader.vkCmdDrawMeshTasksIndirectEXT(cmd, lodWaterIndirect.buffer(), 0L, 1, 12);
            }
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeOitComposite);
            push(arena, cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, layoutOitComposite, W.si(0, oitFront.view()),
                    W.si(1, oitFrontDepth.view()));
            EXTMeshShader.vkCmdDrawMeshTasksEXT(cmd, 1, 1, 1);
            KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
            stamp(cmd, q + 1);
            fullBarrier(arena, cmd);
            check(VK10.vkEndCommandBuffer(cmd), "endWater");

            VulkanCommandEncoder encoder = blaze.commandEncoder();
            encoder.execute(cmd);
            encoder.signalSemaphore(timeline, ++timelineValue, STAGE2_ALL_COMMANDS);
            slotValue[slot] = timelineValue;
        } catch (Throwable t) {
            LOG.warn("[vulkanfish] Wasser-Pass deaktiviert, native Submission aus", t);
            ready = false;
            disableReason = t.toString();
        }
    }

    /**
     * TAA am Ende des Level-Renderns (Main-Target inkl. Vanilla-Anteil, vor Hand/GUI):
     * aktuelles Bild + History -> neue History + nachgeschaerfte Ausgabe -> Main-Target.
     */
    public void renderTaa(RenderTarget main) {
        int slot = waterSlot;
        waterSlot = -1;
        FrameUniformsData d = frameData;
        if (!ready || slot < 0 || d == null) return;
        if (!(main.getColorTexture() instanceof VulkanGpuTexture mainColor)
                || !(main.getColorTextureView() instanceof VulkanGpuTextureView colorView)
                || !(main.getDepthTextureView() instanceof VulkanGpuTextureView depthView)
                || main.width != width || main.height != height) {
            historyValid = false;
            return;
        }
        try (Arena arena = new Arena()) {
            ByteBuffer ub = MemoryUtil.memByteBuffer(taaUniforms[slot].mapped(), TAA_UBO_BYTES);
            putMat(ub, 0, d.invViewProj());
            putMat(ub, 64, prevViewProjUnjittered != null ? prevViewProjUnjittered : d.viewProjUnjittered());
            ub.putFloat(128, width).putFloat(132, height);
            ub.putInt(136, historyValid && prevViewProjUnjittered != null ? 1 : 0);
            ub.putFloat(140, 0.45f); // Nachschaerfen (gleicht die TAA-Weichheit aus)

            Img histIn = history[historyIndex];
            Img histOut = history[historyIndex ^ 1];
            VkCommandBuffer cmd = taaCmds[slot];
            check(VK10.vkResetCommandBuffer(cmd, 0), "resetTaa");
            check(VK10.vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(arena.stack()).sType$Default()
                    .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "beginTaa");
            int q = slot * TS_PER_FRAME + TS_TERRAIN + 2;
            fullBarrier(arena, cmd); // alle Level-Passes (auch Vanilla) fertig
            VK10.vkCmdWriteTimestamp(cmd, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, q);
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeTaa);
            push(arena, cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layoutTaa,
                    W.si(0, colorView.vkImageView()), W.si(1, depthView.vkImageView()), W.si(2, histIn.view()),
                    W.st(3, histOut.view()), W.st(4, taaOut.view()), W.sm(5, linearSampler), W.ub(6, taaUniforms[slot]));
            VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
            barrier(arena, cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_ACCESS_SHADER_WRITE_BIT | VK10.VK_ACCESS_SHADER_READ_BIT,
                    VK10.VK_ACCESS_TRANSFER_READ_BIT | VK10.VK_ACCESS_TRANSFER_WRITE_BIT);
            blit(arena, cmd, taaOut.image(), mainColor.vkImage(), width, height);
            stamp(cmd, q + 1);
            fullBarrier(arena, cmd);
            check(VK10.vkEndCommandBuffer(cmd), "endTaa");

            VulkanCommandEncoder encoder = blaze.commandEncoder();
            encoder.execute(cmd);
            encoder.signalSemaphore(timeline, ++timelineValue, STAGE2_ALL_COMMANDS);
            slotValue[slot] = timelineValue;
            historyIndex ^= 1;
            historyValid = true;
            prevViewProjUnjittered = d.viewProjUnjittered();
        } catch (Throwable t) {
            LOG.warn("[vulkanfish] TAA-Pass deaktiviert, native Submission aus", t);
            ready = false;
            disableReason = t.toString();
        }
    }

    private void stamp(VkCommandBuffer cmd, int query) {
        VK10.vkCmdWriteTimestamp(cmd, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, query);
    }

    /** Timestamps des Slots auslesen (die GPU ist nach waitValue fertig damit). */
    private void collectTimestamps(int slot) {
        if (!slotHasTimestamps[slot]) return;
        try (Arena arena = new Arena()) {
            LongBuffer ts = arena.mallocLong(TS_PER_FRAME);
            int res = VK10.vkGetQueryPoolResults(dev, queryPool, slot * TS_PER_FRAME, TS_TERRAIN, ts, 8,
                    VK10.VK_QUERY_RESULT_64_BIT);
            if (res != VK10.VK_SUCCESS) return;
            for (int i = 0; i < PASS_NAMES.length; i++) {
                passMsSum[i] += Math.max(0, ts.get(i + 1) - ts.get(i)) * timestampPeriodNs / 1_000_000.0;
            }
            passSamples++;
            // GPU-Zeit des Frames ohne LOD-Arbeit (fuer das LOD-Budget aus Leerlauf)
            double frameMs = Math.max(0, ts.get(PASS_NAMES.length) - ts.get(0)) * timestampPeriodNs / 1_000_000.0;
            lodGpuFrameMsEma = lodGpuFrameMsEma * 0.9 + Math.max(0, frameMs - lodLastLodMs) * 0.1;
            LongBuffer ta = arena.mallocLong(2);
            if (VK10.vkGetQueryPoolResults(dev, queryPool, slot * TS_PER_FRAME + TS_TERRAIN + 2, 2, ta, 8,
                    VK10.VK_QUERY_RESULT_64_BIT) == VK10.VK_SUCCESS) {
                taaMsSum += Math.max(0, ta.get(1) - ta.get(0)) * timestampPeriodNs / 1_000_000.0;
                taaSamples++;
                // Spanne Terrain-Start .. TAA-Ende: enthaelt Vanillas Arbeit dazwischen (Entities, Wolken ...)
                spanMsSum += Math.max(0, ta.get(1) - ts.get(0)) * timestampPeriodNs / 1_000_000.0;
            }
            // Wasser-Pass laeuft nicht in jedem Frame (dann NOT_READY)
            LongBuffer tw = arena.mallocLong(2);
            if (VK10.vkGetQueryPoolResults(dev, queryPool, slot * TS_PER_FRAME + TS_TERRAIN, 2, tw, 8,
                    VK10.VK_QUERY_RESULT_64_BIT) == VK10.VK_SUCCESS) {
                waterMsSum += Math.max(0, tw.get(1) - tw.get(0)) * timestampPeriodNs / 1_000_000.0;
                waterSamples++;
                LongBuffer tp = arena.mallocLong(3);
                if (VK10.vkGetQueryPoolResults(dev, queryPool, slot * TS_PER_FRAME + TS_TERRAIN + 4, 3, tp, 8,
                        VK10.VK_QUERY_RESULT_64_BIT) == VK10.VK_SUCCESS) {
                    long[] t = {tw.get(0), tp.get(0), tp.get(1), tp.get(2), tw.get(1)};
                    for (int i = 0; i < 4; i++) waterPartMs[i] += Math.max(0, t[i + 1] - t[i]) * timestampPeriodNs / 1_000_000.0;
                    waterPartN++;
                }
            }
        }
    }

    /** Wartet auf einen Timeline-Wert; false bei Timeout (Frame wird dann von Vanilla gerendert). */
    private boolean waitValue(long value) {
        if (value == 0L) return true;
        try (Arena arena = new Arena()) {
            VkSemaphoreWaitInfo wi = VkSemaphoreWaitInfo.calloc(arena.stack()).sType$Default()
                    .semaphoreCount(1).pSemaphores(arena.longs(timeline)).pValues(arena.longs(value));
            int res = VK12.vkWaitSemaphores(dev, wi, WAIT_TIMEOUT_NS);
            if (res == VK12.VK_TIMEOUT) {
                LOG.warn("[vulkanfish] Timeline-Wert {} nach 2s nicht erreicht – Frame an Vanilla", value);
                return false;
            }
            check(res, "waitSemaphores");
            return true;
        }
    }

    private void waitAll() {
        if (timeline != 0L) waitValue(timelineValue);

    }

    /** std140-Layout von FrameUniforms (gpu_scene.slang). Schatten-Variante: Lichtraum als Kamera. */
    private void writeUniforms(Buf ubo, FrameUniformsData d, boolean shadowPass) {
        ByteBuffer bb = MemoryUtil.memByteBuffer(ubo.mapped(), UBO_BYTES);
        if (shadowPass) {
            putMat(bb, 0, d.shadowViewProj());
            putMat(bb, 64, d.shadowViewProj());
        } else {
            putMat(bb, 0, d.viewProj());
            // Hi-Z entstand mit der Vorframe-Matrix -> dort testen
            putMat(bb, 64, d.viewProj()); // Hi-Z entsteht im selben Frame (Zwei-Phasen-Culling)
        }
        putMat(bb, 128, d.invViewProj());
        putMat(bb, 192, d.shadowViewProj());
        if (shadowPass) {
            // "Kamera" weit in Lichtrichtung: der Meshlet-Ebenentest verwirft lichtabgewandte Flaechen
            float[] e = d.shadowEye();
            bb.putFloat(256, e[0]).putFloat(260, e[1]).putFloat(264, e[2]);
        } else {
            bb.putFloat(256, d.camX()).putFloat(260, d.camY()).putFloat(264, d.camZ());
        }
        bb.putFloat(268, d.time());
        putVec3(bb, 272, d.sunDir());
        bb.putFloat(284, d.sunVisibility());
        putVec3(bb, 288, d.lightDir());
        bb.putFloat(300, d.noonFactor());
        putVec3(bb, 304, d.skyColor());
        bb.putFloat(316, d.renderDistance());
        putVec3(bb, 320, d.fogColor());
        bb.putFloat(332, d.rainFactor());
        bb.putFloat(336, width).putFloat(340, height);
        bb.putInt(344, !shadowPass && hizEnabled && !hizForceOff ? hiz.mips() : 0);
        bb.putInt(348, (int) (d.frameIndex() & 0xFFFFFFFFL));
        bb.putFloat(352, d.shadowDistance());
        bb.putInt(356, d.dimension());
        bb.putInt(360, d.fogType());
        bb.putFloat(364, d.moonBrightness());
        float[] fr = shadowPass ? d.shadowFrustum() : d.frustum();
        for (int i = 0; i < 24; i++) bb.putFloat(368 + i * 4, fr[i]);
        bb.putFloat(464, shadowPass ? 1e9f : 1.0f); // lodErrorThreshold
        bb.putFloat(468, d.thunderFactor());
        bb.putFloat(472, d.caveFactor());
        bb.putFloat(476, d.exposure());
        bb.putInt(480, debugView);
        bb.putInt(484, shadowPass ? 1 : 0); // cullFlags: Schatten-Cull ohne Wasser
        bb.putInt(488, rt != null && !rtForceOff ? 1 : 0);
        bb.putInt(492, rt != null ? rt.lightCount() : 0);
        bb.putInt(496, RtAccel.gridOrigin(d.camX())).putInt(500, RtAccel.gridOrigin(d.camY()))
                .putInt(504, RtAccel.gridOrigin(d.camZ())).putInt(508, 0);
        bb.putFloat(512, simon.vulkanfish.client.VulkanfishSettings.fullbright());
    }

    /** Selbsttest/A-B: Raytracing-Blocklicht abschalten (Vanilla-Blocklicht, gleiche Pipeline). */
    public static volatile boolean rtForceOff = Boolean.getBoolean("vulkanfish.rtOff");

    /** Selbsttest: Hi-Z-Occlusion abschalten (A/B-Vergleich auf faelschlich verdeckte Meshlets). */
    public static volatile boolean hizForceOff = Boolean.getBoolean("vulkanfish.hizOff");

    /** Debug-Ansicht des Deferred-Passes: 0 aus, 1 Schatten, 2 Normalen, 3 Albedo, 4 Licht. */
    public static volatile int debugView = Integer.getInteger("vulkanfish.debugView", 0);

    private static void putMat(ByteBuffer bb, int off, float[] m) {
        for (int i = 0; i < 16; i++) bb.putFloat(off + i * 4, m[i]);
    }

    private static void putVec3(ByteBuffer bb, int off, float[] v) {
        bb.putFloat(off, v[0]).putFloat(off + 4, v[1]).putFloat(off + 8, v[2]);
    }

    private void initImages(Arena arena, VkCommandBuffer cmd) {
        int color = VK10.VK_IMAGE_ASPECT_COLOR_BIT;
        for (Img img : new Img[]{gAlbedo, gNormal, hiz, hdr, bloom, ldr, background, sceneCopy, history[0], history[1], taaOut,
                oitHead, oitFront, oitFrontDepth}) {
            toGeneral(arena, cmd, img.image(), img.mips(), color);
        }
        toGeneral(arena, cmd, depth.image(), 1, VK10.VK_IMAGE_ASPECT_DEPTH_BIT);
        toGeneral(arena, cmd, sceneDepth.image(), 1, VK10.VK_IMAGE_ASPECT_DEPTH_BIT);
        if (!shadowInitialized) {
            toGeneral(arena, cmd, shadowMap.image(), 1, VK10.VK_IMAGE_ASPECT_DEPTH_BIT);
            shadowInitialized = true;
        }
        VkClearColorValue zero = VkClearColorValue.calloc(arena.stack());
        VkImageSubresourceRange range = VkImageSubresourceRange.calloc(arena.stack())
                .aspectMask(color).baseMipLevel(0).levelCount(hiz.mips()).baseArrayLayer(0).layerCount(1);
        VK10.vkCmdClearColorImage(cmd, hiz.image(), VK10.VK_IMAGE_LAYOUT_GENERAL, zero, range);
        VkImageSubresourceRange dRange = VkImageSubresourceRange.calloc(arena.stack())
                .aspectMask(VK10.VK_IMAGE_ASPECT_DEPTH_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        VK10.vkCmdClearDepthStencilImage(cmd, depth.image(), VK10.VK_IMAGE_LAYOUT_GENERAL,
                VkClearDepthStencilValue.calloc(arena.stack()).depth(0.0f), dRange);
        VK10.vkCmdClearDepthStencilImage(cmd, shadowMap.image(), VK10.VK_IMAGE_LAYOUT_GENERAL,
                VkClearDepthStencilValue.calloc(arena.stack()).depth(1.0f), dRange);
        fullBarrier(arena, cmd);
    }

    private void recordUploads(Arena arena, VkCommandBuffer cmd, int slot) {
        for (long[] f : fills) {
            VK10.vkCmdFillBuffer(cmd, target(f[0]).buffer(), f[1], f[2], 0);
        }
        for (int t = TARGET_CLUSTERS; t <= TARGET_LOD_CLUSTERS; t++) {
            int n = 0;
            for (long[] c : copies) if (c[0] == t) n++;
            if (n == 0) continue;
            VkBufferCopy.Buffer regions = VkBufferCopy.calloc(n, arena.stack());
            int i = 0;
            for (long[] c : copies) {
                if (c[0] != t) continue;
                regions.get(i++).srcOffset(c[1]).dstOffset(c[2]).size(c[3]);
            }
            VK10.vkCmdCopyBuffer(cmd, staging[slot].buffer(), target(t).buffer(), regions);
        }
        // Zaehler: belegte Meshlet-Slots, Indirect {0,1,1} (x = Atomik-Zaehler des Culls)
        VK10.vkCmdUpdateBuffer(cmd, meshletTotal.buffer(), 0, arena.ints(clusterCount));
        VK10.vkCmdUpdateBuffer(cmd, meshIndirect.buffer(), 0, arena.ints(0, 1, 1));
        VK10.vkCmdUpdateBuffer(cmd, meshIndirect2.buffer(), 0, arena.ints(0, 1, 1));
        if (lodReady) {
            VK10.vkCmdUpdateBuffer(cmd, lodIndirect1.buffer(), 0, arena.ints(0, 1, 1));
            VK10.vkCmdUpdateBuffer(cmd, lodIndirect2.buffer(), 0, arena.ints(0, 1, 1));
            VK10.vkCmdUpdateBuffer(cmd, lodWaterIndirect.buffer(), 0, arena.ints(0, 1, 1));
            VK10.vkCmdUpdateBuffer(cmd, lodShadowIndirect.buffer(), 0, arena.ints(0, 4, 1)); // y: 4 Gruppen je Meshlet (lodShadowMesh)
            VK10.vkCmdUpdateBuffer(cmd, lodClusterTotal.buffer(), 0, arena.ints(lodClusterCount));
            if (lodMaskPending && lodMaskData != null) {
                lodMaskPending = false;
                ByteBuffer mb = arena.malloc(lodMaskData.length * 4);
                mb.asIntBuffer().put(lodMaskData);
                VK10.vkCmdUpdateBuffer(cmd, lodNearMask.buffer(), 0, mb);
            }
        }
        VK10.vkCmdUpdateBuffer(cmd, shadowIndirect.buffer(), 0, arena.ints(0, 1, 1));
        VK10.vkCmdUpdateBuffer(cmd, waterIndirect.buffer(), 0, arena.ints(0, 1, 1));
        VK10.vkCmdUpdateBuffer(cmd, transIndirect.buffer(), 0, arena.ints(0, 1, 1));
        VK10.vkCmdFillBuffer(cmd, visMeshletCount.buffer(), 0, 4, 0);
        VK10.vkCmdFillBuffer(cmd, visShadowCount.buffer(), 0, 4, 0);
        barrier(arena, cmd, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT | VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
    }

    private void recordHiz(Arena arena, VkCommandBuffer cmd) {
        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeHiz);
        for (int mip = 0; mip < hiz.mips(); mip++) {
            int w = Math.max(width >> mip, 1);
            int h = Math.max(height >> mip, 1);
            // Mip 0: Seed aus dem Tiefenpuffer des Vorframes; danach Min-Reduktion
            push(arena, cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layoutHiz,
                    W.si(0, mip == 0 ? depth.view() : hiz.view()), W.st(1, hizMipViews[mip]), W.sm(2, linearSampler));
            ByteBuffer pc = arena.malloc(16);
            pc.putInt(0, w).putInt(4, h).putInt(8, Math.max(mip - 1, 0)).putInt(12, mip == 0 ? 1 : 0);
            VK10.vkCmdPushConstants(cmd, layoutHiz, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
            VK10.vkCmdDispatch(cmd, (w + 7) / 8, (h + 7) / 8, 1);
            barrier(arena, cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT);
        }
    }

    private void recordCull(Arena arena, VkCommandBuffer cmd, Buf ubo, Buf visible, Buf counter, Buf indirect, int phase) {
        if (clusterCount == 0 || meshletCount == 0) return;
        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeCull);
        push(arena, cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layoutCull,
                W.sb(0, clusters), W.sb(1, meshlets), W.ub(2, ubo),
                W.si(3, hiz.view()), W.sm(4, linearSampler),
                W.sb(5, meshletTotal), W.sb(6, visible), W.sb(7, counter), W.sb(8, indirect),
                W.sb(9, visWater), W.sb(10, waterIndirect), W.sb(11, visTrans), W.sb(12, transIndirect),
                W.sb(13, visBits), W.sb(14, lodNearMask != null ? lodNearMask : visBits));
        VK10.vkCmdPushConstants(cmd, layoutCull, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0,
                arena.ints(phase, 0, 0, 0, Float.floatToRawIntBits(-1e30f), Float.floatToRawIntBits(1e30f), 0, 0));
        // Eine Workgroup pro belegtem Cluster (hierarchischer Cull)
        VK10.vkCmdDispatch(cmd, clusterCount, 1, 1);
    }

    /** Fernfeld-Cull: gleiche Pipeline (Zwei-Phasen, Hi-Z), eigene Hierarchie und Listen; Phase 0 = Schattenkarte. */
    private void recordLodCull(Arena arena, VkCommandBuffer cmd, Buf ubo, Buf visible, Buf indirect, int phase) {
        recordLodCull(arena, cmd, ubo, visible, indirect, phase, -1e30f, 1e30f);
    }

    /**
     * minTopY / maxDistXZ: Meshlets ganz darunter bzw. waagerecht weiter weg verwerfen (Schatten: nur
     * wo das Nahfeld selbst Schattenwerfer laedt, siehe TerrainStreamer.syncShadowCasters).
     */
    private void recordLodCull(Arena arena, VkCommandBuffer cmd, Buf ubo, Buf visible, Buf indirect, int phase,
                               float minTopY, float maxDistXZ) {
        if (!lodReady || lodClusterCount == 0) return;
        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeCull);
        push(arena, cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layoutCull,
                W.sb(0, lodClusters), W.sb(1, lodMeshlets), W.ub(2, ubo),
                W.si(3, hiz.view()), W.sm(4, linearSampler),
                W.sb(5, lodClusterTotal), W.sb(6, visible), W.sb(7, phase == 0 ? visShadowCount : visMeshletCount), W.sb(8, indirect),
                W.sb(9, lodVisWater), W.sb(10, lodWaterIndirect), W.sb(11, visTrans), W.sb(12, transIndirect),
                W.sb(13, lodVisBits), W.sb(14, lodNearMask));
        VK10.vkCmdPushConstants(cmd, layoutCull, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0,
                // nearMode: Bit 0 an, darueber minSectionY + 2048
                arena.ints(phase, lodMaskData != null ? 1 | ((lodMaskMinSY + 2048) << 1) : 0, lodMaskCamX, lodMaskCamZ,
                        Float.floatToRawIntBits(minTopY), Float.floatToRawIntBits(maxDistXZ), 0, 0));
        VK10.vkCmdDispatch(cmd, lodClusterCount, 1, 1);
    }

    private void setViewport(Arena arena, VkCommandBuffer cmd, int w, int h) {
        VkViewport.Buffer vp = VkViewport.calloc(1, arena.stack());
        vp.get(0).set(0, 0, w, h, 0, 1);
        VK10.vkCmdSetViewport(cmd, 0, vp);
        VkRect2D.Buffer rect = VkRect2D.calloc(1, arena.stack());
        rect.get(0).offset().set(0, 0);
        rect.get(0).extent().set(w, h);
        VK10.vkCmdSetScissor(cmd, 0, rect);
    }

    private void recordShadow(Arena arena, VkCommandBuffer cmd, int slot, long atlasView) {
        var entityBatches = simon.vulkanfish.client.render.EntityShadowCapture.frame();
        if (!entityBatches.isEmpty()) {
            // Vanilla hat seinen Entity-Vertexpuffer vorher im selben Command-Strom befuellt
            barrier(arena, cmd, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_VERTEX_INPUT_BIT,
                    VK10.VK_ACCESS_TRANSFER_WRITE_BIT, VK10.VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT);
        }
        VkRenderingAttachmentInfo depthAtt = VkRenderingAttachmentInfo.calloc(arena.stack()).sType$Default()
                .imageView(shadowMap.view()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .loadOp(VK10.VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE);
        depthAtt.clearValue().depthStencil().depth(1.0f);
        VkRenderingInfo ri = VkRenderingInfo.calloc(arena.stack()).sType$Default();
        ri.renderArea().offset().set(0, 0);
        ri.renderArea().extent().set(SHADOW_RES, SHADOW_RES);
        ri.layerCount(1).pDepthAttachment(depthAtt);
        KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, ri);
        setViewport(arena, cmd, SHADOW_RES, SHADOW_RES);
        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeShadow);
        push(arena, cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, layoutTerrain,
                W.sb(0, meshlets), W.sb(1, verts), W.sb(2, tris), W.ub(3, shadowUniforms[slot]),
                W.si(4, atlasView), W.sm(5, atlasSampler), W.sb(6, visShadow));
        EXTMeshShader.vkCmdDrawMeshTasksIndirectEXT(cmd, shadowIndirect.buffer(), 0L, 1, 12);
        if (lodReady && lodClusterCount > 0 && pipeLodShadow != 0L) {
            // Fernfeld: Sections, die das Nahfeld (noch) nicht hat – z. B. die Oberflaeche ueber einer Hoehle
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeLodShadow);
            push(arena, cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, layoutLod,
                    W.sb(0, lodMeshlets), W.sb(1, lodQuads), W.sb(2, lodNearMask), W.ub(3, shadowUniforms[slot]),
                    W.si(4, atlasView), W.sm(5, atlasSampler), W.sb(6, lodVisShadow), W.sb(7, lodTexTable), W.sb(8, lodDrawBiome));
            ByteBuffer pc = arena.malloc(16);
            pc.putInt(0, lodMaskCamX).putInt(4, lodMaskCamZ).putInt(8, lodMaskData != null ? 1 : 0).putInt(12, lodMaskMinSY);
            VK10.vkCmdPushConstants(cmd, layoutLod, EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT, 0, pc);
            EXTMeshShader.vkCmdDrawMeshTasksIndirectEXT(cmd, lodShadowIndirect.buffer(), 0L, 1, 12);
        }
        recordEntityShadows(arena, cmd, slot, entityBatches);
        KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
        barrier(arena, cmd, VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT);
    }

    /** first = Phase 1 (Tiefe neu), sonst Phase 2 (laedt G-Buffer + Tiefe und ergaenzt). */
    private void recordGbuffer(Arena arena, VkCommandBuffer cmd, int slot, long atlasView, boolean first, Buf list, Buf indirect,
                               Buf lodList, Buf lodIndirect) {
        if (!first) {
            // Phase-1-Attachments (und die Hi-Z-Lesezugriffe davor) -> Phase 2 schreibt weiter
            int attWrite = VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
            int attRw = attWrite | VK10.VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT;
            int attStages = VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT
                    | VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
            barrier(arena, cmd, attStages | VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, attStages, attWrite, attRw);
        }
        VkClearValue.Buffer clearValues = VkClearValue.calloc(3, arena.stack());
        clearValues.get(2).depthStencil().depth(0.0f).stencil(0); // Reverse-Z
        // Phase 1: Farbziele DONT_CARE (jeder Pixel mit Tiefe > 0 wird geschrieben, der Rest nie gelesen)
        int colorLoad = first ? VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE : VK10.VK_ATTACHMENT_LOAD_OP_LOAD;
        VkRenderingAttachmentInfo.Buffer colors = VkRenderingAttachmentInfo.calloc(2, arena.stack());
        colors.get(0).sType$Default().imageView(gAlbedo.view()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .loadOp(colorLoad).storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE);
        colors.get(1).sType$Default().imageView(gNormal.view()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .loadOp(colorLoad).storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE);
        // Tiefe bleibt erhalten: Hi-Z (Phase 2) + Kopie ins Main-Depth
        VkRenderingAttachmentInfo depthAtt = VkRenderingAttachmentInfo.calloc(arena.stack()).sType$Default()
                .imageView(depth.view()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .loadOp(first ? VK10.VK_ATTACHMENT_LOAD_OP_CLEAR : VK10.VK_ATTACHMENT_LOAD_OP_LOAD)
                .storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE).clearValue(clearValues.get(2));
        VkRenderingInfo ri = VkRenderingInfo.calloc(arena.stack()).sType$Default();
        ri.renderArea().offset().set(0, 0);
        ri.renderArea().extent().set(width, height);
        ri.layerCount(1).pColorAttachments(colors).pDepthAttachment(depthAtt);
        KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, ri);
        setViewport(arena, cmd, width, height);
        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeGbuffer);
        push(arena, cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, layoutTerrain,
                W.sb(0, meshlets), W.sb(1, verts), W.sb(2, tris), W.ub(3, uniforms[slot]),
                W.si(4, atlasView), W.sm(5, atlasSampler), W.sb(6, list));
        // Genau ein Command: x = Anzahl sichtbarer Meshlets (vom Cull geschrieben)
        EXTMeshShader.vkCmdDrawMeshTasksIndirectEXT(cmd, indirect.buffer(), 0L, 1, 12);
        if (lodReady && lodClusterCount > 0) {
            // Fernfeld in denselben G-Buffer (gleiche Tiefe -> Hi-Z und Deferred wie das Nahfeld)
            updateLodDrawTables();
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeLod);
            push(arena, cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, layoutLod,
                    W.sb(0, lodMeshlets), W.sb(1, lodQuads), W.sb(2, lodNearMask), W.ub(3, uniforms[slot]),
                    W.si(4, atlasView), W.sm(5, atlasSampler), W.sb(6, lodList), W.sb(7, lodTexTable), W.sb(8, lodDrawBiome));
            ByteBuffer pc = arena.malloc(16);
            pc.putInt(0, lodMaskCamX).putInt(4, lodMaskCamZ).putInt(8, lodMaskData != null ? 1 : 0).putInt(12, lodMaskMinSY);
            VK10.vkCmdPushConstants(cmd, layoutLod, EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT, 0, pc);
            EXTMeshShader.vkCmdDrawMeshTasksIndirectEXT(cmd, lodIndirect.buffer(), 0L, 1, 12);
        }
        KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
        barrier(arena, cmd,
                VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT
                        | VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT
                        | VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_TRANSFER_READ_BIT);
    }

    private void recordDeferred(Arena arena, VkCommandBuffer cmd, int slot, long atlasView) {
        if (rt != null) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeDeferredRt);
            push(arena, cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layoutDeferredRt,
                    W.si(0, gAlbedo.view()), W.si(1, gNormal.view()), W.si(2, depth.view()),
                    W.si(3, shadowMap.view()), W.sm(4, shadowSampler), W.si(5, background.view()),
                    W.st(6, hdr.view()), W.ub(7, uniforms[slot]), W.sm(8, linearSampler),
                    W.as(9, rt.tlas()), W.raw(10, rt.lightBuffer(slot), (long) RtAccel.MAX_LIGHTS * RtAccel.LIGHT_BYTES),
                    W.sb(11, cellCount), W.sb(12, cellLights), W.sb(13, verts),
                    W.si(14, atlasView), W.sm(15, atlasSampler));
        } else {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeDeferred);
            push(arena, cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layoutDeferred,
                    W.si(0, gAlbedo.view()), W.si(1, gNormal.view()), W.si(2, depth.view()),
                    W.si(3, shadowMap.view()), W.sm(4, shadowSampler), W.si(5, background.view()),
                    W.st(6, hdr.view()), W.ub(7, uniforms[slot]), W.sm(8, linearSampler));
        }
        VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
        barrier(arena, cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT);
    }

    private void recordBloom(Arena arena, VkCommandBuffer cmd) {
        int mips = bloom.mips();
        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeBloomDown);
        for (int i = 0; i < mips; i++) {
            int w = Math.max(bloom.w() >> i, 1);
            int h = Math.max(bloom.h() >> i, 1);
            push(arena, cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layoutBloom,
                    W.si(0, i == 0 ? hdr.view() : bloom.view()), W.st(1, bloomMipViews[i]), W.sm(2, linearSampler));
            bloomPush(arena, cmd, w, h, Math.max(i - 1, 0), i == 0);
            VK10.vkCmdDispatch(cmd, (w + 7) / 8, (h + 7) / 8, 1);
            barrier(arena, cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
        }
        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeBloomUp);
        for (int i = mips - 2; i >= 0; i--) {
            int w = Math.max(bloom.w() >> i, 1);
            int h = Math.max(bloom.h() >> i, 1);
            push(arena, cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layoutBloom,
                    W.si(0, bloom.view()), W.st(1, bloomMipViews[i]), W.sm(2, linearSampler));
            bloomPush(arena, cmd, w, h, i + 1, false);
            VK10.vkCmdDispatch(cmd, (w + 7) / 8, (h + 7) / 8, 1);
            barrier(arena, cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
        }
    }

    private void bloomPush(Arena arena, VkCommandBuffer cmd, int w, int h, int srcMip, boolean first) {
        ByteBuffer pc = arena.malloc(16);
        pc.putInt(0, w).putInt(4, h).putInt(8, srcMip).putInt(12, first ? 1 : 0);
        VK10.vkCmdPushConstants(cmd, layoutBloom, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
    }

    private void recordFinal(Arena arena, VkCommandBuffer cmd, int slot) {
        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeFinal);
        push(arena, cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layoutFinal,
                W.si(0, hdr.view()), W.si(1, bloom.view()), W.st(2, ldr.view()), W.ub(3, uniforms[slot]),
                W.sm(4, linearSampler));
        VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
        barrier(arena, cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_TRANSFER_READ_BIT);
    }

    // ---------- Push-Deskriptoren (Mojang-Muster: keine Pools/Sets/Updates) ----------

    /** kind: 0=Buffer, 1=Sampled, 2=Storage, 3=Sampler, 4=Acceleration Structure. */
    private record W(int binding, int type, long handle, long size, int kind) {
        static W sb(int b, Buf buf) {
            return new W(b, SB, buf.buffer(), buf.size(), 0);
        }

        static W raw(int b, long buffer, long size) {
            return new W(b, SB, buffer, size, 0);
        }

        static W as(int b, long accel) {
            return new W(b, KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, accel, 0L, 4);
        }

        static W ub(int b, Buf buf) {
            return new W(b, UB, buf.buffer(), buf.size(), 0);
        }

        static W si(int b, long view) {
            return new W(b, SI, view, 0L, 1);
        }

        static W st(int b, long view) {
            return new W(b, ST, view, 0L, 2);
        }

        static W sm(int b, long sampler) {
            return new W(b, SM, sampler, 0L, 3);
        }
    }

    private void push(Arena arena, VkCommandBuffer cmd, int bindPoint, long pipeLayout, W... ws) {
        VkWriteDescriptorSet.Buffer wb = VkWriteDescriptorSet.calloc(ws.length, arena.stack());
        for (int i = 0; i < ws.length; i++) {
            W spec = ws[i];
            VkWriteDescriptorSet w = wb.get(i);
            w.sType$Default().dstSet(0L).dstBinding(spec.binding()).descriptorCount(1).descriptorType(spec.type());
            if (spec.kind() == 0) {
                w.pBufferInfo(VkDescriptorBufferInfo.calloc(1, arena.stack())
                        .buffer(spec.handle()).offset(0L).range(spec.size()));
            } else if (spec.kind() == 4) {
                w.pNext(VkWriteDescriptorSetAccelerationStructureKHR.calloc(arena.stack()).sType$Default()
                        .pAccelerationStructures(arena.longs(spec.handle())).address());
            } else if (spec.kind() == 3) {
                w.pImageInfo(VkDescriptorImageInfo.calloc(1, arena.stack())
                        .sampler(spec.handle()).imageView(0L).imageLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED));
            } else {
                w.pImageInfo(VkDescriptorImageInfo.calloc(1, arena.stack())
                        .sampler(0L).imageView(spec.handle()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL));
            }
        }
        KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cmd, bindPoint, pipeLayout, 0, wb);
    }

    // ---------- Transfers + Barrieren ----------

    private static void blit(Arena arena, VkCommandBuffer cmd, long src, long dst, int w, int h) {
        VkImageBlit.Buffer region = VkImageBlit.calloc(1, arena.stack());
        region.get(0).srcSubresource().set(VK10.VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
        region.get(0).dstSubresource().set(VK10.VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
        region.get(0).srcOffsets(0).set(0, 0, 0);
        region.get(0).srcOffsets(1).set(w, h, 1);
        region.get(0).dstOffsets(0).set(0, 0, 0);
        region.get(0).dstOffsets(1).set(w, h, 1);
        VK10.vkCmdBlitImage(cmd, src, VK10.VK_IMAGE_LAYOUT_GENERAL, dst, VK10.VK_IMAGE_LAYOUT_GENERAL,
                region, VK10.VK_FILTER_NEAREST);
    }

    private void copyDepth(Arena arena, VkCommandBuffer cmd, long src, long dst) {
        VkImageCopy.Buffer region = VkImageCopy.calloc(1, arena.stack());
        region.get(0).srcSubresource().set(VK10.VK_IMAGE_ASPECT_DEPTH_BIT, 0, 0, 1);
        region.get(0).dstSubresource().set(VK10.VK_IMAGE_ASPECT_DEPTH_BIT, 0, 0, 1);
        region.get(0).extent().set(width, height, 1);
        VK10.vkCmdCopyImage(cmd, src, VK10.VK_IMAGE_LAYOUT_GENERAL, dst, VK10.VK_IMAGE_LAYOUT_GENERAL, region);
    }

    private static void barrier(Arena arena, VkCommandBuffer cmd, int srcStage, int dstStage, int srcAccess, int dstAccess) {
        VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, arena.stack());
        b.get(0).sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess);
        VK10.vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, b, null, null);
    }

    private static void fullBarrier(Arena arena, VkCommandBuffer cmd) {
        int rw = VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT;
        barrier(arena, cmd, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, rw, rw);
    }

    private static void toGeneral(Arena arena, VkCommandBuffer cmd, long img, int mips, int aspect) {
        VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, arena.stack());
        b.get(0).sType$Default().oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .image(img).srcAccessMask(0)
                .dstAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT);
        b.get(0).subresourceRange().aspectMask(aspect).baseMipLevel(0).levelCount(mips).baseArrayLayer(0).layerCount(1);
        VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                0, null, null, b);
    }

    private static void check(int res, String what) {
        if (res != VK10.VK_SUCCESS) throw new IllegalStateException(what + " -> VkResult " + res);
    }

    // ---------- Debug-Snapshot (PNG in run/) ----------

    /** Fordert einen PNG-Snapshot an (0=Endbild, 1=Albedo, 2=Normale/Licht). */
    public void requestSnapshot(int source) {
        snapshotSource = source;
    }

    /** Eigene Submission auf Mojangs Queue (Render-Thread), nach Abschluss des letzten Frames. RGBA8-Quellen. */
    private void writeSnapshot(int source) {
        if (gAlbedo == null || !imagesInitialized) return;
        Img src = switch (source) {
            case 1 -> gAlbedo;
            case 2 -> gNormal;
            default -> ldr;
        };
        String name = switch (source) {
            case 1 -> "vulkanfish-albedo.png";
            case 2 -> "vulkanfish-normallight.png";
            default -> "vulkanfish-frame.png";
        };
        waitAll();
        long bytes = (long) width * height * 4L;
        Buf buf = null;
        long fence = 0L;
        VkCommandBuffer cmd = null;
        try (Arena arena = new Arena()) {
            buf = makeBuffer(arena, bytes, VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            VkCommandBufferAllocateInfo ai = VkCommandBufferAllocateInfo.calloc(arena.stack()).sType$Default()
                    .commandPool(pool).level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            PointerBuffer pb = arena.mallocPointer(1);
            check(VK10.vkAllocateCommandBuffers(dev, ai, pb), "snapCmd");
            cmd = new VkCommandBuffer(pb.get(0), dev);
            check(VK10.vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(arena.stack()).sType$Default()
                    .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "snapBegin");
            fullBarrier(arena, cmd);
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, arena.stack());
            region.get(0).imageSubresource().set(VK10.VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
            region.get(0).imageExtent().set(width, height, 1);
            VK10.vkCmdCopyImageToBuffer(cmd, src.image(), VK10.VK_IMAGE_LAYOUT_GENERAL, buf.buffer(), region);
            // Transfer-Writes fuer den Host sichtbar machen (Fence allein reicht laut Spec nicht)
            barrier(arena, cmd, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_HOST_BIT,
                    VK10.VK_ACCESS_TRANSFER_WRITE_BIT, VK10.VK_ACCESS_HOST_READ_BIT);
            check(VK10.vkEndCommandBuffer(cmd), "snapEnd");
            LongBuffer pf = arena.mallocLong(1);
            check(VK10.vkCreateFence(dev, VkFenceCreateInfo.calloc(arena.stack()).sType$Default(), null, pf), "snapFence");
            fence = pf.get(0);
            VkSubmitInfo.Buffer si = VkSubmitInfo.calloc(1, arena.stack()).sType$Default()
                    .pCommandBuffers(arena.pointers(cmd.address()));
            check(VK10.vkQueueSubmit(gfxQ, si, fence), "snapSubmit");
            check(VK10.vkWaitForFences(dev, arena.longs(fence), true, 5_000_000_000L), "snapWait");

            ByteBuffer bb = MemoryUtil.memByteBuffer(buf.mapped(), (int) bytes);
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(width, height,
                    java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int o = (y * width + x) * 4;
                    int r = bb.get(o) & 0xFF, g = bb.get(o + 1) & 0xFF, b = bb.get(o + 2) & 0xFF;
                    // Vulkan-Zeile 0 = unten (GL-Konvention wie Mojangs Main-Target) -> fuer PNG spiegeln
                    img.setRGB(x, height - 1 - y, (r << 16) | (g << 8) | b);
                }
            }
            java.io.File out = new java.io.File(name).getAbsoluteFile();
            javax.imageio.ImageIO.write(img, "png", out);
            LOG.info("[vulkanfish] Snapshot: {}", out.getAbsolutePath());
        } catch (Throwable t) {
            LOG.warn("[vulkanfish] Snapshot fehlgeschlagen", t);
        } finally {
            if (fence != 0L) VK10.vkDestroyFence(dev, fence, null);
            if (cmd != null) VK10.vkFreeCommandBuffers(dev, pool, cmd);
            if (buf != null) destroyBuffer(buf);
        }
    }

    // ---------- Shutdown ----------

    private void destroyBuffer(Buf b) {
        Vma.vmaDestroyBuffer(vma, b.buffer(), b.allocation()); // Mapping gehoert VMA
        buffers.remove(b);
    }

    /** Vor Mojangs Device-Zerstoerung: GPU idle, dann alle eigenen Objekte freigeben. */
    public void destroy() {
        if (dev == null) return;
        ready = false;
        VK10.vkDeviceWaitIdle(dev);
        if (rt != null) rt.destroy();
        rt = null;
        destroyImages();
        destroyImg(shadowMap);
        shadowMap = null;
        for (Buf b : new ArrayList<>(buffers)) destroyBuffer(b);
        for (long p : pipelines) VK10.vkDestroyPipeline(dev, p, null);
        for (long p : pipelineLayouts) VK10.vkDestroyPipelineLayout(dev, p, null);
        for (long s : setLayouts) VK10.vkDestroyDescriptorSetLayout(dev, s, null);
        for (long m : shaderModules) VK10.vkDestroyShaderModule(dev, m, null);
        pipelines.clear();
        pipelineLayouts.clear();
        setLayouts.clear();
        shaderModules.clear();
        if (pool != 0L) VK10.vkDestroyCommandPool(dev, pool, null); // gibt die Command-Buffer mit frei

        if (timeline != 0L) VK10.vkDestroySemaphore(dev, timeline, null);
        if (queryPool != 0L) VK10.vkDestroyQueryPool(dev, queryPool, null);
        if (lgQueryPool != 0L) VK10.vkDestroyQueryPool(dev, lgQueryPool, null);
        lgQueryPool = 0L;
        for (long s : new long[]{linearSampler, atlasSampler, shadowSampler}) {
            if (s != 0L) VK10.vkDestroySampler(dev, s, null);
        }
        pool = timeline = queryPool = linearSampler = atlasSampler = shadowSampler = 0L;
        LOG.info("[vulkanfish] Native Ressourcen freigegeben");
        dev = null;
    }
}
