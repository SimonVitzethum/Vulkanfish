package simon.vulkanfish.client.gpu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Baut pro Frame die skalaren GPU-Treiber aus VANILLAS Render-State
 * (keine doppelte Matrizen-/Himmels-Logik auf CPU, kein Szenen-Content).
 *
 * <p>Himmel/Sonne kommen direkt aus Vanillas Environment-Attributen der
 * Kamera (SUN_ANGLE in Grad, 0 = Mittag; SKY_COLOR als RGB) – NICHT aus dem
 * SkyRenderState: den befuellt Vanilla nur, solange es selbst einen
 * Himmel-Renderer hat, und dessen Pass ersetzen wir in der Overworld.
 *
 * <p>Projektion: exakt die Matrix, die GameRenderer.renderLevel an Blaze3D
 * gibt (inkl. Bobbing/Nausea, Reverse-Z, zZeroToOne) – per Mixin abgegriffen.
 */
public final class FrameDataCapture {
    private static final Logger LOG = LoggerFactory.getLogger("vulkanfish");
    private static final Matrix4f LEVEL_PROJECTION = new Matrix4f();
    private static volatile boolean haveProjection;
    private static boolean warned;
    private static final long T0 = System.nanoTime();
    // Sonnenbahn um die X-Achse geneigt: auch mittags schraege, lesbare Schatten
    private static final float SUN_TILT = (float) Math.toRadians(30.0);
    /** Halbe Kantenlaenge des Schatten-Lichtraums in Bloecken. */
    public static final float SHADOW_DISTANCE = 128.0f;
    private static final float SHADOW_DEPTH = 384.0f;
    private static final float[] MOON_PHASE_BRIGHTNESS = {1.0f, 0.75f, 0.5f, 0.25f, 0.0f, 0.25f, 0.5f, 0.75f};
    private static float caveSmoothed;
    private static final boolean DEBUG_LOG = Boolean.getBoolean("vulkanfish.snapshots");
    /** Nur Selbsttest: fester Sonnenwinkel in Grad (NaN = Vanilla-Wert). */
    static volatile float testSunAngle = Float.NaN;
    static volatile float lastSunAngle;
    /** Diagnose: Zeit anhalten (siehe capture). */
    static final boolean FREEZE_TIME = Boolean.getBoolean("vulkanfish.freezeTime");

    private FrameDataCapture() {
    }

    private static final Matrix4f LEVEL_PROJECTION_UNJITTERED = new Matrix4f();
    /** TAA aktiv (vom Renderer gesetzt): dann wird die Level-Projektion gejittert. */
    public static volatile boolean taaJitter;
    private static int jitterIndex;
    /** Jitter dieses Frames in Pixeln (x nach rechts, y nach unten), fuer DLSS. */
    public static volatile float jitterPxX, jitterPxY;

    /**
     * Aus dem GameRenderer-Mixin (Render-Thread): Projektion merken und bei aktivem TAA
     * in-place um einen Subpixel-Offset (Halton 2/3, 8 Positionen) verschieben.
     */
    public static void setLevelProjection(Matrix4f projection) {
        LEVEL_PROJECTION_UNJITTERED.set(projection);
        if (taaJitter) {
            var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            // Folge laenger bei kleinerer Renderaufloesung (DLSS: 8 x Skalierung^2 Phasen)
            float s = RenderScale.current();
            int phases = Math.max(8, Math.min(64, Math.round(8.0f / (s * s))));
            int i = (jitterIndex++ % phases) + 1;
            float jx = halton(i, 2) - 0.5f;
            float jy = halton(i, 3) - 0.5f;
            // Clip-Offset proportional zu w (Perspektive: w = -z_view) -> konstanter NDC-Versatz
            projection.m20(projection.m20() - jx * 2.0f / target.width);
            projection.m21(projection.m21() - jy * 2.0f / target.height);
            jitterPxX = jx;
            jitterPxY = jy;
        } else {
            jitterPxX = jitterPxY = 0f;
        }
        LEVEL_PROJECTION.set(projection);
        haveProjection = true;
    }

    private static float halton(int index, int base) {
        float f = 1.0f, r = 0.0f;
        while (index > 0) {
            f /= base;
            r += f * (index % base);
            index /= base;
        }
        return r;
    }

    /** LOD-Fernfeld aktiv: Nebel/Horizont an dessen Ende statt an der Vanilla-Sichtweite. */
    public static volatile float lodFogDistance;

