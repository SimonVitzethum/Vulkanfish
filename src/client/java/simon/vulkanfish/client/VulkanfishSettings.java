package simon.vulkanfish.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import simon.vulkanfish.client.gpu.GpuDrivenConfig;

/**
 * Im Spiel aenderbare Einstellungen (Videoeinstellungen -> Vulkanfish), gespeichert in
 * config/vulkanfish-client.properties. Nicht gesetzte Werte kommen aus config/vulkanfish.json
 * (Startwerte); Aenderungen gelten sofort.
 */
public final class VulkanfishSettings {
    private static final Logger LOG = LoggerFactory.getLogger("vulkanfish");
    private static final Path FILE = Path.of("config", "vulkanfish-client.properties");

    /** Fullbright in Prozent (0 = aus). */
    private static volatile int fullbright;
    private static volatile Boolean taa, raytracing, dlss;
    private static volatile Integer lodChunks, frameGen;
    private static volatile Float lodPixelError, lodGpuMs;
    /** Bekannte Seeds je Serveradresse (Mehrspieler-LOD-Generierung). */
    private static final Map<String, Long> SEEDS = new ConcurrentHashMap<>();
    /** Im Spiel gesetzte (oder aus der Datei gelesene) Werte; nur diese werden gespeichert. */
    private static final java.util.Set<String> SET = ConcurrentHashMap.newKeySet();

    static {
        load();
    }

    private VulkanfishSettings() {
    }

    /** Fehlende Werte aus der Start-Konfiguration uebernehmen (einmal beim Renderer-Start). */
    public static void initDefaults(GpuDrivenConfig c) {
        if (taa == null) taa = c.enableTaa();
        if (raytracing == null) raytracing = c.enableRaytracing();
        if (lodChunks == null) lodChunks = c.lodDistanceChunks();
        if (lodPixelError == null) lodPixelError = c.lodPixelError();
        if (lodGpuMs == null) lodGpuMs = c.lodGpuBudgetMs();
    }

    public static int fullbrightPercent() {
        return fullbright;
    }

    /** 0..1 fuer Shader und Lightmap. */
    public static float fullbright() {
        return fullbright / 100.0f;
    }

    /** Nur fuer Selbsttests: ohne Speichern. */
    public static void setFullbrightTransient(int percent) {
        fullbright = clamp(percent, 0, 100);
    }

    public static void setFullbright(int percent) {
        fullbright = clamp(percent, 0, 100);
        save();
    }

    public static boolean taa() {
        return taa == null || taa;
    }

    public static void setTaa(boolean on) {
        taa = on;
        SET.add("taa");
        save();
    }

    /** DLSS 4 (DLAA) statt des eigenen TAA, wenn NGX verfuegbar ist. */
    public static boolean dlss() {
        return dlss == null || dlss;
    }

    public static void setDlss(boolean on) {
        dlss = on;
        SET.add("dlss");
        save();
    }

    /** DLSS Frame Generation: angezeigte Bilder je gerendertem Bild (1 = aus, 2..6). */
    public static int frameGeneration() {
        return frameGen == null ? 1 : frameGen;
    }

    public static void setFrameGeneration(int images) {
        frameGen = clamp(images, 1, 6);
        SET.add("frameGen");
        save();
    }

    public static boolean raytracing() {
        return raytracing == null || raytracing;
    }

    public static void setRaytracing(boolean on) {
        raytracing = on;
        SET.add("raytracing");
        save();
    }

    public static int lodChunks() {
        return lodChunks == null ? 256 : lodChunks;
    }

    public static void setLodChunks(int chunks) {
        lodChunks = clamp(chunks, 32, 1024);
        SET.add("lodDistance");
        save();
    }

    public static float lodPixelError() {
        return lodPixelError == null ? 1.0f : lodPixelError;
    }

    public static void setLodPixelError(float err) {
        lodPixelError = Math.max(0.25f, Math.min(8f, err));
        SET.add("lodPixelError");
        save();
    }

    public static float lodGpuMs() {
        return lodGpuMs == null ? 1.5f : lodGpuMs;
    }

    public static void setLodGpuMs(float ms) {
        lodGpuMs = Math.max(0.25f, Math.min(10f, ms));
        SET.add("lodGpuBudgetMs");
        save();
    }

