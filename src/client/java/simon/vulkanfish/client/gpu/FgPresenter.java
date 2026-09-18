package simon.vulkanfish.client.gpu;

import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import it.unimi.dsi.fastutil.longs.LongList;
import java.nio.LongBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandBufferSubmitInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreSubmitInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import simon.vulkanfish.client.mixin.VulkanGpuSurfaceAccessor;

/**
 * DLSS Frame Generation (DLSS 4, bis 6x) fuer Mojangs Swapchain.
 *
 * <p>NGX erzeugt die Zwischenbilder, praesentieren muss die Anwendung – gleichmaessig verteilt und
 * laut NVIDIAs Guide asynchron zum Render-Thread. Bei aktiver FG nimmt Mojangs Oberflaeche deshalb
 * weder Swapchain-Bilder noch praesentiert sie (VulkanGpuSurfaceMixin):
 * <ol>
 *   <li>Render-Thread, bei Mojangs Blit (nach dem GUI): NGX schreibt die N-1 Zwischenbilder in
 *       einen Ring, das echte Bild wird daneben kopiert; beides in Mojangs Submission, danach ein
 *       Timeline-Wert.</li>
 *   <li>Present-Thread: wartet (GPU-seitig) auf diesen Wert, holt je Bild ein Swapchain-Bild,
 *       kopiert (vertikal gespiegelt wie Mojangs Blit) und praesentiert – Zwischenbilder zuerst,
 *       dann das echte, in gleichen Abstaenden (bei FIFO/VSync taktet die Anzeige selbst).</li>
 * </ol>
 * Alle Queue-Zugriffe (Mojangs Submits, Present, WaitIdle) laufen unter {@link #QUEUE_LOCK};
 * Swapchain-Neuanlage unter {@link #swapLock}.
 */
public final class FgPresenter {
    private static final Logger LOG = LoggerFactory.getLogger("vulkanfish");
    /** Externe Synchronisation der Grafik-Queue (Mojang + Present-Thread). */
    public static final ReentrantLock QUEUE_LOCK = new ReentrantLock();
    private static volatile FgPresenter instance;
    private static final int SLOTS = 3;

    private final NativePassRunner runner;
    private final NgxBridge ngx;
    private final VkDevice dev;
    private final VkQueue queue;
    private final boolean ownQueue; // eigene Present-Queue (sonst Mojangs, unter QUEUE_LOCK)
    private final long queryPool;   // GPU-Zeitstempel je Present-Kopie (Takt, wie ihn die Anzeige sieht)
    private final float tsPeriodNs;
    private long lastGpuTs;
    private double gpuSum, gpuSq;
    private int gpuN;
    private final int maxImages; // Zwischenbilder + echtes Bild
    private final ReentrantLock swapLock = new ReentrantLock();

    // ---- Render-Thread ----
    private final long renderPool;
    private final VkCommandBuffer[] renderCmds = new VkCommandBuffer[SLOTS];
    private final long timeline;
    private long timelineValue;
    private final long renderQueries;     // je Slot: Frame-Start, NGX-Start, NGX-Ende
    private final VkCommandBuffer[] startCmds = new VkCommandBuffer[SLOTS];
    private boolean slotPrepared;
    private double frameGpuMs;            // GPU-Zeit eines Frames ohne NGX (gleitend)
    private final boolean[] renderQueryUsed = new boolean[SLOTS];
    private final int[] renderQueryGen = new int[SLOTS];
    private double fgMsPerImage = 0.0;    // GPU-Kosten je erzeugtem Bild (gleitend)
    private int pendingChoice, pendingCount;
    private final NativePassRunner.Img[][] ring = new NativePassRunner.Img[SLOTS][];
    private int ringW, ringH;
    private boolean ringNeedsInit;
    private int slot;
    private boolean frameActive;          // diesen Frame: FG-Modus (in acquire entschieden)
    private GpuTextureView blitView;      // Mojangs Blit-Quelle dieses Frames (finales Bild inkl. GUI)
    private int packetImages;
    private boolean needReset = true;
    private long lastPacketNs;
    private double frameIntervalNs = 16_666_667.0;

    // ---- Present-Thread ----
    private record Packet(int slot, int images, long timelineValue, boolean fifo, VulkanGpuSurfaceAccessor surface) {}
    private final ArrayBlockingQueue<Packet> packets = new ArrayBlockingQueue<>(1);
    private final java.util.concurrent.atomic.AtomicLongArray slotDoneValue = new java.util.concurrent.atomic.AtomicLongArray(SLOTS); // Timeline-Wert, ab dem der Slot frei ist
    private final Thread presentThread;
    private volatile boolean running = true;
    private long presentPool;
    private final VkCommandBuffer[] presentCmds = new VkCommandBuffer[16];
    private final long[] presentFences = new long[16];
    private final long[] acquireSems = new long[16];
    private long[] imageDoneSems = new long[0];
    private long imageDoneSwapchain;
    private int presentCursor;
    private final long presentTimeline;       // Present-Thread signalisiert "Slot gelesen"
    private long presentTimelineValue;
    private volatile int presentedFrames, droppedFrames;
    // Angezeigte Bilder je Sekunde (F3), vom Present-Thread gezaehlt
    private long fpsWindowNs;
    private int fpsCount;
    private volatile int displayedFps;
    private volatile long lastDisplayNs;
    // Takt-Statistik (Present-Thread): Abstaende aufeinanderfolgender Presents
    private long lastPresentNs;
    private double pacingSum, pacingSq;
    private int pacingN;
    /** Selbsttest: so viele folgende Pakete als PNG sichern (run/fg_<n>_<k>.png, k in Anzeigereihenfolge). */
    public static volatile int dumpPackets;
    private int dumpPending = -1, dumpImages, dumpSeq;

