package simon.vulkanfish.client.render;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.FallingBlockRenderState;
import net.minecraft.client.renderer.entity.state.TntRenderState;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import simon.vulkanfish.client.VulkanfishClient;
import simon.vulkanfish.client.gpu.FrameDataCapture;
import simon.vulkanfish.client.gpu.NativePassRunner;

/**
 * Eigene Entity-Pipeline fuer starre Blockmodelle (TNT, fallende Bloecke, spaeter Items/
 * Loren-Inhalte): statt pro Entity Geometrie auf der CPU zu expandieren, zu stagen und mit
 * eigenem Draw zu fahren, wird das Modell EINMAL gebacken (Vanillas BakedQuads: Position, Atlas-
 * UV, geometrische Normale) und pro Entity nur eine Instanz geschrieben (Matrix + Licht + Flags,
 * 104 Byte = EntityInstance-Struktur im Shader). Ein instanced Draw pro Modell zeichnet alles.
 *
 * <p>Umlenkung am Submit (pro Entity, virtuell – produktionsfest): Vanillas Frustum-Culling
 * (shouldRender) laeuft vorher, unsichtbare Entities erzeugen keine Records. Extract-Kosten
 * (State + BlockModel-Update) bleiben – Submit-Expansion, Staging-Upload und Draws entfallen.
 * Bereit nur wenn Renderer + Pipes + Bake stehen, sonst Vanilla (auch bei GL-Backend, fehlendem
 * Modell, vollem Puffer oder abgeschaltet via -Dvulkanfish.entityPipeline=false).
 */
public final class EntityInstancing {
    private static final Logger LOG = LoggerFactory.getLogger("vulkanfish");
    /** Instanz-Layout (108 Byte = EntityInstance + light + atlas): 12 Modell + 4 Tint/Flag + 3+1+3+1+2+1. */
    public static final int FLOATS_PER_INSTANCE = 27;
    public static final int MAX_INSTANCES = 32768;
    private static final boolean ENABLED =
            !"false".equals(System.getProperty("vulkanfish.entityPipeline", "true"));

    /** Gebackenes Modell (Modellraum 0..1, Atlas-UVs direkt – derselbe Atlas wie Vanilla). */
    public record BakedModel(float[] verts, int[] indices, boolean translucent, float[] aabb, int slot,
                             boolean itemsAtlas) {
    }

    /** Anstehender Bake-Upload (CPU-Arrays -> NativePassRunner erzeugt Device-Puffer). */
    public record BakedUpload(int slot, float[] verts, int[] indices) {
    }

    private static final BlockState TNT_STATE = Blocks.TNT.defaultBlockState();
    private static final Map<BlockState, BakedModel> MODELS = new IdentityHashMap<>();
    private static final List<BakedModel> BY_SLOT = new ArrayList<>();
    private static final List<BakedUpload> PENDING_UPLOADS = new ArrayList<>();

    private static float[] instances = new float[MAX_INSTANCES * FLOATS_PER_INSTANCE];
    private static float[] scratch = new float[MAX_INSTANCES * FLOATS_PER_INSTANCE];
    private static int instanceCount;
    private static final Matrix4f TMP_MAT = new Matrix4f(); // nur Render-Thread
    private static final float[] TMP_ARR = new float[16]; // column-major aus get()

    private EntityInstancing() {
    }

    /** Pro Frame aus onFrameStart (vor Vanillas Submits). */
    public static void beginFrame() {
        instanceCount = 0;
    }

    public static int instanceCount() {
        return instanceCount;
    }

    public static float[] instanceData() {
        return instances;
    }

    public static float[] scratch() {
        return scratch;
    }

    /** Gebackene Modelle je Slot (Upload/Draws im NativePassRunner). */
    public static List<BakedModel> models() {
        return BY_SLOT;
    }

    /** Anstehende Bake-Uploads abholen (leert die Liste). */
    public static List<BakedUpload> takeBakedUploads() {
        List<BakedUpload> out = new ArrayList<>(PENDING_UPLOADS);
        PENDING_UPLOADS.clear();
        return out;
    }

    /** Fremd gebackenes Modell (Items) registrieren: Slot + Upload einreihen. Render-Thread. */
    public static synchronized int registerBaked(BakedModel m) {
        int slot = BY_SLOT.size();
        BakedModel withSlot = new BakedModel(m.verts(), m.indices(), m.translucent(), m.aabb(), slot, m.itemsAtlas());
        BY_SLOT.add(withSlot);
        PENDING_UPLOADS.add(new BakedUpload(slot, withSlot.verts(), withSlot.indices()));
        return slot;
    }

    /** Passt noch eine Instanz (sonst Vanilla-Überlauf, vollständig statt halb)? */
    public static boolean fits(int extra) {
        return instanceCount + extra <= MAX_INSTANCES;
    }

