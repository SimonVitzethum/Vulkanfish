package simon.vulkanfish.client.gpu;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;

/**
 * DLSS Super Resolution: die Welt wird in einem kleineren Ziel gerendert, DLSS rechnet sie auf
 * Bildschirmaufloesung hoch; Hand und GUI zeichnet Vanilla danach in voller Aufloesung.
 *
 * <p>Waehrend des Level-Renderns liefert {@code GameRenderer.mainRenderTarget()} das kleine Ziel
 * (GameRendererScaleMixin) – Vanillas Frame-Graph, Entities, Partikel, Wolken und unser Renderer
 * nehmen es so alle automatisch. Am Ende des Levels (VulkanfishRenderer.onFrameEnd) schreibt
 * DLSS ins echte Ziel, dann gilt wieder das grosse.
 */
public final class RenderScale {
    /** Faktoren der DLSS-Modi: DLAA, Qualitaet, Ausgewogen, Leistung, Ultra-Leistung. */
    public static final float[] MODE_SCALE = {1.0f, 0.667f, 0.58f, 0.5f, 0.333f};

    private static volatile float wanted = 1.0f;   // aus den Einstellungen (nur wenn DLSS laeuft)
    private static TextureTarget low;
    private static RenderTarget full;
    private static boolean active;

    private RenderScale() {
    }

    /** Vom Renderer je Frame: gewuenschter Faktor (1 = kein Hochskalieren). */
    public static void setWanted(float scale) {
        wanted = Math.max(0.25f, Math.min(1.0f, scale));
    }

    public static float wanted() {
        return wanted;
    }

    /** Aus GameRenderer.renderLevel (HEAD): ab jetzt ins kleine Ziel. */
    public static void beginLevel(RenderTarget real) {
        active = false;
        float s = wanted;
        if (s >= 0.999f || real == null) return;
        int w = Math.max(1, Math.round(real.width * s)), h = Math.max(1, Math.round(real.height * s));
        if (low == null) {
            low = new TextureTarget("Vulkanfish Render", w, h, true, GpuFormat.RGBA8_UNORM);
        } else if (low.width != w || low.height != h) {
            low.resize(w, h);
        }
        full = real;
        active = true;
    }

    /** Liefert das kleine Ziel, solange die Welt gerendert wird (sonst das echte). */
    public static RenderTarget redirect(RenderTarget real) {
        return active ? low : real;
    }

    public static boolean active() {
        return active;
    }

    public static RenderTarget full() {
        return full;
    }

    /** Nach dem Hochskalieren (oder als Notweg vor der Hand): wieder das echte Ziel. */
    public static void endLevel() {
        active = false;
    }

    /** Beim Beenden des Renderers (vor Mojangs Device-Ende). */
    public static void destroy() {
        active = false;
        if (low != null) low.destroyBuffers();
        low = null;
        full = null;
    }

    /** Aktueller Faktor des kleinen Ziels (fuer die Jitter-Folge). */
    public static float current() {
        return active && full != null && full.width > 0 ? (float) low.width / full.width : 1.0f;
    }
}