    /** Seed fuer eine Serveradresse (Kleinschreibung, ohne Standardport), sonst null. */
    public static Long seedFor(String serverAddress) {
        return SEEDS.get(serverKey(serverAddress));
    }

    public static void setSeed(String serverAddress, Long seed) {
        if (seed == null) SEEDS.remove(serverKey(serverAddress));
        else SEEDS.put(serverKey(serverAddress), seed);
        save();
    }

    /** Properties-Schluessel: ':' ist dort ein Trenner -> Port mit '_' abtrennen. */
    private static String serverKey(String address) {
        String a = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        if (a.endsWith(":25565")) a = a.substring(0, a.length() - 6);
        return a.replace(':', '_');
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static void load() {
        if (!Files.exists(FILE)) return;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(FILE)) {
            p.load(r);
        } catch (IOException e) {
            LOG.warn("[vulkanfish] {} nicht lesbar: {}", FILE, e.toString());
            return;
        }
        try {
            fullbright = clamp(Integer.parseInt(p.getProperty("fullbright", "0").trim()), 0, 100);
            for (String k : new String[]{"taa", "dlss", "frameGen", "raytracing", "lodDistance", "lodPixelError", "lodGpuBudgetMs"}) {
                if (p.containsKey(k)) SET.add(k);
            }
            if (p.containsKey("taa")) taa = Boolean.parseBoolean(p.getProperty("taa").trim());
            if (p.containsKey("raytracing")) raytracing = Boolean.parseBoolean(p.getProperty("raytracing").trim());
            if (p.containsKey("dlss")) dlss = Boolean.parseBoolean(p.getProperty("dlss").trim());
            if (p.containsKey("frameGen")) frameGen = clamp(Integer.parseInt(p.getProperty("frameGen").trim()), 1, 6);
            if (p.containsKey("lodDistance")) lodChunks = clamp(Integer.parseInt(p.getProperty("lodDistance").trim()), 32, 1024);
            if (p.containsKey("lodPixelError")) lodPixelError = Float.parseFloat(p.getProperty("lodPixelError").trim());
            if (p.containsKey("lodGpuBudgetMs")) lodGpuMs = Float.parseFloat(p.getProperty("lodGpuBudgetMs").trim());
        } catch (NumberFormatException e) {
            LOG.warn("[vulkanfish] {}: ungueltiger Wert ({})", FILE, e.getMessage());
        }
        for (String name : p.stringPropertyNames()) {
            if (!name.startsWith("seed.")) continue;
            try {
                SEEDS.put(name.substring(5), Long.parseLong(p.getProperty(name).trim()));
            } catch (NumberFormatException e) {
                LOG.warn("[vulkanfish] Seed fuer {} ungueltig: {}", name.substring(5), p.getProperty(name));
            }
        }
    }

    private static void save() {
        Properties p = new Properties();
        p.setProperty("fullbright", Integer.toString(fullbright));
        if (SET.contains("taa")) p.setProperty("taa", Boolean.toString(taa()));
        if (SET.contains("raytracing")) p.setProperty("raytracing", Boolean.toString(raytracing()));
        if (SET.contains("dlss")) p.setProperty("dlss", Boolean.toString(dlss()));
        if (SET.contains("frameGen")) p.setProperty("frameGen", Integer.toString(frameGeneration()));
        if (SET.contains("lodDistance")) p.setProperty("lodDistance", Integer.toString(lodChunks()));
        if (SET.contains("lodPixelError")) p.setProperty("lodPixelError", Float.toString(lodPixelError()));
        if (SET.contains("lodGpuBudgetMs")) p.setProperty("lodGpuBudgetMs", Float.toString(lodGpuMs()));
        SEEDS.forEach((server, seed) -> p.setProperty("seed." + server, Long.toString(seed)));
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer w = Files.newBufferedWriter(FILE)) {
                p.store(w, "Vulkanfish - Einstellungen aus dem Spiel (Videoeinstellungen)");
            }
        } catch (IOException e) {
            LOG.warn("[vulkanfish] {} nicht schreibbar: {}", FILE, e.toString());
        }
    }
}