    public static boolean hasProjection() {
        return haveProjection;
    }

    /** Letzte Frame-Daten (Lightmap fuer Vanillas Entities: Licht wie im Deferred-Pass). */
    public static volatile NativePassRunner.FrameUniformsData last;

    /** Kopie der ungejitterten Level-Projektion (DLSS Frame Generation). */
    public static Matrix4f levelProjectionUnjittered() {
        return new Matrix4f(LEVEL_PROJECTION_UNJITTERED);
    }

    /** m11 der Level-Projektion (= 1 / tan(fovY/2)), fuer die Pixelgroesse des LOD. */
    public static float projectionM11() {
        return haveProjection ? LEVEL_PROJECTION_UNJITTERED.m11() : 1.0f;
    }

    public static NativePassRunner.FrameUniformsData capture(long frameIndex, CameraRenderState cam, SkyRenderState sky,
                                                             int shadowResolution) {
        try {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null || cam == null) return null;

            // Render-Ursprung: Ursprung der Kamera-Section (ganzzahlig, Vielfaches von 16). Alle GPU-
            // Positionen sind relativ dazu -> float bleibt auch bei Koordinaten in Millionenhoehe genau
            int ox = Math.floorDiv((int) Math.floor(cam.pos.x), 16) * 16;
            int oy = Math.floorDiv((int) Math.floor(cam.pos.y), 16) * 16;
            int oz = Math.floorDiv((int) Math.floor(cam.pos.z), 16) * 16;
            float rx = (float) (cam.pos.x - ox), ry = (float) (cam.pos.y - oy), rz = (float) (cam.pos.z - oz);
            // ViewProj = Level-Projektion * (Rotation * Translation), relativer Raum
            Matrix4f view = new Matrix4f().set(cam.viewRotationMatrix);
            view.translate(-rx, -ry, -rz);
            Matrix4f viewProj = new Matrix4f(haveProjection ? LEVEL_PROJECTION : cam.projectionMatrix).mul(view);
            float[] vp = viewProj.get(new float[16]); // JOML: column-major (wie das Shader-Layout)
            Matrix4f projRotUnj = new Matrix4f(haveProjection ? LEVEL_PROJECTION_UNJITTERED : cam.projectionMatrix)
                    .mul(new Matrix4f().set(cam.viewRotationMatrix));
            // Gejitterte Variante (LEVEL_PROJECTION traegt den Jitter dieses Frames): Basis der
            // TAA-Reprojektion – die History enthaelt den GEJITTERTEN Vorframe, nicht Pixelzentren!
            Matrix4f projRotJ = new Matrix4f(haveProjection ? LEVEL_PROJECTION : cam.projectionMatrix)
                    .mul(new Matrix4f().set(cam.viewRotationMatrix));
            float[] vpUnjittered = new Matrix4f(projRotUnj).translate(-rx, -ry, -rz).get(new float[16]);
            float[] invVp = new Matrix4f(viewProj).invert().get(new float[16]);

            int dimension = level.dimension() == Level.OVERWORLD ? 0 : level.dimension() == Level.NETHER ? 1 : 2;

            // Sonne: Winkel aus Vanillas Environment-Attributen (Grad), Bahn geneigt
            float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            EnvironmentAttributeProbe probe = mc.gameRenderer.mainCamera().attributeProbe();
            float sunAngle = Float.isNaN(testSunAngle) ? probe.getValue(EnvironmentAttributes.SUN_ANGLE, pt) : testSunAngle;
            lastSunAngle = sunAngle;
            double a = Math.toRadians(sunAngle);
            Vector3f sunDir = new Vector3f((float) -Math.sin(a), (float) (Math.cos(a) * Math.cos(SUN_TILT)),
                    (float) (Math.cos(a) * Math.sin(SUN_TILT))).normalize();
            float elev = sunDir.y;
            float noon = clamp01(elev * 1.2f);
            float vis = clamp01((elev + 0.10f) * 3.0f);
            // Schattenlicht: Sonne ueber, Mond unter dem Horizont; am Horizont auf 0 blenden,
            // damit der Wechsel unsichtbar ist (Laenge von lightDir = Staerke)
            Vector3f light = elev >= 0 ? new Vector3f(sunDir) : new Vector3f(sunDir).negate();
            float fade = smoothstep(0.0f, 0.08f, Math.abs(elev));
            float[] lightDir = {light.x * fade, light.y * fade, light.z * fade};

            float rain = clamp01(level.getRainLevel(pt));
            float thunder = 0.0f;
            try {
                thunder = level.getThunderLevel(1.0f);
            } catch (Throwable ignored) {
            }
            // Selbsttest mit fester Sonne: typische Vanilla-Taghimmelfarbe statt der (evtl. naechtlichen) echten
            // (26.3: SKY_COLOR ist Vector3fc 0..1 statt gepacktem Int)
            org.joml.Vector3fc skyVec = Float.isNaN(testSunAngle) || vis < 0.5f
                    ? probe.getValue(EnvironmentAttributes.SKY_COLOR, pt) : null;
            float[] skyRgb = skyVec != null ? new float[]{skyVec.x(), skyVec.y(), skyVec.z()}
                    : new float[]{0x78 / 255.0f, 0xA7 / 255.0f, 0xFF / 255.0f};
            MoonPhase phase = probe.getValue(EnvironmentAttributes.MOON_PHASE, pt);
            float moon = phase != null ? MOON_PHASE_BRIGHTNESS[phase.ordinal() & 7] : 1.0f;
            float[] fog = {cam.fogData.color.x, cam.fogData.color.y, cam.fogData.color.z};
            int fogType = cam.fogType == FogType.WATER ? 1 : cam.fogType == FogType.LAVA ? 2
                    : cam.fogType == FogType.POWDER_SNOW ? 3 : 0;

            // Hoehlenfaktor aus dem Himmelslicht an der Kamera, zeitlich geglaettet
            float skyAtCam = level.getBrightness(LightLayer.SKY, cam.blockPos) / 15.0f;
            caveSmoothed += ((1.0f - skyAtCam) - caveSmoothed) * 0.04f;
            float exposure = lerp(1.35f, 0.9f, vis) * lerp(1.0f, 1.35f, caveSmoothed);

            // Schatten-Lichtraum (nur Overworld, nur wenn Licht ueberhaupt wirkt)
            boolean shadows = dimension == 0 && fade > 0.01f;
            float[] shadowVp = new float[16];
            float[] shadowFrustum = new float[24];
            float[] shadowEye = new float[3];
            if (shadows) {
                Matrix4f sv = shadowViewProj(light, cam.pos.x, cam.pos.y, cam.pos.z, ox, oy, oz, shadowResolution);
                sv.get(shadowVp);
                shadowFrustum = extractFrustum(transpose(shadowVp));
                shadowEye = new float[]{rx + light.x * 1e4f, ry + light.y * 1e4f, rz + light.z * 1e4f};
            }

            if (DEBUG_LOG && frameIndex % 120 == 0) {
                LOG.info("[vulkanfish] Licht: sunAngle={} elev={} vis={} noon={} rain={} sky=({},{},{}) moon={} fade={} dim={} fog={}",
                        sunAngle, elev, vis, noon, rain, skyRgb[0], skyRgb[1], skyRgb[2], moon, fade, dimension, fogType);
            }
            float renderDist = Math.max(mc.options.getEffectiveRenderDistance() * 16.0f, lodFogDistance);
            // Diagnose (-Dvulkanfish.freezeTime): Zeit anhalten – Wind, Wellen, Kaustik und
            // Sterne stehen still. Flackert es dann weiter, ist es nicht zeitgetrieben.
            float time = FREEZE_TIME ? 100.0f : (float) (((System.nanoTime() - T0) / 1e9) % 3600.0);
            return last = new NativePassRunner.FrameUniformsData(
                    vp, invVp, shadowVp,
                    rx, ry, rz, time,
                    new float[]{sunDir.x, sunDir.y, sunDir.z}, dimension == 0 ? vis : 0.0f, lightDir, noon,
                    skyRgb, renderDist, fog, rain, thunder, shadows ? SHADOW_DISTANCE : 0.0f,
                    dimension, fogType, moon, caveSmoothed, exposure, frameIndex,
                    extractFrustum(transpose(vp)), shadowFrustum, shadowEye, vpUnjittered,
                    cam.pos.x, cam.pos.y, cam.pos.z, ox, oy, oz, projRotUnj.get(new float[16]),
                    projRotJ.get(new float[16]));
        } catch (Throwable th) {
            if (!warned) {
                warned = true;
                LOG.warn("[vulkanfish] FrameDataCapture aus ({}), Frames ohne native Submission", th.toString());
            }
            return null;
        }
    }

    /**
     * Ortho-Lichtraum um die Kamera (im relativen Raum um den Render-Ursprung o). Die Kamera wird
     * im Lichtraum auf das Texelraster der (verzerrten) Map-Mitte gerastet -> kein Flimmern der
     * Schattenkanten beim Laufen. Gerastet wird in double-Weltkoordinaten: gleiche Raster-
     * position unabhaengig vom Ursprung (kein Sprung, wenn der Ursprung wechselt) und bei hohen
     * Koordinaten keine float-Rundung, die das Raster springen laesst.
     */
    private static Matrix4f shadowViewProj(Vector3f light, double cx, double cy, double cz, int ox, int oy, int oz, int res) {
        Vector3f up = Math.abs(light.y) > 0.99f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
        Matrix4f rot = new Matrix4f().lookAt(0, 0, 0, -light.x, -light.y, -light.z, up.x, up.y, up.z);
        // Lichtraum-Position der Kamera und des Ursprungs in double (rot ist orthonormal, ohne Translation)
        double pcx = rot.m00() * cx + rot.m10() * cy + rot.m20() * cz;
        double pcy = rot.m01() * cx + rot.m11() * cy + rot.m21() * cz;
        double pcz = rot.m02() * cx + rot.m12() * cy + rot.m22() * cz;
        double pox = rot.m00() * ox + rot.m10() * oy + rot.m20() * oz;
        double poy = rot.m01() * ox + rot.m11() * oy + rot.m21() * oz;
        double poz = rot.m02() * ox + rot.m12() * oy + rot.m22() * oz;
        double texel = 2.0 * SHADOW_DISTANCE / res * 0.14; // Texelgroesse in der Map-Mitte (Verzerrung 0.86)
        double sx = Math.floor(pcx / texel) * texel;
        double sy = Math.floor(pcy / texel) * texel;
        // relativer Punkt p: Lichtraum = rot*p + rot*o; verschoben um die gerastete Kamera
        Matrix4f viewL = new Matrix4f().translation((float) (pox - sx), (float) (poy - sy), (float) (poz - pcz)).mul(rot);
        return new Matrix4f().setOrtho(-SHADOW_DISTANCE, SHADOW_DISTANCE, -SHADOW_DISTANCE, SHADOW_DISTANCE,
                -SHADOW_DEPTH, SHADOW_DEPTH, true).mul(viewL);
    }

    private static float[] transpose(float[] m) {
        float[] t = new float[16];
        for (int r = 0; r < 4; r++)
            for (int c = 0; c < 4; c++)
                t[r * 4 + c] = m[c * 4 + r];
        return t;
    }

    private static float clamp01(float v) {
        return Math.min(1.0f, Math.max(0.0f, v));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3.0f - 2.0f * t);
    }

    /**
     * Gribb/Hartmann aus row-major Matrix, Ebenen normiert. Die z-Ebenen sind
     * fuer [-1,1] formuliert; bei Reverse-Z/zZeroToOne ist Ebene 4 (z >= -w)
     * lockerer als noetig -> konservativ, Ebene 5 (z <= w) ist die Near-Plane.
     */
    static float[] extractFrustum(float[] m) {
        float[] p = new float[24];
        setPlane(p, 0, m[12] + m[0], m[13] + m[1], m[14] + m[2], m[15] + m[3]);
        setPlane(p, 1, m[12] - m[0], m[13] - m[1], m[14] - m[2], m[15] - m[3]);
        setPlane(p, 2, m[12] + m[4], m[13] + m[5], m[14] + m[6], m[15] + m[7]);
        setPlane(p, 3, m[12] - m[4], m[13] - m[5], m[14] - m[6], m[15] - m[7]);
        setPlane(p, 4, m[12] + m[8], m[13] + m[9], m[14] + m[10], m[15] + m[11]);
        setPlane(p, 5, m[12] - m[8], m[13] - m[9], m[14] - m[10], m[15] - m[11]);
        return p;
    }

    private static void setPlane(float[] p, int i, float a, float b, float c, float d) {
        float len = (float) Math.sqrt(a * a + b * b + c * c);
        if (len < 1e-8f) len = 1.0f;
        p[i * 4] = a / len;
        p[i * 4 + 1] = b / len;
        p[i * 4 + 2] = c / len;
        p[i * 4 + 3] = d / len;
    }
}