    private FgPresenter(NativePassRunner runner, NgxBridge ngx) {
        this.runner = runner;
        this.ngx = ngx;
        this.dev = runner.device();
        int[] pq = NgxBridge.presentQueue;
        if (pq != null && pq[0] == runner.graphicsQueueFamily()) {
            try (Arena a = new Arena()) {
                PointerBuffer qp = a.mallocPointer(1);
                VK10.vkGetDeviceQueue(dev, pq[0], pq[1], qp);
                this.queue = new VkQueue(qp.get(0), dev);
            }
            this.ownQueue = true;
        } else {
            this.queue = runner.graphicsQueue();
            this.ownQueue = false;
        }
        this.maxImages = Math.min(6, ngx.multiFrameMax() + 1);
        try (Arena a = new Arena()) {
            renderPool = pool(a);
            LongBuffer rq = a.mallocLong(1);
            NativePassRunner.check(VK10.vkCreateQueryPool(dev, org.lwjgl.vulkan.VkQueryPoolCreateInfo.calloc(a.stack()).sType$Default()
                    .queryType(VK10.VK_QUERY_TYPE_TIMESTAMP).queryCount(SLOTS * 3), null, rq), "fgRenderQueries");
            renderQueries = rq.get(0);
            for (int i = 0; i < SLOTS; i++) renderCmds[i] = cmd(a, renderPool);
            for (int i = 0; i < SLOTS; i++) startCmds[i] = cmd(a, renderPool);
            timeline = timelineSemaphore(a);
            presentTimeline = timelineSemaphore(a);
            presentPool = pool(a);
            LongBuffer qp = a.mallocLong(1);
            NativePassRunner.check(VK10.vkCreateQueryPool(dev, org.lwjgl.vulkan.VkQueryPoolCreateInfo.calloc(a.stack()).sType$Default()
                    .queryType(VK10.VK_QUERY_TYPE_TIMESTAMP).queryCount(presentCmds.length), null, qp), "fgQueryPool");
            queryPool = qp.get(0);
            var props = org.lwjgl.vulkan.VkPhysicalDeviceProperties.malloc(a.stack());
            VK10.vkGetPhysicalDeviceProperties(dev.getPhysicalDevice(), props);
            tsPeriodNs = props.limits().timestampPeriod();
            for (int i = 0; i < presentCmds.length; i++) {
                presentCmds[i] = cmd(a, presentPool);
                LongBuffer f = a.mallocLong(1);
                NativePassRunner.check(VK10.vkCreateFence(dev, VkFenceCreateInfo.calloc(a.stack()).sType$Default()
                        .flags(VK10.VK_FENCE_CREATE_SIGNALED_BIT), null, f), "fgFence");
                presentFences[i] = f.get(0);
                acquireSems[i] = binarySemaphore(a);
            }
        }
        presentThread = new Thread(this::presentLoop, "vulkanfish-fg-present");
        presentThread.setDaemon(true);
        presentThread.setPriority(Thread.MAX_PRIORITY);
        presentThread.start();
        LOG.info("[vulkanfish] DLSS Frame Generation bereit (bis {}x, eigener Present-Thread, {})", maxImages,
                ownQueue ? "eigene Queue" : "Mojangs Queue");
    }

    /** Vom NativePassRunner nach dem NGX-Init (nur mit Frame-Generation-Unterstuetzung). */
    static void create(NativePassRunner runner, NgxBridge ngx) {
        if (instance != null || !ngx.has(NgxBridge.FEATURE_FRAMEGEN)) return;
        try {
            instance = new FgPresenter(runner, ngx);
        } catch (Throwable t) {
            LOG.warn("[vulkanfish] DLSS Frame Generation nicht verfuegbar ({})", t.toString());
        }
    }

    public static FgPresenter instance() {
        return instance;
    }

    private boolean fifoMode;        // VSync: die Anzeige taktet die Presents selbst
    private long blockedNs;          // Warten auf den Present-Thread in diesem Frame (zaehlt nicht zum Render-Takt)
    private int autoImages = 2;
    private long refreshCheckNs;
    private int refreshHz;

