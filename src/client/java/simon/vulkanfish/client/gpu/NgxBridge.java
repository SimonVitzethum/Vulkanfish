package simon.vulkanfish.client.gpu;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NVIDIA NGX (DLSS 4) ueber den nativen Shim src/client/native/vfngx.cpp, per FFM aufgerufen.
 * Verzeichnis mit Shim + DLSS-Snippets (beschreibbar, NGX legt dort Caches an):
 * -Dvulkanfish.ngxDir (runClient setzt es auf build/native). Fehlt es, bleibt DLSS aus.
 * Shim-Name je OS (libvfngx.so / vfngx.dll).
 */
public final class NgxBridge {
    private static final Logger LOG = LoggerFactory.getLogger("vulkanfish");
    // Geraete-Extensions, die NGX verlangt (NVSDK_NGX_VULKAN_RequiredExtensions); Buffer-Device-Address
    // ist als Kernfeature schon aktiv (Raytracing), die EXT-Variante vertruege sich damit nicht
    private static final String[] DEVICE_EXTENSIONS = {"VK_NVX_binary_import", "VK_NVX_image_view_handle", "VK_KHR_push_descriptor"};
    public static final int FEATURE_DLSS = 1, FEATURE_FRAMEGEN = 2, FEATURE_RAY_RECONSTRUCTION = 4;

    private static final Path DIR = System.getProperty("vulkanfish.ngxDir") != null ? Path.of(System.getProperty("vulkanfish.ngxDir")) : null;
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows");
    private static final String SHIM_NAME = IS_WINDOWS ? "vfngx.dll" : "libvfngx.so";

    /** Shim-Dateiname je OS (libvfngx.so / vfngx.dll). */
    public static String shimName() {
        return SHIM_NAME;
    }

    private final MethodHandle init, error, dlaa, rr, fg, shutdown, option;
    private final Arena params = Arena.ofShared();
    private final MemorySegment fgParams, rrImages, rrViews, rrFormats, rrMatrices;
    private int features = -1;
    private int multiFrameMax;

