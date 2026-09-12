package simon.vulkanfish.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Im Spiel aenderbare Einstellungen (Videoeinstellungen -> Abschnitt Vulkanfish), gespeichert in
 * config/vulkanfish-client.properties. Startwerte aus der Datei, Aenderungen gelten sofort.
 */
public final class VulkanfishSettings {
    private static final Logger LOG = LoggerFactory.getLogger("vulkanfish");
    private static final Path FILE = Path.of("config", "vulkanfish-client.properties");

    /** Fullbright in Prozent (0 = aus). */
    private static volatile int fullbright;
    /** Bekannte Seeds je Serveradresse (Mehrspieler-LOD-Generierung). */
    private static final java.util.Map<String, Long> SEEDS = new java.util.concurrent.ConcurrentHashMap<>();

    static {
        load();
    }

    private VulkanfishSettings() {
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
        fullbright = Math.max(0, Math.min(100, percent));
    }

    public static void setFullbright(int percent) {
        fullbright = Math.max(0, Math.min(100, percent));
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
        String a = address == null ? "" : address.trim().toLowerCase(java.util.Locale.ROOT);
        if (a.endsWith(":25565")) a = a.substring(0, a.length() - 6);
        return a.replace(':', '_');
    }

    private static void load() {
        if (!Files.exists(FILE)) return;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(FILE)) {
            p.load(r);
            fullbright = Math.max(0, Math.min(100, Integer.parseInt(p.getProperty("fullbright", "0").trim())));
            for (String name : p.stringPropertyNames()) {
                if (!name.startsWith("seed.")) continue;
                try {
                    SEEDS.put(name.substring(5), Long.parseLong(p.getProperty(name).trim()));
                } catch (NumberFormatException e) {
                    LOG.warn("[vulkanfish] Seed fuer {} ungueltig: {}", name.substring(5), p.getProperty(name));
                }
            }
        } catch (IOException | NumberFormatException e) {
            LOG.warn("[vulkanfish] {} nicht lesbar: {}", FILE, e.toString());
        }
    }

    private static void save() {
        Properties p = new Properties();
        p.setProperty("fullbright", Integer.toString(fullbright));
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