    /**
     * Mit VSync (FIFO) taktet die Anzeige: mehr Bilder als ihre Frequenz hergibt, wuerden nur die
     * echte Bildrate druecken (6x bei 60 Hz = 10 echte FPS). Dann so viele Bilder, dass
     * echte FPS x Faktor ~ Bildwiederholrate (wie NVIDIAs dynamische MFG), hoechstens die Einstellung.
     */
    private int effectiveImages() {
        int wanted = wantedImages();
        var cfg = net.minecraft.client.Minecraft.getInstance().windowSurface().currentConfiguration();
        boolean fifo = cfg.isPresent() && (cfg.get().presentMode() == com.mojang.renderpearl.api.device.GpuSurface.PresentMode.FIFO
                || cfg.get().presentMode() == com.mojang.renderpearl.api.device.GpuSurface.PresentMode.FIFO_RELAXED);
        fifoMode = fifo;
        if (!fifo || wanted <= 1) return wanted;
        long now = System.nanoTime();
        if (refreshHz == 0 || now - refreshCheckNs > 2_000_000_000L) {
            refreshCheckNs = now;
            // 26.3: GLFW -> SDL, kein GLFW mehr im Client-Classpath; Vanillas VideoMode nutzen
            float rr = 60f;
            try {
                rr = net.minecraft.client.Minecraft.getInstance().getWindow().getActiveVideoMode().getRefreshRate();
            } catch (Throwable ignored) {
            }
            refreshHz = rr > 0 ? Math.round(rr) : 60;
        }
        // Dauer eines echten Bildes bei k Bildern: CPU-Arbeit oder GPU (Level + k-1 erzeugte Bilder)
        double cpuMs = frameIntervalNs / 1e6, gpuMs = frameGpuMs > 0.0 ? frameGpuMs : runner.gpuSpanMs();
        double perGen = fgMsPerImage > 0.0 ? fgMsPerImage : 1.0;
        int choice = wanted;
        for (int k = 1; k <= wanted; k++) {
            double periodMs = Math.max(cpuMs, gpuMs + perGen * (k - 1));
            if (k * 1000.0 / periodMs >= refreshHz * 0.95) { // kleinster Faktor, der die Anzeige fuellt
                choice = k;
                break;
            }
        }
        // Hysterese: erst nach 30 Frames gleicher Entscheidung wechseln
        if (choice == autoImages) {
            pendingCount = 0;
        } else if (choice == pendingChoice && ++pendingCount >= 30) {
            autoImages = choice;
            pendingCount = 0;
        } else if (choice != pendingChoice) {
            pendingChoice = choice;
            pendingCount = 1;
        }
        autoImages = Math.max(1, Math.min(wanted, autoImages));
        return autoImages;
    }

    /** Gewuenschte Bilder je gerendertem Frame (1 = aus). */
    private int wantedImages() {
        int m = simon.vulkanfish.client.VulkanfishSettings.frameGeneration();
        String prop = System.getProperty("vulkanfish.frameGen");
        if (prop != null) m = Integer.parseInt(prop.trim());
        return Math.max(1, Math.min(maxImages, m));
    }

    // =====================================================================
    // Hooks aus VulkanGpuSurfaceMixin (Render-Thread)
    // =====================================================================

    /** Vor Mojangs acquire: true = FG-Modus, Mojang holt kein Swapchain-Bild. */
    public boolean onAcquire(VulkanGpuSurfaceAccessor surface) {
        boolean want = running && wantedImages() > 1 && runner.isReady();
        if (frameActive && !want) drain(); // zurueck zu Mojang: erst alles von uns Praesentierte abwarten
        frameActive = want;
        if (want && ring[0] != null) prepareSlot();
        return want;
    }

    /**
     * Frame-Beginn: naechsten Ring-Slot frei warten (Rueckstau vom Present-Thread hier statt mitten im
     * Frame – die Eingaben werden danach gelesen, das haelt die Latenz klein) und einen GPU-
     * Zeitstempel an den Anfang von Mojangs Submission setzen.
     */
    private void prepareSlot() {
        slot = (slot + 1) % SLOTS;
        long tw = System.nanoTime();
        waitSlotFree(slot);
        blockedNs += System.nanoTime() - tw;
        readRenderQueries(slot);
        try (Arena a = new Arena()) {
            VkCommandBuffer cmd = startCmds[slot];
            NativePassRunner.check(VK10.vkResetCommandBuffer(cmd, 0), "fgStartReset");
            NativePassRunner.check(VK10.vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(a.stack()).sType$Default()
                    .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "fgStartBegin");
            VK10.vkCmdResetQueryPool(cmd, renderQueries, slot * 3, 3);
            VK10.vkCmdWriteTimestamp(cmd, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, renderQueries, slot * 3);
            NativePassRunner.check(VK10.vkEndCommandBuffer(cmd), "fgStartEnd");
            runner.encoder().execute(cmd);
        }
        slotPrepared = true;
    }

    public boolean frameActive() {
        return frameActive;
    }

