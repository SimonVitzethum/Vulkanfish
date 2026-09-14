package simon.vulkanfish.client.render;

import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;
import simon.vulkanfish.client.VulkanfishSettings;
import simon.vulkanfish.client.gpu.NativePassRunner;

/**
 * Vanillas Lightmap im Vulkanfish-Look: Entities, Block-Entities (Truhen), Partikel und die Hand
 * beleuchtet Vanilla selbst ueber die 16x16-Lightmap (Blocklicht x Himmelslicht). Mit Vanillas
 * Tabelle passten sie nicht zum Terrain: kein warmes Fackellicht, andere Helligkeit (unsere
 * Belichtung + Kurve). Hier dieselbe Rechnung wie im Deferred-Pass (look.slang) fuer eine weisse
 * Flaeche mit mittlerer Ausrichtung, durch dieselbe Tonemap-Kurve – Vanilla multipliziert die
 * Textur damit wie bisher. Sonnenschatten legt entity_shade.slang danach darauf.
 */
public final class EntityLightmap {
    private static final float[] TORCH = {1.00f, 0.58f, 0.28f};
    private static final float[] MIN_LIGHT = {0.010f, 0.011f, 0.014f};
    private static final float AVG_UP = 0.75f;   // mittlere Ausrichtung einer Entity (n.y*0.5+0.5)
    private static final float AVG_NDL = 0.6f;   // mittleres N.L zur Sonne (wie entity_shade.slang)
    private static ByteBuffer buf;

    private EntityLightmap() {
    }

    /** 16x16 RGBA8, Index x = Blocklicht, y = Himmelslicht (wie Vanillas Lightmap). */
    public static ByteBuffer compute(NativePassRunner.FrameUniformsData d) {
        if (buf == null) buf = MemoryUtil.memAlloc(16 * 16 * 4);
        float[] amb = new float[3], dir = new float[3];
        ambient(d, amb);
        float fade = (float) Math.sqrt(d.lightDir()[0] * d.lightDir()[0] + d.lightDir()[1] * d.lightDir()[1] + d.lightDir()[2] * d.lightDir()[2]);
        direct(d, dir);
        float fb = VulkanfishSettings.fullbright();
        float fbs = fb * (float) Math.sqrt(fb) * lerp(0.7f, 1.0f, AVG_UP);
        float[] c = new float[3];
        for (int sky = 0; sky < 16; sky++) {
            float s = sky / 15.0f;
            float skyGate = smoothstep(0.35f, 0.95f, s);
            float sunVis = d.dimension() == 0 ? lerp(skyGate, 1.0f, 0.75f) * smoothstep(0.02f, 0.33f, s) : 0.0f;
            for (int block = 0; block < 16; block++) {
                float b = block / 15.0f;
                float bl = b * b * (0.55f + 0.45f * b * b) * 2.4f;
                for (int i = 0; i < 3; i++) {
                    float a = d.dimension() == 0 ? amb[i] * s * s : amb[i];
                    float sun = d.dimension() == 0 ? dir[i] * AVG_NDL * fade * sunVis : 0.0f;
                    float fill = fbs * (i == 0 ? 0.62f : i == 1 ? 0.66f : 0.72f);
                    c[i] = sun + a + TORCH[i] * bl + fill + MIN_LIGHT[i];
                }
                tonemap(c, d.exposure());
                int o = (sky * 16 + block) * 4;
                buf.put(o, (byte) Math.round(c[0] * 255)).put(o + 1, (byte) Math.round(c[1] * 255))
                        .put(o + 2, (byte) Math.round(c[2] * 255)).put(o + 3, (byte) 255);
            }
        }
        return buf;
    }

    private static void ambient(NativePassRunner.FrameUniformsData d, float[] out) {
        if (d.dimension() == 1) {
            out[0] = 0.14f; out[1] = 0.07f; out[2] = 0.05f;
            return;
        }
        if (d.dimension() != 0) {
            out[0] = 0.09f; out[1] = 0.08f; out[2] = 0.12f;
            return;
        }
        float[] sky = d.skyColor();
        float[] dayUp = new float[3], dusk = {0.50f * 0.35f, 0.38f * 0.35f, 0.42f * 0.35f}, base = {0.40f, 0.55f, 0.88f};
        float nightK = 0.7f + 0.6f * d.moonBrightness();
        float[] night = {0.040f * nightK, 0.055f * nightK, 0.100f * nightK};
        float noonT = smoothstep(0.0f, 0.45f, d.noonFactor());
        for (int i = 0; i < 3; i++) {
            dayUp[i] = lerp(base[i], (float) Math.pow(sky[i], 2.2) * 1.1f, 0.4f) * 0.55f;
            float day = lerp(dusk[i], dayUp[i], noonT);
            out[i] = lerp(night[i], day, d.sunVisibility());
        }
        float l = luma(out);
        float[] grey = {l * 0.85f, l * 0.9f, l};
        for (int i = 0; i < 3; i++) out[i] = lerp(out[i], grey[i], d.rainFactor() * 0.6f) * lerp(0.45f, 1.0f, AVG_UP);
    }

    private static void direct(NativePassRunner.FrameUniformsData d, float[] out) {
        float noonT = smoothstep(0.0f, 0.55f, d.noonFactor());
        float rainSun = lerp(1.0f, 0.12f, d.rainFactor());
        float moonK = (0.10f + 0.18f * d.moonBrightness()) * lerp(1.0f, 0.25f, d.rainFactor());
        float[] noon = {2.3f, 0.93f * 2.3f, 0.82f * 2.3f}, low = {1.7f, 0.50f * 1.7f, 0.20f * 1.7f}, moon = {0.36f, 0.48f, 0.78f};
        for (int i = 0; i < 3; i++) {
            float sun = lerp(low[i], noon[i], noonT) * rainSun;
            out[i] = lerp(moon[i] * moonK, sun, d.sunVisibility());
        }
    }

    /** look.slang tonemap(): Filmkurve (liefert Gamma), S-Kontrast, Saettigung. */
    private static void tonemap(float[] c, float exposure) {
        for (int i = 0; i < 3; i++) {
            float x = Math.max(c[i] * exposure - 0.002f, 0.0f);
            float m = (x * (6.2f * x + 0.5f)) / (x * (6.2f * x + 1.7f) + 0.06f);
            m = Math.min(Math.max(m, 0.0f), 1.0f);
            c[i] = m * m * (3.0f - 2.0f * m) * 0.25f + m * 0.75f;
        }
        float l = luma(c);
        for (int i = 0; i < 3; i++) c[i] = Math.min(Math.max(lerp(l, c[i], 1.12f), 0.0f), 1.0f);
    }

    private static float luma(float[] c) {
        return c[0] * 0.2126f + c[1] * 0.7152f + c[2] * 0.0722f;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Math.min(Math.max((x - e0) / (e1 - e0), 0.0f), 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }
}
