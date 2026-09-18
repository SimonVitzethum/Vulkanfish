package simon.vulkanfish.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.model.Model;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import simon.vulkanfish.client.gpu.FrameDataCapture;
import simon.vulkanfish.client.gpu.NativePassRunner;
import simon.vulkanfish.client.mixin.ItemStackRenderStateAccessor;

/**
 * Item-Bake fuer die Entity-Pipeline: Ein Fang-Collector laesst Vanilla EINMAL pro Art die Layer
 * expandieren (exakte Transforms, Pose einkopiert); daraus werden Baked-Modelle. Danach pro
 * Entity nur Instanzen (Bob/Spin exakt nach ItemEntityRenderer-Mathe, Count-Layer mit identischer
 * RandomSource-Folge).
 *
 * <p>Arten mit Foil, Tints, transluzenten Quads, exotischen Atlanten (Maps, Skins) oder
 * Special-Renderern (weniger Batches als Layer) bleiben bei Vanilla. Art-Key: Identitaet des
 * ersten sauberen Quads + Layer-Zahl, Kollisionen per Referenz nachgeprueft. Leucht-Umrisse
 * und Glitzern entfallen bewusst (selten am Boden).
 */
public final class ItemBaker {
    private ItemBaker() {
    }

    /** Ein Fang-Auftrag (Pose einkopiert, Quads per Referenz – BakedQuads sind unveraenderlich). */
    private record Captured(Matrix4f pose, net.minecraft.client.resources.model.geometry.ItemQuads quads,
                            int[] tints, ItemStackRenderState.FoilType foil) {
    }

    private static final class CaptureCollector implements SubmitNodeCollector {
        final List<Captured> batches = new ArrayList<>();

        @Override
        public net.minecraft.client.renderer.OrderedSubmitNodeCollector order(int order) {
            return this;
        }

        @Override
        public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords,
                               int overlayCoords, int outlineColor, int[] tintLayers,
                               net.minecraft.client.resources.model.geometry.ItemQuads quads,
                               ItemStackRenderState.FoilType foilType) {
            batches.add(new Captured(new Matrix4f(poseStack.last().pose()), quads, tintLayers, foilType));
        }