    private NgxBridge(SymbolLookup lib) {
        Linker l = Linker.nativeLinker();
        ValueLayout.OfLong J = ValueLayout.JAVA_LONG;
        ValueLayout.OfInt I = ValueLayout.JAVA_INT;
        ValueLayout.OfFloat F = ValueLayout.JAVA_FLOAT;
        init = l.downcallHandle(lib.find("vfngx_init").orElseThrow(),
                FunctionDescriptor.of(I, J, J, J, J, J, ValueLayout.ADDRESS, I, ValueLayout.ADDRESS));
        error = l.downcallHandle(lib.find("vfngx_error").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS));
        dlaa = l.downcallHandle(lib.find("vfngx_dlaa").orElseThrow(), FunctionDescriptor.of(I,
                J, I, I, I, I, J, J, I, J, J, I, J, J, I, J, J, I, F, F, I));
        rr = l.downcallHandle(lib.find("vfngx_rr").orElseThrow(), FunctionDescriptor.of(I,
                J, I, I, I, I, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, F, F, I));
        fg = l.downcallHandle(lib.find("vfngx_fg").orElseThrow(), FunctionDescriptor.of(I,
                J, I, I, I, I, J, J, I, J, J, I, J, J, I, J, J, I, J, J, I, ValueLayout.ADDRESS, I, I, I));
        shutdown = l.downcallHandle(lib.find("vfngx_shutdown").orElseThrow(), FunctionDescriptor.ofVoid());
        option = l.downcallHandle(lib.find("vfngx_option").orElseThrow(), FunctionDescriptor.ofVoid(I, I));
        fgParams = params.allocate(ValueLayout.JAVA_FLOAT, 50);
        rrImages = params.allocate(ValueLayout.JAVA_LONG, 7);
        rrViews = params.allocate(ValueLayout.JAVA_LONG, 7);
        rrFormats = params.allocate(ValueLayout.JAVA_INT, 7);
        rrMatrices = params.allocate(ValueLayout.JAVA_FLOAT, 32);
    }

    /** Liegt der Shim bereit (vor der Geraeteerstellung: sollen die NGX-Extensions dazu)? */
    public static boolean present() {
        return DIR != null && Files.isRegularFile(DIR.resolve(SHIM_NAME)) && !"false".equals(System.getProperty("vulkanfish.dlss"));
    }

    /** NGX-Extensions als optionales FeatureSet (26.3): nur mit Shim anhaengen, Vanilla gated per Support. */
    public static com.mojang.renderpearl.backend.vulkan.init.FeatureSet ngxFeatureSet() {
        if (!present()) return null;
        return new com.mojang.renderpearl.backend.vulkan.init.FeatureSet("Vulkanfish NGX",
                java.util.Set.of(DEVICE_EXTENSIONS), java.util.Set.of());
    }

    /** Eigene Queue fuer den Present-Thread der Frame Generation: {Familie, Index} oder null. */
    static volatile int[] presentQueue;

    /**
     * Beim Device-Anlegen: eine zusaetzliche Queue in Mojangs Grafik-Familie anfordern, damit die
     * FG-Kopien + Presents nicht hinter dem naechsten Frame auf Mojangs Queue warten.
     */
    public static it.unimi.dsi.fastutil.ints.Int2IntMap withPresentQueue(it.unimi.dsi.fastutil.ints.Int2IntMap map,
                                                                        com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice physicalDevice) {
        var gfx = physicalDevice.graphicsQueueFamilyAndIndex();
        if (!present() || gfx == null) return map;
        int family = gfx.leftInt();
        int used = map.getOrDefault(family, 0);
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.IntBuffer n = stack.mallocInt(1);
            org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice.vkPhysicalDevice(), n, null);
            var props = org.lwjgl.vulkan.VkQueueFamilyProperties.malloc(n.get(0), stack);
            org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice.vkPhysicalDevice(), n, props);
            if (family >= n.get(0) || props.get(family).queueCount() <= used) return map;
        }
        var out = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap(map);
        out.put(family, used + 1);
        presentQueue = new int[]{family, used};
        return out;
    }

    /** Shim laden und NGX auf Mojangs Geraet initialisieren; null, wenn nicht moeglich. */
    public static NgxBridge create(BlazeDeviceInterop blaze) {
        if (!present()) return null;
        try {
            SymbolLookup lib = SymbolLookup.libraryLookup(DIR.resolve(SHIM_NAME), Arena.global());
            NgxBridge b = new NgxBridge(lib);
            long gipa = org.lwjgl.vulkan.VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr");
            long gdpa = org.lwjgl.vulkan.VK10.vkGetInstanceProcAddr(blaze.vkInstance(), "vkGetDeviceProcAddr");
            try (Arena a = Arena.ofConfined()) {
                MemorySegment mfc = a.allocate(ValueLayout.JAVA_INT);
                int r = (int) b.init.invokeExact(blaze.vkInstance().address(), blaze.vkPhysicalDevice().address(),
                        blaze.vkDevice().address(), gipa, gdpa, a.allocateFrom(DIR.resolve("ngx").toString()),
                        Boolean.getBoolean("vulkanfish.ngxLog") ? 1 : 0, mfc);
                if (r < 0) {
                    LOG.warn("[vulkanfish] DLSS: NGX-Init fehlgeschlagen ({})", b.lastError());
                    return null;
                }
                b.features = r;
                b.multiFrameMax = mfc.get(ValueLayout.JAVA_INT, 0);
            }
            LOG.info("[vulkanfish] DLSS 4 (NGX): DLAA {}, Frame Generation {} (bis {}x), Ray Reconstruction {}",
                    b.has(FEATURE_DLSS), b.has(FEATURE_FRAMEGEN), b.multiFrameMax + 1, b.has(FEATURE_RAY_RECONSTRUCTION));
            return b;
        } catch (Throwable t) {
            LOG.warn("[vulkanfish] DLSS: Shim nicht nutzbar ({})", t.toString());
            return null;
        }
    }

    public boolean has(int feature) {
        return features >= 0 && (features & feature) != 0;
    }

    public int multiFrameMax() {
        return multiFrameMax;
    }

    public String lastError() {
        try {
            return ((MemorySegment) error.invokeExact()).reinterpret(256).getString(0);
        } catch (Throwable t) {
            return t.toString();
        }
    }

    /**
     * DLSS in den offenen Command-Buffer (alle Bilder GENERAL): Eingaben w x h, Ausgabe ow x oh
     * (gleich = DLAA, kleiner = Super Resolution). @return 0 = ok
     */
    public int dlaa(long cmd, int w, int h, int ow, int oh, long colorImg, long colorView, int colorFmt, long depthImg, long depthView, int depthFmt,
                    long mvImg, long mvView, int mvFmt, long outImg, long outView, int outFmt, float jx, float jy, boolean reset) {
        try {
            return (int) dlaa.invokeExact(cmd, w, h, ow, oh, colorImg, colorView, colorFmt, depthImg, depthView, depthFmt,
                    mvImg, mvView, mvFmt, outImg, outView, outFmt, jx, jy, reset ? 1 : 0);
        } catch (Throwable t) {
            return -100;
        }
    }

    /**
     * Ray Reconstruction in den offenen Command-Buffer (alle Bilder GENERAL). Je 7 Eintraege in der
     * Reihenfolge Farbe, Tiefe, Bewegung, diffuse Albedo, spekulare Albedo, Normale+Rauheit, Ausgabe;
     * matrices: 16 worldToView + 16 viewToClip (JOML column-major). @return 0 = ok
     */
    public int rayReconstruction(long cmd, int w, int h, int ow, int oh, long[] images, long[] views, int[] formats, float[] matrices,
                                 float jx, float jy, boolean reset) {
        try {
            MemorySegment.copy(images, 0, rrImages, ValueLayout.JAVA_LONG, 0, 7);
            MemorySegment.copy(views, 0, rrViews, ValueLayout.JAVA_LONG, 0, 7);
            MemorySegment.copy(formats, 0, rrFormats, ValueLayout.JAVA_INT, 0, 7);
            MemorySegment.copy(matrices, 0, rrMatrices, ValueLayout.JAVA_FLOAT, 0, 32);
            return (int) rr.invokeExact(cmd, w, h, ow, oh, rrImages, rrViews, rrFormats, rrMatrices, jx, jy, reset ? 1 : 0);
        } catch (Throwable t) {
            return -100;
        }
    }

    /**
     * Ein Zwischenbild der DLSS Frame Generation (frameIndex 1..frameCount) in den offenen Command-Buffer.
     * camera: 50 floats (siehe vfngx.cpp fgImpl). hudImg 0 = ohne Szene-ohne-GUI. @return 0 = ok
     */
    public int frameGen(long cmd, int w, int h, int rw, int rh, long bbImg, long bbView, int bbFmt, long depthImg, long depthView, int depthFmt,
                        long mvImg, long mvView, int mvFmt, long hudImg, long hudView, int hudFmt, long outImg, long outView, int outFmt,
                        float[] camera, int frameIndex, int frameCount, boolean reset) {
        try {
            MemorySegment.copy(camera, 0, fgParams, ValueLayout.JAVA_FLOAT, 0, 50);
            return (int) fg.invokeExact(cmd, w, h, rw, rh, bbImg, bbView, bbFmt, depthImg, depthView, depthFmt, mvImg, mvView, mvFmt,
                    hudImg, hudView, hudFmt, outImg, outView, outFmt, fgParams, frameIndex, frameCount, reset ? 1 : 0);
        } catch (Throwable t) {
            return -100;
        }
    }

    /** Shim-Option (siehe vfngx_option), wirkt beim naechsten Anlegen des Features. */
    public void option(int key, int value) {
        try {
            option.invokeExact(key, value);
        } catch (Throwable ignored) {
        }
    }

    public void shutdown() {
        try {
            shutdown.invokeExact();
        } catch (Throwable ignored) {
        }
    }

}