    /** Instanz aus beliebiger Matrix schreiben (Items); false = voll. Nur Render-Thread. */
    public static boolean recordMatrix(BakedModel m, Matrix4f mat, int lightCoords, float white) {
        if (!reserve(m)) return false;
        mat.get(TMP_ARR);
        int o = instanceCount * FLOATS_PER_INSTANCE;
        writeRows(o);
        writeTail(m, lightCoords, white, o);
        instanceCount++;
        return true;
    }

    private static void writeTail(BakedModel m, int lightCoords, float white, int o) {
        instances[o + 12] = 1.0f;
        instances[o + 13] = 1.0f; // Tint weiss (getoente Arten bleiben Vanilla, siehe ItemBaker)
        instances[o + 14] = 1.0f;
        instances[o + 15] = white; // Fuse-Blitz
        instances[o + 16] = m.aabb()[0];
        instances[o + 17] = m.aabb()[1];
        instances[o + 18] = m.aabb()[2];
        instances[o + 19] = 0.0f; // emissive
        instances[o + 20] = m.aabb()[3];
        instances[o + 21] = m.aabb()[4];
        instances[o + 22] = m.aabb()[5];
        instances[o + 23] = m.slot();
        float block = ((lightCoords >> 4) & 15) / 15.0f;
        float sky = ((lightCoords >> 20) & 15) / 15.0f;
        instances[o + 24] = block;
        instances[o + 25] = sky;
        instances[o + 26] = m.itemsAtlas() ? 1.0f : 0.0f;
    }

    /** Modell zum BlockState (bake bei Bedarf, Render-Thread). null = Vanilla lassen. */
    public static synchronized BakedModel modelFor(BlockState state) {
        BakedModel m = MODELS.get(state);
        if (m != null) return m;
        m = bake(state);
        if (m == null) return null;
        BakedModel withSlot = new BakedModel(m.verts(), m.indices(), m.translucent(), m.aabb(), BY_SLOT.size(), m.itemsAtlas());
        MODELS.put(state, withSlot);
        BY_SLOT.add(withSlot);
        PENDING_UPLOADS.add(new BakedUpload(withSlot.slot(), withSlot.verts(), withSlot.indices()));
        return withSlot;
    }

    /** Modelle verwerfen (z. B. Ressourcen-Reload; faellt danach neu an). */
    public static synchronized void clearCache() {
        MODELS.clear();
        BY_SLOT.clear();
        PENDING_UPLOADS.clear();
    }

