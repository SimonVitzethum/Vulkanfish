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

    private static void load() {
        if (!Files.exists(FILE)) return;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(FILE)) {
            p.load(r);
            fullbright = Math.max(0, Math.min(100, Integer.parseInt(p.getProperty("fullbright", "0").trim())));
        } catch (IOException | NumberFormatException e) {
            LOG.warn("[vulkanfish] {} nicht lesbar: {}", FILE, e.toString());
        }
    }

    private static void save() {
        Properties p = new Properties();
        p.setProperty("fullbright", Integer.toString(fullbright));
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
