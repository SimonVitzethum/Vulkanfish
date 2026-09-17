package simon.vulkanfish.client.render;

/**
 * Partikel-Ratenbremse gegen Spawn-Stuerme (z. B. hunderte primed TNT mit Rauch): Vanilla simuliert
 * und submittet jedes Partikel pro Frame auf dem Render-Thread – jenseits einiger Tausend bricht die
 * Framerate ein, obwohl man den Unterschied optisch kaum sieht. Begrenzt die Spawns pro Sekunde
 * (Standard 4096/s, normal sind es einige Hundert); Ueberzaehliges faellt weg wie bei Vanillas
 * Partikeleinstellung "Reduziert". Render-Thread. Abschaltbar: -Dvulkanfish.particleRate=0.
 */
public final class ParticleThrottle {
    private static final long RATE = Long.getLong("vulkanfish.particleRate", 4096);
    private static long windowMs, used, dropped;

    private ParticleThrottle() {
    }

    public static boolean allow() {
        if (RATE <= 0) return true;
        long now = System.currentTimeMillis();
        if (now - windowMs >= 1000) {
            windowMs = now;
            used = 0;
        }
        if (used >= RATE) {
            dropped++;
            return false;
        }
        used++;
        return true;
    }

    /** Verworfene Spawns seit dem letzten Abruf (Diagnose im CPU-Log). */
    public static long takeDropped() {
        long d = dropped;
        dropped = 0;
        return d;
    }
}