    private static BakedModel bake(BlockState state) {
        try {
            var mc = Minecraft.getInstance();
            if (mc.level == null) return null;
            var model = mc.getModelManager().getBlockStateModelSet().get(state);
            List<BlockStateModelPart> parts = new ArrayList<>();
            model.collectParts(RandomSource.create(42L), parts);
            List<Float> vp = new ArrayList<>();
            List<Integer> ip = new ArrayList<>();
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            List<net.minecraft.client.resources.model.geometry.BakedQuad> quads = new ArrayList<>();
            for (BlockStateModelPart part : parts) {
                quads.addAll(part.getQuads(null));
                for (Direction d : Direction.values()) quads.addAll(part.getQuads(d));
            }
            for (var q : quads) {
                float[] px = new float[4], py = new float[4], pz = new float[4], uu = new float[4], vv = new float[4];
                for (int v = 0; v < 4; v++) {
                    var p = q.position(v);
                    px[v] = p.x();
                    py[v] = p.y();
                    pz[v] = p.z();
                    long packed = q.packedUV(v);
                    uu[v] = net.minecraft.client.model.geom.builders.UVPair.unpackU(packed);
                    vv[v] = net.minecraft.client.model.geom.builders.UVPair.unpackV(packed);
                }
                // Geometrische Normale (Kreuzprodukt, ggf. gegen Facing korrigiert)
                float ex1 = px[1] - px[0], ey1 = py[1] - py[0], ez1 = pz[1] - pz[0];
                float ex2 = px[2] - px[0], ey2 = py[2] - py[0], ez2 = pz[2] - pz[0];
                float nx = ey1 * ez2 - ez1 * ey2, ny = ez1 * ex2 - ex1 * ez2, nz = ex1 * ey2 - ey1 * ex2;
                float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (nl > 1e-9f) {
                    nx /= nl;
                    ny /= nl;
                    nz /= nl;
                }
                Direction face = q.direction();
                if (face != null) {
                    float dot = nx * face.getStepX() + ny * face.getStepY() + nz * face.getStepZ();
                    if (dot < 0) {
                        nx = -nx;
                        ny = -ny;
                        nz = -nz;
                    }
                }
                int base = vp.size() / 8;
                for (int v = 0; v < 4; v++) {
                    vp.add(px[v]);
                    vp.add(py[v]);
                    vp.add(pz[v]);
                    vp.add(nx);
                    vp.add(ny);
                    vp.add(nz);
                    vp.add(uu[v]);
                    vp.add(vv[v]);
                    if (px[v] < minX) minX = px[v];
                    if (py[v] < minY) minY = py[v];
                    if (pz[v] < minZ) minZ = pz[v];
                    if (px[v] > maxX) maxX = px[v];
                    if (py[v] > maxY) maxY = py[v];
                    if (pz[v] > maxZ) maxZ = pz[v];
                }
                ip.add(base);
                ip.add(base + 1);
                ip.add(base + 2);
                ip.add(base + 2);
                ip.add(base + 3);
                ip.add(base);
            }
            if (ip.isEmpty()) return null;
            float[] verts = new float[vp.size()];
            for (int i = 0; i < verts.length; i++) verts[i] = vp.get(i);
            int[] indices = new int[ip.size()];
            for (int i = 0; i < indices.length; i++) indices[i] = ip.get(i);
            boolean translucent =
                    (model.materialFlags() & net.minecraft.client.resources.model.geometry.BakedQuad.FLAG_TRANSLUCENT) != 0;
            LOG.info("[vulkanfish] Entity-Modell gebacken: {} Quads (Block {}, transluzent={})",
                    indices.length / 6, state, translucent);
            return new BakedModel(verts, indices, translucent,
                    new float[]{minX, minY, minZ, maxX, maxY, maxZ}, -1, false);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean ready() {
        if (!ENABLED) return false;
        var r = VulkanfishClient.RENDERER;
        if (r == null || !r.useGpuDrivenPath()) return false;
        return NativePassRunner.entityReady();
    }

    private static boolean reserve(BakedModel m) {
        if (m == null || m.translucent() || instanceCount >= MAX_INSTANCES) return false;
        if (instanceCount * FLOATS_PER_INSTANCE + FLOATS_PER_INSTANCE > instances.length) return false;
        return true;
    }

    /** Matrix exakt wie TntRenderer.submit (T/Translate/Swellenwert/Rotationen). Render-Thread. */
    public static boolean divertTnt(TntRenderState state) {
        BakedModel m = modelFor(TNT_STATE);
        if (!ready() || !reserve(m) || FrameDataCapture.last == null) return false;
        var d = FrameDataCapture.last;
        float fuse = state.fuseRemainingInTicks;
        TMP_MAT.identity()
                .translate((float) (state.x - d.originX()), (float) (state.y - d.originY()), (float) (state.z - d.originZ()))
                .translate(0.0f, 0.5f, 0.0f);
        if (fuse < 10.0f) {
            float g = 1.0f - fuse / 10.0f;
            g = Mth.clamp(g, 0.0f, 1.0f);
            g *= g;
            g *= g;
            float s = 1.0f + g * 0.3f;
            TMP_MAT.scale(s);
        }
        TMP_MAT.rotateY((float) Math.toRadians(-90.0)).translate(-0.5f, -0.5f, 0.5f).rotateY((float) Math.toRadians(90.0));
        float white = (fuse >= 0.0f && ((int) (fuse / 5.0f)) % 2 == 0) ? 1.0f : 0.0f;
        writeInstance(m, state.lightCoords, white);
        return true;
    }

    /** Fallende Bloecke: nur Translation (keine Rotation), kein Blitz. Render-Thread. */
    public static boolean divertFalling(FallingBlockRenderState state) {
        BlockState bs = state.movingBlockRenderState.blockState;
        if (bs == null || bs.getRenderShape() != RenderShape.MODEL) return false;
        BakedModel m = modelFor(bs);
        if (!ready() || !reserve(m) || FrameDataCapture.last == null) return false;
        var d = FrameDataCapture.last;
        TMP_MAT.identity().translate((float) (state.x - 0.5 - d.originX()), (float) (state.y - d.originY()),
                (float) (state.z - 0.5 - d.originZ()));
        writeInstance(m, state.lightCoords, 0.0f);
        return true;
    }

    private static void writeInstance(BakedModel m, int lightCoords, float white) {
        int o = instanceCount * FLOATS_PER_INSTANCE;
        // float3x4-Zeilen aus column-major get(): Zeile i = arr[i],arr[i+4],arr[i+8],arr[i+12]
        TMP_MAT.get(TMP_ARR);
        writeRows(o);
        writeTail(m, lightCoords, white, o);
        instanceCount++;
    }

    private static void writeRows(int o) {
        instances[o] = TMP_ARR[0];
        instances[o + 1] = TMP_ARR[4];
        instances[o + 2] = TMP_ARR[8];
        instances[o + 3] = TMP_ARR[12];
        instances[o + 4] = TMP_ARR[1];
        instances[o + 5] = TMP_ARR[5];
        instances[o + 6] = TMP_ARR[9];
        instances[o + 7] = TMP_ARR[13];
        instances[o + 8] = TMP_ARR[2];
        instances[o + 9] = TMP_ARR[6];
        instances[o + 10] = TMP_ARR[10];
        instances[o + 11] = TMP_ARR[14];
    }
}