        @Override
        public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
        }

        @Override
        public void submitNameTag(PoseStack poseStack, Vec3 nameTagAttachment, int offset, Component name,
                                  boolean seeThrough, int lightCoords, CameraRenderState camera) {
        }

        @Override
        public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence string,
                               boolean dropShadow, net.minecraft.client.gui.Font.DisplayMode displayMode,
                               int lightCoords, int color, int backgroundColor, int outlineColor) {
        }

        @Override
        public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {
        }

        @Override
        public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
        }

        @Override
        public void submitTextBackground(PoseStack poseStack, float x, float y, float width, float height,
                                         int color, net.minecraft.client.gui.Font.DisplayMode displayMode, int outlineColor) {
        }

        @Override
        public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
                                    int lightCoords, int overlayCoords, int tintedColor,
                                    net.minecraft.client.renderer.texture.UvMapping uvMapping, int outlineColor) {
        }

        @Override
        public <S> void submitCrumblingOverlay(Model<? super S> model, S state, PoseStack poseStack,
                                               RenderType renderType, int lightCoords, int overlayCoords, int tintedColor,
                                               ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        }

        @Override
        public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState,
                                      int outlineColor) {
        }

        @Override
        public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts,
                                     int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor) {
        }

        @Override
        public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress,
                                             boolean withOverlay) {
        }

        @Override
        public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color,
                                       float width, boolean afterTerrain) {
        }

        @Override
        public void submitCustomGeometry(PoseStack poseStack, RenderType renderType,
                                         SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {
        }

        @Override
        public void submitQuadParticleGroup(QuadParticleRenderState particles) {
        }

        @Override
        public void submitGizmoPrimitives(net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives.Group group,
                                          CameraRenderState camera, boolean onTop) {
        }
    }

    /** Art-Key -> Modell-Slot (nur saubere Arten). */
    private static final Map<Long, Integer> KIND_TO_SLOT = new java.util.HashMap<>();
    private static final RandomSource RANDOM = RandomSource.create();
    private static final Matrix4f TMP_ITEM = new Matrix4f(); // nur Render-Thread

    private record KindProof(BakedQuad first, int layers) {
    }

    private static final Map<Long, KindProof> KIND_PROOFS = new java.util.HashMap<>();

    /**
     * Item umlenken (Render-Thread): Art bekannt -> Instanzen pro Count-Layer aufzeichnen, true.
     * Sonst genau einmal pro Art backen (Fang-Submit); sauber -> registrieren + aufzeichnen,
     * sonst Art als Vanilla markieren. Alles andere -> false (Vanilla).
     */
    public static boolean divertItem(ItemEntityRenderState state) {
        // Art-Key: Identitaet des ersten Quads von Layer 0 + Layer-Zahl (Nanosekunden).
        var layers = ((ItemStackRenderStateAccessor) (Object) state.item).vulkanfish$layers();
        int active = ((ItemStackRenderStateAccessor) (Object) state.item).vulkanfish$activeLayerCount();
        if (layers == null || active <= 0 || layers[0] == null) return false;
        var layerQuads = ((simon.vulkanfish.client.mixin.ItemLayerQuadsAccessor) (Object) layers[0]).vulkanfish$quads();
        if (layerQuads == null || layerQuads.all().isEmpty()) return false;
        BakedQuad first = layerQuads.all().get(0);
        long key = ((long) System.identityHashCode(first) << 32) | (active & 0xFFFFFFFFL);
        Integer slot = KIND_TO_SLOT.get(key);
        if (slot != null) {
            KindProof proof = KIND_PROOFS.get(key);
            if (proof == null || proof.first() != first || proof.layers() != active) {
                KIND_TO_SLOT.remove(key);
                KIND_PROOFS.remove(key);
            } else {
                return recordItem(state, slot);
            }
        }
        // Neu oder kollidiert: einmal backen (Fang-Submit, selten).
        EntityInstancing.BakedModel baked = bakeKind(state);
        if (baked == null) return false;
        int newSlot = EntityInstancing.registerBaked(baked);
        KIND_TO_SLOT.put(key, newSlot);
        KIND_PROOFS.put(key, new KindProof(first, active));
        return recordItem(state, newSlot);
    }

    /** Fang-Submit aller Layer, Pruefungen, Decode in BakedModel (Modellraum, Layer-Transforms drin). */
    private static EntityInstancing.BakedModel bakeKind(ItemEntityRenderState state) {
        try {
            var layers = ((ItemStackRenderStateAccessor) (Object) state.item).vulkanfish$layers();
            int active = ((ItemStackRenderStateAccessor) (Object) state.item).vulkanfish$activeLayerCount();
            CaptureCollector capture = new CaptureCollector();
            PoseStack pose = new PoseStack();
            state.item.submit(pose, capture, state.lightCoords, 0, 0);
            if (capture.batches.size() < active) return null; // Special-Renderer-Anteil (unsichtbar) -> Vanilla
            List<Float> vp = new ArrayList<>();
            List<Integer> ip = new ArrayList<>();
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            boolean itemsAtlas = false;
            boolean blocksAtlas = false;
            for (Captured b : capture.batches) {
                if (b.foil() != ItemStackRenderState.FoilType.NONE) return null;
                if (b.tints() != null && b.tints().length > 0) return null;
                if (!b.quads().translucent().isEmpty()) return null; // transluzent -> Vanilla
                float[] m = mojangToJoml(b.pose());
                for (BakedQuad q : b.quads().all()) {
                    int flags = q.materialInfo().flags();
                    if ((flags & BakedQuad.FLAG_TRANSLUCENT) != 0) return null;
                    var sprite = q.materialInfo().sprite();
                    var atlas = sprite != null ? sprite.atlasLocation() : null;
                    if (atlas != null) {
                        if (atlas.equals(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_ITEMS)) {
                            itemsAtlas = true;
                        } else if (atlas.equals(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS)) {
                            blocksAtlas = true;
                        } else {
                            return null; // exotisch (Maps, Skins) -> Vanilla
                        }
                    }
                    float[] px = new float[4], py = new float[4], pz = new float[4], uu = new float[4], vv = new float[4];
                    for (int v = 0; v < 4; v++) {
                        var p = q.position(v);
                        // Pose (Layer-Transforms, Vanilla-exakt) auf CPU einmalig anwenden
                        float x = m[0] * p.x() + m[4] * p.y() + m[8] * p.z() + m[12];
                        float y = m[1] * p.x() + m[5] * p.y() + m[9] * p.z() + m[13];
                        float z = m[2] * p.x() + m[6] * p.y() + m[10] * p.z() + m[14];
                        px[v] = x;
                        py[v] = y;
                        pz[v] = z;
                        long packed = q.packedUV(v);
                        uu[v] = net.minecraft.client.model.geom.builders.UVPair.unpackU(packed);
                        vv[v] = net.minecraft.client.model.geom.builders.UVPair.unpackV(packed);
                    }
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
            }
            if (ip.isEmpty()) return null;
            float[] verts = new float[vp.size()];
            for (int i = 0; i < verts.length; i++) verts[i] = vp.get(i);
            int[] indices = new int[ip.size()];
            for (int i = 0; i < indices.length; i++) indices[i] = ip.get(i);
            return new EntityInstancing.BakedModel(verts, indices, false,
                    new float[]{minX, minY, minZ, maxX, maxY, maxZ}, -1, itemsAtlas);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Mojang-Matrix (column-major Felder) -> JOML-Array (column-major). */
    private static float[] mojangToJoml(org.joml.Matrix4f m) {
        float[] a = new float[16];
        a[0] = m.m00();
        a[1] = m.m01();
        a[2] = m.m02();
        a[3] = m.m03();
        a[4] = m.m10();
        a[5] = m.m11();
        a[6] = m.m12();
        a[7] = m.m13();
        a[8] = m.m20();
        a[9] = m.m21();
        a[10] = m.m22();
        a[11] = m.m23();
        a[12] = m.m30();
        a[13] = m.m31();
        a[14] = m.m32();
        a[15] = m.m33();
        return a;
    }

    /**
     * Instanzen pro Count-Layer (Bob/Spin + Offsets exakt nach ItemEntityRenderer, RandomSource-
     * Folge identisch). Render-Thread.
     */
    private static boolean recordItem(ItemEntityRenderState state, int slot) {
        var d = FrameDataCapture.last;
        if (d == null || !NativePassRunner.entityReady()) return false;
        var models = EntityInstancing.models();
        if (slot < 0 || slot >= models.size()) return false;
        EntityInstancing.BakedModel model = models.get(slot);
        int amount = state.count;
        if (amount <= 0) return true; // nichts zu zeichnen, aber erfolgreich umgelenkt
        if (!EntityInstancing.fits(amount)) return false; // voll -> ganz Vanilla (keine halben Layer)
        float minOffsetY = -model.aabb()[1] + 0.0625f;
        float bob = Mth.sin(state.ageInTicks / 10.0f + state.bobOffset) * 0.1f + 0.1f;
        float spin = ItemEntity.getSpin(state.ageInTicks, state.bobOffset);
        float modelDepth = model.aabb()[5] - model.aabb()[2];
        boolean flat = modelDepth <= 0.0625f;
        RANDOM.setSeed(state.seed);
        float baseX = (float) (state.x - d.originX());
        float baseY = (float) (state.y - d.originY());
        float baseZ = (float) (state.z - d.originZ());
        for (int i = 0; i < amount; i++) {
            TMP_ITEM.identity().translate(baseX, baseY + bob + minOffsetY, baseZ)
                    .rotateY(spin);
            if (flat) {
                float offsetZ = modelDepth * 1.5f;
                TMP_ITEM.translate(0.0f, 0.0f, -(offsetZ * (amount - 1) / 2.0f) + offsetZ * i);
                if (i > 0) {
                    float xo = (RANDOM.nextFloat() * 2.0f - 1.0f) * 0.15f * 0.5f;
                    float yo = (RANDOM.nextFloat() * 2.0f - 1.0f) * 0.15f * 0.5f;
                    // Random-Folge exakt wie Vanilla (2 Calls pro Layer ab 1)
                    TMP_ITEM.translate(xo, yo, 0.0f);
                }
            } else if (i > 0) {
                float xo = (RANDOM.nextFloat() * 2.0f - 1.0f) * 0.15f;
                float yo = (RANDOM.nextFloat() * 2.0f - 1.0f) * 0.15f;
                float zo = (RANDOM.nextFloat() * 2.0f - 1.0f) * 0.15f;
                TMP_ITEM.translate(xo, yo, zo);
            }
            if (!EntityInstancing.recordMatrix(model, TMP_ITEM, state.lightCoords, 0.0f)) return false;
        }
        return true;
    }
}