    /**
     * Statt Mojangs Blit: Zwischenbilder (NGX) + Kopie des echten Bildes in den Ring, per Mojangs
     * Encoder (landet in dessen naechstem Submit).
     */
    public void onBlit(CommandEncoderBackend encoderBackend, GpuTextureView view) {
        if (!(view.texture() instanceof VulkanGpuTexture tex) || !(encoderBackend instanceof VulkanCommandEncoder encoder)) {
            packetImages = 0;
            return;
        }
        int w = view.getWidth(0), h = view.getHeight(0);
        if (dumpPending >= 0) {
            dump(dumpPending, dumpImages);
            dumpPending = -1;
        }
        if (ensureRing(w, h) || !slotPrepared) {
            slot = (slot + 1) % SLOTS;
            waitSlotFree(slot);
            renderQueryUsed[slot] = false; // ohne Start-Zeitstempel
        }
        boolean timed = slotPrepared;
        slotPrepared = false;
        NativePassRunner.FgInputs in = runner.fgInputs(w, h);
        int images = in != null ? effectiveImages() : 1; // ohne Level-Bild (Menue) nur das echte Bild
        boolean reset = needReset || in == null || in.reset();
        try (Arena a = new Arena()) {
            VkCommandBuffer cmd = renderCmds[slot];
            NativePassRunner.check(VK10.vkResetCommandBuffer(cmd, 0), "fgReset");
            NativePassRunner.check(VK10.vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(a.stack()).sType$Default()
                    .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "fgBegin");
            if (ringNeedsInit) {
                for (NativePassRunner.Img[] r : ring)
                    for (NativePassRunner.Img img : r) NativePassRunner.toGeneral(a, cmd, img.image(), 1, VK10.VK_IMAGE_ASPECT_COLOR_BIT);
                ringNeedsInit = false;
            }
            NativePassRunner.fullBarrier(a, cmd); // GUI & Co. fertig
            if (timed) VK10.vkCmdWriteTimestamp(cmd, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, renderQueries, slot * 3 + 1);
            long viewHandle = ((com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView) view).vkImageView();
            int fmt = VK10.VK_FORMAT_R8G8B8A8_UNORM, bbFmt = NativePassRunner.vkFormat(tex);
            for (int i = 1; i < images; i++) {
                NativePassRunner.Img out = ring[slot][i - 1];
                int r = ngx.frameGen(cmd.address(), w, h, in.renderW(), in.renderH(), tex.vkImage(), viewHandle, bbFmt,
                        in.depthImage(), in.depthView(), VK10.VK_FORMAT_D32_SFLOAT,
                        in.motionImage(), in.motionView(), VK10.VK_FORMAT_R16G16_SFLOAT,
                        in.hudImage(), in.hudView(), fmt, out.image(), out.view(), fmt,
                        in.camera(), i, images - 1, reset && i == 1);
                if (r != 0) {
                    LOG.warn("[vulkanfish] DLSS Frame Generation fehlgeschlagen ({}: {}) – nur echte Bilder", r, ngx.lastError());
                    images = 1;
                    running = false; // weitere Frames ohne FG (onAcquire schaltet zurueck)
                    break;
                }
            }
            NativePassRunner.fullBarrier(a, cmd);
            if (timed) VK10.vkCmdWriteTimestamp(cmd, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, renderQueries, slot * 3 + 2);
            renderQueryUsed[slot] = timed;
            renderQueryGen[slot] = images - 1;
            // echtes Bild neben die Zwischenbilder (Mojangs Hauptziel wird im naechsten Frame ueberschrieben)
            VkImageCopy.Buffer region = VkImageCopy.calloc(1, a.stack());
            region.get(0).srcSubresource().set(VK10.VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
            region.get(0).dstSubresource().set(VK10.VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
            region.get(0).extent().set(w, h, 1);
            VK10.vkCmdCopyImage(cmd, tex.vkImage(), VK10.VK_IMAGE_LAYOUT_GENERAL, ring[slot][maxImages - 1].image(),
                    VK10.VK_IMAGE_LAYOUT_GENERAL, region);
            NativePassRunner.fullBarrier(a, cmd);
            NativePassRunner.check(VK10.vkEndCommandBuffer(cmd), "fgEnd");
            encoder.execute(cmd);
            encoder.signalSemaphore(timeline, ++timelineValue, 0x10000L /* ALL_COMMANDS */);
        }
        needReset = false;
        packetImages = images;
    }

    /** Statt Mojangs present: Paket an den Present-Thread (wartet, solange der noch das vorige zeigt). */
    public void onPresent(VulkanGpuSurfaceAccessor surface) {
        if (packetImages <= 0) return;
        // Takt = Arbeitszeit des Render-Threads ohne das Warten auf den Present-Thread (sonst haelt
        // sich jeder einmal erreichte langsame Takt selbst: langsam praesentieren -> langsam liefern)
        long now = System.nanoTime();
        if (lastPacketNs != 0L)
            frameIntervalNs = frameIntervalNs * 0.9 + Math.min(Math.max(now - lastPacketNs - blockedNs, 0L), 100_000_000L) * 0.1;
        blockedNs = 0L;
        Packet p = new Packet(slot, packetImages, timelineValue, fifoMode, surface);
        if (dumpPackets > 0) {
            dumpPackets--;
            dumpPending = slot;
            dumpImages = packetImages;
        }
        try {
            if (!packets.offer(p, 250, TimeUnit.MILLISECONDS)) droppedFrames++;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        lastPacketNs = System.nanoTime();
    }

    /** Mojang legt die Swapchain neu an: Present-Thread anhalten (Sperre bis afterConfigure). */
    public void beforeConfigure() {
        quiesce();
        swapLock.lock();
    }

    /**
     * Present-Thread leer laufen lassen und dessen Queue abwarten: Mojang wartet vor dem Zerstoeren
     * der Swapchain nur auf die eigene Queue, unsere Kopien/Presents duerfen dann nicht mehr laufen.
     */
    public void quiesce() {
        drain();
        swapLock.lock();
        if (!ownQueue) QUEUE_LOCK.lock();
        try {
            VK10.vkQueueWaitIdle(queue);
        } finally {
            if (!ownQueue) QUEUE_LOCK.unlock();
            swapLock.unlock();
        }
    }

    public void afterConfigure() {
        needReset = true;
        if (swapLock.isHeldByCurrentThread()) swapLock.unlock();
    }

    /** Warten, bis alle Pakete praesentiert sind. */
    private void drain() {
        long deadline = System.nanoTime() + 1_000_000_000L;
        while ((!packets.isEmpty() || busy) && System.nanoTime() < deadline) LockSupport.parkNanos(200_000L);
    }

    private volatile boolean busy;

    // =====================================================================
    // Present-Thread
    // =====================================================================

    private void presentLoop() {
        long nextPresent = 0L;
        while (running || !packets.isEmpty()) {
            Packet p;
            try {
                p = packets.poll(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (p == null) continue;
            busy = true;
            try {
                // erst takten, wenn die GPU die Bilder fertig hat – sonst warten alle Kopien auf der
                // GPU auf dasselbe Signal und erscheinen als Buendel
                waitTimeline(p.timelineValue());
                long ready = System.nanoTime();
                if (lastReadyNs != 0L) readyPeriodNs = readyPeriodNs * 0.9 + Math.min(ready - lastReadyNs, 100_000_000L) * 0.1;
                lastReadyNs = ready;
                // Ohne VSync: gleichmaessig ueber die gemessene Lieferzeit verteilen, etwas schneller als
                // geliefert wird (Rueckstau baut sich ab statt sich zu halten). Mit VSync taktet die
                // Anzeige (jedes Present wartet auf ein freies Bild); eigenes Takten wuerde sich dort
                // mit dem Warten auf den Bildwechsel zu einem langsameren Takt aufschaukeln.
                long step = p.fifo() ? 0L : (long) (readyPeriodNs * 0.95 / p.images());
                if (nextPresent < ready - step) nextPresent = ready; // zu spaet: nicht nachholen
                for (int k = 0; k < p.images(); k++) {
                    // Reihenfolge: Zwischenbilder 1..N-1, dann das echte Bild (Ring-Index maxImages-1)
                    NativePassRunner.Img img = k < p.images() - 1 ? ring[p.slot()][k] : ring[p.slot()][maxImages - 1];
                    long wait = nextPresent - System.nanoTime(); // gleichmaessige Abstaende (FIFO taktet zusaetzlich)
                    if (wait > 0) LockSupport.parkNanos(wait);
                    long t = System.nanoTime();
                    boolean ok = presentOne(p, img, k == p.images() - 1);
                    long now = System.nanoTime();
                    if (ok && lastPresentNs != 0L && now - lastPresentNs < 100_000_000L) {
                        double dt = (now - lastPresentNs) / 1e6;
                        pacingSum += dt;
                        pacingSq += dt * dt;
                        pacingN++;
                    }
                    if (ok) {
                        lastPresentNs = now;
                        fpsCount++;
                        lastDisplayNs = now;
                        if (now - fpsWindowNs >= 1_000_000_000L) {
                            displayedFps = (int) Math.round(fpsCount * 1e9 / Math.max(now - fpsWindowNs, 1L));
                            fpsCount = 0;
                            fpsWindowNs = now;
                        }
                    }
                    if (!ok) {
                        // Paket abgebrochen: Slot erst frei, wenn die schon abgeschickten Kopien durch sind
                        try (Arena a = new Arena()) {
                            VK10.vkWaitForFences(dev, a.longs(presentFences), true, 1_000_000_000L);
                        }
                        slotDoneValue.set(p.slot(), 0L);
                        break;
                    }
                    nextPresent = Math.max(t, nextPresent) + step;
                    presentedFrames++;
                }
            } catch (Throwable t) {
                LOG.warn("[vulkanfish] FG-Present fehlgeschlagen", t);
                running = false;
            } finally {
                busy = false;
            }
        }
    }

    /** Ein Ring-Bild auf die Swapchain. @return false: Swapchain veraltet (Rest des Pakets verwerfen). */
    private boolean presentOne(Packet p, NativePassRunner.Img img, boolean last) {
        swapLock.lock();
        try (Arena a = new Arena()) {
            VulkanGpuSurfaceAccessor s = p.surface();
            long swapchain = s.vulkanfish$swapchain();
            if (swapchain == 0L || s.vulkanfish$outOfDate()) return false;
            LongList images = s.vulkanfish$swapchainImages();
            if (imageDoneSwapchain != swapchain || imageDoneSems.length != images.size()) recreateImageSems(a, swapchain, images.size());
            int c = presentCursor++ % presentCmds.length;
            // Befehlspuffer/Semaphore dieses Rings erst nach dessen letztem Submit wiederverwenden
            NativePassRunner.check(VK10.vkWaitForFences(dev, presentFences[c], true, 1_000_000_000L), "fgFenceWait");
            if (queryUsed[c]) readTimestamp(a, c);
            java.nio.IntBuffer idx = a.mallocInt(1);
            int r = KHRSwapchain.vkAcquireNextImageKHR(dev, swapchain, 1_000_000_000L, acquireSems[c], 0L, idx);
            if (r == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
                s.vulkanfish$setOutOfDate(true);
                s.vulkanfish$setSuboptimal(true);
                return false;
            }
            if (r == KHRSwapchain.VK_SUBOPTIMAL_KHR) s.vulkanfish$setSuboptimal(true);
            else if (r != VK10.VK_SUCCESS) return false; // Timeout o. ae.: Bild auslassen
            int i = idx.get(0);
            long swapImage = images.getLong(i);
            int w = Math.min(s.vulkanfish$width(), img.w()), h = Math.min(s.vulkanfish$height(), img.h());
            VkCommandBuffer cmd = presentCmds[c];
            NativePassRunner.check(VK10.vkResetCommandBuffer(cmd, 0), "fgPresentReset");
            NativePassRunner.check(VK10.vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(a.stack()).sType$Default()
                    .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "fgPresentBegin");
            VK10.vkCmdResetQueryPool(cmd, queryPool, c, 1);
            layout(a, cmd, swapImage, 0L, 0L, 0x1000L /* TRANSFER */, 0x1000L /* TRANSFER_WRITE */, 0, 7);
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, a.stack());
            region.get(0).srcSubresource().set(VK10.VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
            region.get(0).dstSubresource().set(VK10.VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
            region.get(0).srcOffsets(0).set(0, 0, 0);
            region.get(0).srcOffsets(1).set(w, h, 1);
            region.get(0).dstOffsets(0).set(0, h, 0); // vertikal gespiegelt wie Mojangs Blit
            region.get(0).dstOffsets(1).set(w, 0, 1);
            VK12.vkCmdBlitImage(cmd, img.image(), VK10.VK_IMAGE_LAYOUT_GENERAL, swapImage, 7, region, VK10.VK_FILTER_NEAREST);
            VK10.vkCmdWriteTimestamp(cmd, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, queryPool, c);
            queryUsed[c] = true;
            layout(a, cmd, swapImage, 0x1000L, 0x1000L, 0x10000L /* ALL_COMMANDS */, 0L, 7, KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            NativePassRunner.check(VK10.vkEndCommandBuffer(cmd), "fgPresentEnd");

            VkSemaphoreSubmitInfo.Buffer waits = VkSemaphoreSubmitInfo.calloc(2, a.stack());
            waits.get(0).sType$Default().semaphore(acquireSems[c]).stageMask(0x1000L);
            waits.get(1).sType$Default().semaphore(timeline).value(p.timelineValue()).stageMask(0x1000L);
            VkSemaphoreSubmitInfo.Buffer signals = VkSemaphoreSubmitInfo.calloc(last ? 2 : 1, a.stack());
            signals.get(0).sType$Default().semaphore(imageDoneSems[i]).stageMask(0x10000L);
            if (last) signals.get(1).sType$Default().semaphore(presentTimeline).value(++presentTimelineValue).stageMask(0x10000L);
            VkCommandBufferSubmitInfo.Buffer cmds = VkCommandBufferSubmitInfo.calloc(1, a.stack());
            cmds.get(0).sType$Default().commandBuffer(cmd);
            VkSubmitInfo2.Buffer submit = VkSubmitInfo2.calloc(1, a.stack());
            submit.get(0).sType$Default().pWaitSemaphoreInfos(waits).pCommandBufferInfos(cmds).pSignalSemaphoreInfos(signals);
            NativePassRunner.check(VK10.vkResetFences(dev, presentFences[c]), "fgFenceReset");
            VkPresentInfoKHR present = VkPresentInfoKHR.calloc(a.stack()).sType$Default()
                    .pWaitSemaphores(a.longs(imageDoneSems[i])).swapchainCount(1).pSwapchains(a.longs(swapchain)).pImageIndices(a.ints(i));
            int pr;
            if (!ownQueue) QUEUE_LOCK.lock();
            try {
                NativePassRunner.check(KHRSynchronization2.vkQueueSubmit2KHR(queue, submit, presentFences[c]), "fgSubmit");
                pr = KHRSwapchain.vkQueuePresentKHR(queue, present);
            } finally {
                if (!ownQueue) QUEUE_LOCK.unlock();
            }
            if (last) slotDoneValue.set(p.slot(), presentTimelineValue);
            if (pr == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
                s.vulkanfish$setOutOfDate(true);
                s.vulkanfish$setSuboptimal(true);
                return false;
            }
            if (pr == KHRSwapchain.VK_SUBOPTIMAL_KHR) s.vulkanfish$setSuboptimal(true);
            return true;
        } finally {
            swapLock.unlock();
        }
    }

    private final boolean[] queryUsed = new boolean[16];

    /** Zeitstempel der vorigen Kopie mit diesem Befehlspuffer (Kopien laufen in Reihenfolge). */
    private void readTimestamp(Arena a, int c) {
        LongBuffer ts = a.mallocLong(1);
        if (VK10.vkGetQueryPoolResults(dev, queryPool, c, 1, ts, 8, VK10.VK_QUERY_RESULT_64_BIT) != VK10.VK_SUCCESS) return;
        long t = ts.get(0);
        if (lastGpuTs != 0L) {
            double dt = (t - lastGpuTs) * tsPeriodNs / 1e6;
            if (dt > 0.0 && dt < 100.0) {
                gpuSum += dt;
                gpuSq += dt * dt;
                gpuN++;
            }
        }
        lastGpuTs = t;
    }

    /** vkDeviceWaitIdle braucht alle Queues extern synchronisiert: Present-Thread (swapLock) + Mojang. */
    /** NGX-Kosten des Slots (die GPU ist nach waitSlotFree damit fertig; sonst NOT_READY). */
    private void readRenderQueries(int s) {
        if (!renderQueryUsed[s]) return;
        renderQueryUsed[s] = false;
        try (Arena a = new Arena()) {
            LongBuffer ts = a.mallocLong(3);
            if (VK10.vkGetQueryPoolResults(dev, renderQueries, s * 3, 3, ts, 8, VK10.VK_QUERY_RESULT_64_BIT) != VK10.VK_SUCCESS) return;
            double frame = Math.max(0L, ts.get(1) - ts.get(0)) * tsPeriodNs / 1e6;
            if (frame < 200.0) frameGpuMs = frameGpuMs == 0.0 ? frame : frameGpuMs * 0.9 + frame * 0.1;
            if (renderQueryGen[s] <= 0) return;
            double ms = Math.max(0L, ts.get(2) - ts.get(1)) * tsPeriodNs / 1e6 / renderQueryGen[s];
            if (ms < 50.0) fgMsPerImage = fgMsPerImage == 0.0 ? ms : fgMsPerImage * 0.9 + ms * 0.1;
        }
    }

    private long lastReadyNs;
    private double readyPeriodNs = 16_666_667.0;

    private void waitTimeline(long value) {
        try (Arena a = new Arena()) {
            org.lwjgl.vulkan.VkSemaphoreWaitInfo wi = org.lwjgl.vulkan.VkSemaphoreWaitInfo.calloc(a.stack()).sType$Default()
                    .semaphoreCount(1).pSemaphores(a.longs(timeline)).pValues(a.longs(value));
            VK12.vkWaitSemaphores(dev, wi, 1_000_000_000L);
        }
    }

    private void waitIdle() {
        swapLock.lock();
        QUEUE_LOCK.lock();
        try {
            VK10.vkDeviceWaitIdle(dev);
        } finally {
            QUEUE_LOCK.unlock();
            swapLock.unlock();
        }
    }

    private static void layout(Arena a, VkCommandBuffer cmd, long image, long srcStage, long srcAccess, long dstStage, long dstAccess,
                               int oldLayout, int newLayout) {
        VkImageMemoryBarrier2.Buffer b = VkImageMemoryBarrier2.calloc(1, a.stack()).sType$Default();
        b.srcStageMask(srcStage).srcAccessMask(srcAccess).dstStageMask(dstStage).dstAccessMask(dstAccess)
                .oldLayout(oldLayout).newLayout(newLayout).srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(image);
        b.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, VkDependencyInfo.calloc(a.stack()).sType$Default().pImageMemoryBarriers(b));
    }

    private void recreateImageSems(Arena a, long swapchain, int n) {
        // alte Semaphoren: die GPU ist mit ihnen fertig, sobald alle Present-Fences durch sind
        VK10.vkWaitForFences(dev, a.longs(presentFences), true, 1_000_000_000L);
        for (long sem : imageDoneSems) VK10.vkDestroySemaphore(dev, sem, null);
        imageDoneSems = new long[n];
        for (int i = 0; i < n; i++) imageDoneSems[i] = binarySemaphore(a);
        imageDoneSwapchain = swapchain;
    }

    // =====================================================================
    // Hilfen
    // =====================================================================

    /** Ring-Slot erst beschreiben, wenn der Present-Thread ihn fertig gelesen hat. */
    private void waitSlotFree(int s) {
        long need = slotDoneValue.get(s);
        if (need == 0L) return;
        try (Arena a = new Arena()) {
            org.lwjgl.vulkan.VkSemaphoreWaitInfo wi = org.lwjgl.vulkan.VkSemaphoreWaitInfo.calloc(a.stack()).sType$Default()
                    .semaphoreCount(1).pSemaphores(a.longs(presentTimeline)).pValues(a.longs(need));
            VK12.vkWaitSemaphores(dev, wi, 1_000_000_000L);
        }
    }

    /** @return true: Ring neu angelegt (vorbereiteter Slot ungueltig) */
    private boolean ensureRing(int w, int h) {
        if (w == ringW && h == ringH && ring[0] != null) return false;
        drain();
        waitIdle();
        destroyRing();
        try (Arena a = new Arena()) {
            int usage = VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT
                    | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            for (int s = 0; s < SLOTS; s++) {
                ring[s] = new NativePassRunner.Img[maxImages];
                for (int i = 0; i < maxImages; i++) {
                    ring[s][i] = runner.makeImage(a, w, h, 1, VK10.VK_FORMAT_R8G8B8A8_UNORM, usage, VK10.VK_IMAGE_ASPECT_COLOR_BIT);
                }
                slotDoneValue.set(s, 0L);
            }
        }
        ringNeedsInit = true; // Layout GENERAL im naechsten FG-Befehlspuffer
        ringW = w;
        ringH = h;
        needReset = true;
        return true;
    }

    private void destroyRing() {
        for (int s = 0; s < SLOTS; s++) {
            if (ring[s] == null) continue;
            for (NativePassRunner.Img img : ring[s]) runner.destroyImg(img);
            ring[s] = null;
        }
    }

    private long pool(Arena a) {
        LongBuffer p = a.mallocLong(1);
        NativePassRunner.check(VK10.vkCreateCommandPool(dev, VkCommandPoolCreateInfo.calloc(a.stack()).sType$Default()
                .flags(VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(runner.graphicsQueueFamily()), null, p), "fgPool");
        return p.get(0);
    }

    private VkCommandBuffer cmd(Arena a, long pool) {
        PointerBuffer pb = a.mallocPointer(1);
        NativePassRunner.check(VK10.vkAllocateCommandBuffers(dev, VkCommandBufferAllocateInfo.calloc(a.stack()).sType$Default()
                .commandPool(pool).level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pb), "fgCmd");
        return new VkCommandBuffer(pb.get(0), dev);
    }

    private long timelineSemaphore(Arena a) {
        VkSemaphoreTypeCreateInfo type = VkSemaphoreTypeCreateInfo.calloc(a.stack()).sType$Default()
                .semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE).initialValue(0);
        LongBuffer p = a.mallocLong(1);
        NativePassRunner.check(VK10.vkCreateSemaphore(dev, VkSemaphoreCreateInfo.calloc(a.stack()).sType$Default().pNext(type.address()), null, p), "fgTimeline");
        return p.get(0);
    }

    private long binarySemaphore(Arena a) {
        LongBuffer p = a.mallocLong(1);
        NativePassRunner.check(VK10.vkCreateSemaphore(dev, VkSemaphoreCreateInfo.calloc(a.stack()).sType$Default(), null, p), "fgSemaphore");
        return p.get(0);
    }

    /** Angezeigte FPS inkl. erzeugter Bilder; -1, wenn die FG gerade nicht praesentiert (F3). */
    public int displayedFps() {
        return frameActive && System.nanoTime() - lastDisplayNs < 500_000_000L ? displayedFps : -1;
    }

    /** Aktueller Faktor (angezeigte Bilder je gerendertem). */
    public int currentMultiplier() {
        return Math.max(1, packetImages);
    }

    public String stats() {
        double mean = pacingN > 0 ? pacingSum / pacingN : 0.0;
        double sd = pacingN > 1 ? Math.sqrt(Math.max(0.0, pacingSq / pacingN - mean * mean)) : 0.0;
        double gm = gpuN > 0 ? gpuSum / gpuN : 0.0;
        double gsd = gpuN > 1 ? Math.sqrt(Math.max(0.0, gpuSq / gpuN - gm * gm)) : 0.0;
        return String.format(java.util.Locale.ROOT, "%dx (NGX %.2f ms/Bild, Frame-GPU %.2f ms, %d Hz), %d praesentiert, %d verworfen, Render-Takt %.2f ms, fertig alle %.2f ms, Present-Abstand CPU %.2f +- %.2f ms, GPU %.2f +- %.2f ms (n=%d)",
                packetImages, fgMsPerImage, frameGpuMs, refreshHz, presentedFrames, droppedFrames, frameIntervalNs / 1e6, readyPeriodNs / 1e6, mean, sd, gm, gsd, gpuN);
    }

    public void resetStats() {
        pacingSum = pacingSq = 0.0;
        pacingN = 0;
        gpuSum = gpuSq = 0.0;
        gpuN = 0;
        presentedFrames = droppedFrames = 0;
    }

    /** Selbsttest: Bilder eines fertig praesentierten Slots als PNG (Render-Thread). */
    private void dump(int s, int images) {
        drain();
        waitIdle();
        int n = dumpSeq++;
        for (int k = 0; k < images; k++) {
            NativePassRunner.Img img = k < images - 1 ? ring[s][k] : ring[s][maxImages - 1];
            runner.writePng(img.image(), ringW, ringH, "fg_" + n + "_" + k + ".png");
        }
    }

    /** Vor Mojangs Device-Ende (NativePassRunner.destroy). */
    void destroy() {
        running = false;
        drain();
        try {
            presentThread.join(1000);
        } catch (InterruptedException ignored) {
        }
        waitIdle();
        destroyRing();
        VK10.vkDestroyCommandPool(dev, renderPool, null);
        VK10.vkDestroyCommandPool(dev, presentPool, null);
        VK10.vkDestroyQueryPool(dev, queryPool, null);
        VK10.vkDestroyQueryPool(dev, renderQueries, null);
        for (long f : presentFences) VK10.vkDestroyFence(dev, f, null);
        for (long s : acquireSems) VK10.vkDestroySemaphore(dev, s, null);
        for (long s : imageDoneSems) VK10.vkDestroySemaphore(dev, s, null);
        VK10.vkDestroySemaphore(dev, timeline, null);
        VK10.vkDestroySemaphore(dev, presentTimeline, null);
        instance = null;
    }
}
