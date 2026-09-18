package simon.vulkanfish.client.gpu;

import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3fc;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Meshet eine Section (Worker-Thread) in Meshlets – mit VANILLAS
 * ModelBlockRenderer/FluidRenderer: echte Atlas-UVs, Biome-Tint, Smooth-AO,
 * Face-Shading und Licht kommen 1:1 aus Vanillas QuadInstance.
 *
 * <p>SOLID/CUTOUT gehen in den G-Buffer; Wasser und transluzente Bloecke (Glas,
 * Eis, Slime) werden als eigene Meshlet-Klassen fuer den Transluzenz-Pass gebaut.
 * selbst – gegen unsere Tiefe, die ins Main-Target kopiert wird.
 *
 * <p>Meshlets: 16 Quads (64 Verts / 32 Tris). Quads werden nach Face-Richtung
 * gruppiert und entlang der Ebene sortiert, damit der Cull-Shader ganze
 * Meshlets per Backface-Ebenentest verwerfen kann.
 */
final class SectionMesher {
    static final int QUADS_PER_MESHLET = 16;
    static final int VERTEX_BYTES = 16;
    static final int MESHLET_BYTES = 64;
    private static final int WORDS_PER_QUAD = 4 * 4; // 4 Vertices x 4 Worte (16 Byte, siehe TerrainVertex)
    private static final int[] ORDER_KEEP = {0, 1, 2, 3};
    private static final int[] ORDER_FLIP = {0, 3, 2, 1}; // kehrt die Windung um
    private static final int AXIS_NONE = 6;
    private static final int AXIS_WATER = 7;       // eigene Meshlets fuer den Wasser-Pass
    private static final int AXIS_TRANSLUCENT = 8; // Glas/Eis (Blending-Pass)
    static final byte KIND_OPAQUE = 0;
    static final byte KIND_WATER = 1;
    static final byte KIND_TRANSLUCENT = 2;
    // Material-IDs (identisch zu look.slang), 4 Bit + 4 Bit Emission im Vertex-Alpha
    static final int MAT_DEFAULT = 0;
    static final int MAT_LEAVES = 1;
    static final int MAT_PLANT = 2;
    static final int MAT_PLANT_TOP = 3;
    static final int MAT_VINE = 4;
    static final int MAT_ORE = 5;
    static final int MAT_LAVA = 6;
    static final int MAT_WATER = 7;
    static final int MAT_GLASS = 8;
    static final int MAT_ICE = 9;
    // Raytracing-Kategorien (hoechster Sortierschluessel): massiv und Cutout landen als eigene
    // zusammenhaengende Quad-Bereiche am Anfang der Section -> zwei BLAS-Geometrien ohne Umkopieren
    static final int CAT_SOLID = 0;   // BLAS, opak (kein Any-Hit)
    static final int CAT_CUTOUT = 1;  // BLAS, Alpha-Test im Strahl (Blaetter, Gitter, Tueren ...)
    static final int CAT_NO_RT = 2;   // Pflanzen/Ranken (Rauschen) + leuchtende Bloecke (Lichtquelle selbst)
    static final int CAT_WATER = 3;
    static final int CAT_TRANS = 4;
    static final int LIGHT_INTS = 4;  // pro Lichtquelle: x, y, z (float-Bits, Welt), gepackt (Emission, Radius, Farbe)
    private static final int MIN_RT_EMISSION = 5;
    private static final ConcurrentHashMap<BlockState, Integer> MATERIALS = new ConcurrentHashMap<>();
    private static final float[][] AXES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** Ergebnis in relativen Offsets; der Streamer patcht Start-Indices nach der Allokation. */
    record MeshResult(long key, int generation, int version, int quadCount, int meshletCount,
                      ByteBuffer vertices, ByteBuffer tris, int[] meshletQuadStart, int[] meshletQuadCount,
                      float[] meshletBounds, float[] meshletPlanes, byte[] meshletKind,
                      int solidQuads, int cutoutQuads, long rtHash, int[] lights) {
        int vertexCount() {
            return quadCount * 4;
        }

        int triCount() {
            return quadCount * 2;
        }
    }

    private final ModelBlockRenderer blockRenderer;
    private final FluidRenderer fluidRenderer;
    private final BlockStateModelSet modelSet;
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
    // Quad-Sammelpuffer (wiederverwendet pro Thread)
    private int[] words = new int[WORDS_PER_QUAD * 1024];
    private float[] pos = new float[12 * 1024]; // Welt-Positionen fuer Bounds/Ebenen
    private int[] axisKey = new int[1024];
    private float[] planeKey = new float[1024];
    private byte[] category = new byte[1024];
    // Sortier-Scratch (wiederverwendet pro Thread, nur transient in build()):
    // primitiver int-Index statt Integer[] + Comparator-Lambda (kein Boxing, kein GC-Druck).
    private int[] sortIdx = new int[1024];
    private int[] mStartTmp = new int[72];
    private int[] mCountTmp = new int[72];
    private final int[] sortStack = new int[128]; // Quicksort-Tiefe log n, 128 = Reserve bis ~1 Mio. Quads
    private int[] lights = new int[64];
    private int lightCount;
    private int quads;
    private float originX;
    private float originY;
    private float originZ;
    private final FluidCapture fluidCapture = new FluidCapture();
    private CardinalLighting cardinal = CardinalLighting.DEFAULT;
    private int blockMaterial;   // Material|Emission<<4 des aktuellen Blocks
    private boolean currentIsIce;
    private boolean translucentQuad;
    private boolean plantWaves;  // obere Vertices dieses Pflanzenblocks wehen
    private BlockState currentState;
    private RenderSectionRegion currentRegion;
    private final BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();

    SectionMesher(BlockStateModelSet modelSet, FluidStateModelSet fluidSet, BlockColors colors, boolean ambientOcclusion) {
        this.modelSet = modelSet;
        this.blockRenderer = new ModelBlockRenderer(ambientOcclusion, true, colors);
        this.fluidRenderer = new FluidRenderer(fluidSet);
    }

    BlockStateModelSet modelSet() {
        return modelSet;
    }

    MeshResult mesh(RenderSectionRegion region, long key, int generation, int version) {
        quads = 0;
        lightCount = 0;
        int sx = SectionPos.x(key);
        int sy = SectionPos.y(key);
        int sz = SectionPos.z(key);
        originX = sx << 4;
        originY = sy << 4;
        originZ = sz << 4;
        cardinal = region.cardinalLighting();
        // Alle Fluids selbst: Lava opak im G-Buffer, Wasser als eigene Meshlets (Wasser-Pass)
        FluidRenderer.Output fluidOut = layer -> fluidCapture.begin();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    scratch.set((sx << 4) + x, (sy << 4) + y, (sz << 4) + z);
                    BlockState state = region.getBlockState(scratch);
                    if (state.isAir()) continue;
                    FluidState fluid = state.getFluidState();
                    if (!fluid.isEmpty()) {
                        currentRegion = region;
                        fluidRenderer.tesselate(region, scratch, fluidOut, state, fluid);
                        boolean water = fluid.getType() == Fluids.WATER || fluid.getType() == Fluids.FLOWING_WATER;
                        fluidCapture.flush(this, water ? MAT_WATER : (MAT_LAVA | (15 << 4)));
                    }
                    int emission = state.getLightEmission();
                    if (emission >= MIN_RT_EMISSION && !(state.getBlock() instanceof LiquidBlock)) addLight(state, emission);
                    if (state.getRenderShape() == RenderShape.MODEL) {
                        currentState = state;
                        currentRegion = region;
                        blockMaterial = material(state);
                        currentIsIce = state.getBlock() instanceof net.minecraft.world.level.block.IceBlock
                                || state.getBlock() == net.minecraft.world.level.block.Blocks.PACKED_ICE
                                || state.getBlock() == net.minecraft.world.level.block.Blocks.BLUE_ICE;
                        plantWaves = (blockMaterial & 0xF) == MAT_PLANT && waves(state);
                        blockRenderer.tesselateBlock(this::putBlockQuad, x, y, z, region, scratch,
                                state, modelSet.get(state), state.getSeed(scratch));
                    }
                }
            }
        }
        return build(key, generation, version);
    }

    /** Material+Emission pro BlockState (einmal berechnet, threadsicher gecacht). */
    private static int material(BlockState state) {
        return MATERIALS.computeIfAbsent(state, st -> {
            var block = st.getBlock();
            int mat = MAT_DEFAULT;
            if (block instanceof LeavesBlock) {
                mat = MAT_LEAVES;
            } else if (block instanceof VineBlock) {
                mat = MAT_VINE;
            } else if (block instanceof VegetationBlock) {
                mat = MAT_PLANT;
            } else if (BuiltInRegistries.BLOCK.getKey(block).getPath().endsWith("_ore")) {
                mat = MAT_ORE;
            }
            return mat | (Math.min(st.getLightEmission(), 15) << 4);
        });
    }

    /**
     * Lichtquelle fuer die Raytracing-Schatten: Welt-Position des Leuchtpunkts (Flamme statt
     * Blockmitte), Radius fuer weiche Schatten, Farbe nach Blocktyp.
     */
    private void addLight(BlockState state, int emission) {
        var block = state.getBlock();
        float ox = 0.5f, oy = 0.5f, oz = 0.5f, radius = 0.45f;
        if (block instanceof net.minecraft.world.level.block.BaseTorchBlock
                || block instanceof net.minecraft.world.level.block.RedstoneTorchBlock) {
            radius = 0.06f;
            oy = 0.7f;
            if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                // Wandfackel: Flamme wie Vanillas Partikel 0.27 Richtung Wand, etwas hoeher
                var opp = state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
                ox += 0.27f * opp.getStepX();
                oz += 0.27f * opp.getStepZ();
                oy = 0.92f;
            }
        } else if (block instanceof net.minecraft.world.level.block.LanternBlock) {
            radius = 0.12f;
            oy = state.hasProperty(BlockStateProperties.HANGING) && state.getValue(BlockStateProperties.HANGING) ? 0.4f : 0.3f;
        } else if (block instanceof net.minecraft.world.level.block.AbstractCandleBlock) {
            radius = 0.08f;
            oy = 0.45f;
        } else if (block instanceof net.minecraft.world.level.block.CampfireBlock
                || block instanceof net.minecraft.world.level.block.BaseFireBlock) {
            radius = 0.3f;
            oy = 0.4f;
        } else if (!state.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
            radius = 0.2f; // kleine Leuchtobjekte (Endstab, Amethyst, Seegurken ...)
        }
        if (lightCount * LIGHT_INTS + LIGHT_INTS > lights.length) lights = Arrays.copyOf(lights, lights.length * 2);
        int o = lightCount++ * LIGHT_INTS;
        // section-lokal (0..16): RtAccel rechnet sie je Frame in den kamerarelativen Raum um
        lights[o] = Float.floatToRawIntBits((scratch.getX() & 15) + ox);
        lights[o + 1] = Float.floatToRawIntBits((scratch.getY() & 15) + oy);
        lights[o + 2] = Float.floatToRawIntBits((scratch.getZ() & 15) + oz);
        int rCode = Math.min(15, Math.round(radius * 30f)); // 0..0.5 Bloecke
        lights[o + 3] = Math.min(emission, 15) | (rCode << 4) | (lightColor(block) << 8);
    }

    private static final ConcurrentHashMap<net.minecraft.world.level.block.Block, Integer> LIGHT_COLORS = new ConcurrentHashMap<>();

    /** Lichtfarbe (RGB8, linear-ish) nach Blocktyp; eigene Palette, warm als Standard. */
    private static int lightColor(net.minecraft.world.level.block.Block block) {
        return LIGHT_COLORS.computeIfAbsent(block, b -> {
            String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
            float[] c;
            if (id.contains("soul")) c = new float[]{0.25f, 0.75f, 1.0f};
            else if (id.startsWith("redstone")) c = id.contains("lamp") ? new float[]{1.0f, 0.72f, 0.42f} : new float[]{1.0f, 0.12f, 0.06f};
            else if (id.contains("copper")) c = new float[]{0.45f, 1.0f, 0.55f};
            else if (id.contains("amethyst") || id.contains("crying_obsidian") || id.contains("respawn_anchor")
                    || id.contains("portal") || id.equals("enchanting_table") || id.contains("ender_chest")) c = new float[]{0.7f, 0.35f, 1.0f};
            else if (id.contains("sea_lantern") || id.contains("beacon") || id.contains("conduit")) c = new float[]{0.65f, 0.9f, 1.0f};
            else if (id.equals("end_rod")) c = new float[]{1.0f, 0.95f, 0.92f};
            else if (id.equals("verdant_froglight")) c = new float[]{0.55f, 1.0f, 0.45f};
            else if (id.equals("pearlescent_froglight")) c = new float[]{1.0f, 0.6f, 0.85f};
            else if (id.equals("ochre_froglight")) c = new float[]{1.0f, 0.85f, 0.4f};
            else if (id.contains("sculk") || id.contains("glow_lichen")) c = new float[]{0.4f, 0.9f, 0.85f};
            else if (id.equals("glowstone") || id.contains("shroomlight") || id.contains("cave_vines")) c = new float[]{1.0f, 0.74f, 0.4f};
            else if (id.contains("magma")) c = new float[]{1.0f, 0.35f, 0.08f};
            else if (id.equals("sea_pickle")) c = new float[]{0.55f, 0.95f, 0.7f};
            else c = new float[]{1.0f, 0.58f, 0.28f}; // Fackel, Laterne, Feuer, Kerze, Ofen ...
            return Math.round(c[0] * 255f) | (Math.round(c[1] * 255f) << 8) | (Math.round(c[2] * 255f) << 16);
        });
    }

    /** Doppelpflanzen: nur die obere Haelfte weht (sonst reisst die Naht in der Mitte). */
    private static boolean waves(BlockState state) {
        if (state.getBlock() instanceof DoublePlantBlock && state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
        }
        return true;
    }

    // ---- Quad-Eingang ----

    private void putBlockQuad(float x, float y, float z, BakedQuad quad, QuadInstance inst) {
        boolean translucent = quad.materialInfo().layer() == ChunkSectionLayer.TRANSLUCENT;
        if (translucent && isSharedTranslucentFace(quad)) return;
        ensureCapacity();
        Vector3fc p0 = quad.position(0);
        Vector3fc p1 = quad.position(1);
        Vector3fc p2 = quad.position(2);
        Vector3fc p3 = quad.position(3);
        float[] n = faceNormal(p0.x(), p0.y(), p0.z(), p1.x(), p1.y(), p1.z(),
                p2.x(), p2.y(), p2.z(), p3.x(), p3.y(), p3.z());
        // Der Mesh-Shader leitet die Normale aus der Windung ab -> an Face-Richtung ausrichten
        var d = quad.direction().getUnitVec3i();
        int[] order = ORDER_KEEP;
        if (n[0] * d.getX() + n[1] * d.getY() + n[2] * d.getZ() < 0) {
            order = ORDER_FLIP;
            n[0] = -n[0];
            n[1] = -n[1];
            n[2] = -n[2];
        }
        int emission = quad.materialInfo().lightEmission();
        // Vanillas feste Seiten-Schattierung herausrechnen: das Deferred-Licht schattiert selbst
        // (26.3: shadeDirectionOverride statt shade()-Flag; immer richtungsabhaengig)
        net.minecraft.core.Direction shadeDir = quad.materialInfo().shadeDirectionOverride();
        float shade = cardinal.byFace(shadeDir != null ? shadeDir : quad.direction());
        float unshade = shade > 0.05f ? 1.0f / shade : 1.0f;
        int emissionBits = Math.max(blockMaterial >> 4, Math.min(emission, 15)) << 4;
        for (int slot = 0; slot < 4; slot++) {
            int v = order[slot];
            Vector3fc p = quad.position(v);
            long uv = quad.packedUV(v);
            int mat = translucent ? (currentIsIce ? MAT_ICE : MAT_GLASS) : blockMaterial & 0xF;
            if (mat == MAT_PLANT && plantWaves && p.y() > 0.3f) mat = MAT_PLANT_TOP;
            writeVertex(slot, x + p.x(), y + p.y(), z + p.z(), UVPair.unpackU(uv), UVPair.unpackV(uv),
                    scaleRgb(inst.getColor(v), unshade), inst.getLightCoordsWithEmission(v, emission), mat | emissionBits);
        }
        translucentQuad = translucent;
        int cat;
        int m = blockMaterial & 0xF;
        if (translucent) cat = CAT_TRANS;
        else if ((blockMaterial >> 4) > 0 || emission > 0 || m == MAT_PLANT || m == MAT_VINE) cat = CAT_NO_RT;
        else cat = quad.materialInfo().layer() == ChunkSectionLayer.SOLID ? CAT_SOLID : CAT_CUTOUT;
        category[quads] = (byte) cat;
        classify(n);
        translucentQuad = false;
    }

    /**
     * Grenzflaeche zwischen zwei VERSCHIEDENEN transluzenten Vollbloecken (Glas auf Eis,
     * rotes neben blauem Glas ...): Vanilla laesst beide deckungsgleichen Flaechen stehen
     * (skipRendering greift nur bei gleichem Block) -> zwei Schichten in exakt derselben
     * Ebene. Physikalisch gibt es dort nur EINE Grenzflaeche: wir behalten die Flaeche des
     * Blocks auf der negativen Seite (dessen UP/SOUTH/EAST) und verwerfen die Gegenflaeche.
     */
    private boolean isSharedTranslucentFace(BakedQuad quad) {
        if (!(currentState.getBlock() instanceof HalfTransparentBlock)) return false;
        net.minecraft.core.Direction dir = quad.direction();
        if (dir.getAxisDirection() != net.minecraft.core.Direction.AxisDirection.NEGATIVE) return false;
        int axis = dir.getAxis().ordinal(); // X=0, Y=1, Z=2
        for (int v = 0; v < 4; v++) {
            if (Math.abs(quad.position(v).get(axis)) > 1e-4f) return false; // nicht auf der Blockgrenze
        }
        neighborPos.setWithOffset(scratch, dir);
        BlockState neighbor = currentRegion.getBlockState(neighborPos);
        return neighbor.getBlock() instanceof HalfTransparentBlock && neighbor.getBlock() != currentState.getBlock();
    }

    /** Vier bereits gesammelte Fluid-Vertices (section-relative Positionen) als Quad uebernehmen. */
    void putFluidQuad(float[] v, int[] color, int[] light, int material) {
        ensureCapacity();
        for (int i = 0; i < 4; i++) {
            int o = i * 5;
            writeVertex(i, v[o], v[o + 1], v[o + 2], v[o + 3], v[o + 4], color[i], light[i], material);
        }
        // Fluid-Oberseiten haben ggf. eine Rueckseite -> kein Ebenentest; Wasser separat gruppiert
        boolean water = (material & 0xF) == MAT_WATER;
        if (water && touchesSolid(v)) {
            // Grenzflaeche Wasser | Eis/Glas/Block: fast gleiche Brechzahl -> keine Spiegelung (Bit 4)
            for (int i = 0; i < 4; i++) words[quads * WORDS_PER_QUAD + i * 4 + 3] |= WATER_INTERFACE << 24;
        }
        axisKey[quads] = water ? AXIS_WATER : AXIS_NONE;
        category[quads] = (byte) (water ? CAT_WATER : CAT_NO_RT); // Lava leuchtet selbst
        planeKey[quads] = 0;
        quads++;
    }

    /** Wasser-Emission ist immer 0: Bit 4 des Materialbytes markiert Wasser an einem festen Nachbarn. */
    static final int WATER_INTERFACE = 16;

    /**
     * Liegt die Wasserflaeche an einem Block, der Bewegung blockiert (Eis, Glas, Stufen ...)?
     * Oberseite: Block darueber; Seiten: Nachbar in Normalenrichtung. Offene Flaechen (Luft,
     * Pflanzen) bleiben normale Wasseroberflaeche mit Spiegelung.
     */
    private boolean touchesSolid(float[] v) {
        float ax = v[5] - v[0], ay = v[6] - v[1], az = v[7] - v[2];
        float bx = v[10] - v[0], by = v[11] - v[1], bz = v[12] - v[2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float anx = Math.abs(nx), any = Math.abs(ny), anz = Math.abs(nz);
        net.minecraft.core.Direction dir;
        if (any >= anx && any >= anz) {
            if (ny < 0) return false; // Rueckseite der Oberflaeche (Blick von unten)
            dir = net.minecraft.core.Direction.UP;
        } else if (anx >= anz) {
            dir = nx > 0 ? net.minecraft.core.Direction.EAST : net.minecraft.core.Direction.WEST;
        } else {
            dir = nz > 0 ? net.minecraft.core.Direction.SOUTH : net.minecraft.core.Direction.NORTH;
        }
        if (currentRegion == null) return false;
        neighborPos.setWithOffset(scratch, dir);
        BlockState neighbor = currentRegion.getBlockState(neighborPos);
        // 26.3: blocksMotion() entfernt (war isSolid + Ausnahmen fuer nicht-solide Bloecke = isSolid)
        return neighbor.getFluidState().isEmpty() && neighbor.isSolid();
    }

    private void classify(float[] n) {
        int axis = AXIS_NONE;
        for (int a = 0; a < 6; a++) {
            if (n[0] * AXES[a][0] + n[1] * AXES[a][1] + n[2] * AXES[a][2] > 0.999f) {
                axis = a;
                break;
            }
        }
        if (translucentQuad) axis = AXIS_TRANSLUCENT; // eigener Pass, kein Ebenentest (von beiden Seiten sichtbar)
        axisKey[quads] = axis;
        int pb = quads * 12;
        planeKey[quads] = axis >= AXIS_NONE ? 0
                : pos[pb] * AXES[axis][0] + pos[pb + 1] * AXES[axis][1] + pos[pb + 2] * AXES[axis][2];
        quads++;
    }

    /** Vertex {@code slot} des aktuellen Quads; rx/ry/rz section-relativ. */
    private static int scaleRgb(int argb, float k) {
        if (k == 1.0f) return argb;
        int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * k));
        int g = Math.min(255, Math.round(((argb >> 8) & 0xFF) * k));
        int b = Math.min(255, Math.round((argb & 0xFF) * k));
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private void writeVertex(int slot, float rx, float ry, float rz, float u, float v, int argb, int lightCoords, int material) {
        int pb = quads * 12 + slot * 3;
        // section-lokal: Meshlet-Kugeln und Ebenen-Cutoffs relativ zum Section-Ursprung (die GPU
        // rechnet sie gegen den Render-Ursprung um -> genau auch bei riesigen Koordinaten)
        pos[pb] = rx;
        pos[pb + 1] = ry;
        pos[pb + 2] = rz;
        int w = quads * WORDS_PER_QUAD + slot * 4;
        words[w] = quantize(rx) | (quantize(ry) << 16);
        int block = Math.min(lightCoords & 0xFFFF, 255);
        int sky = Math.min((lightCoords >>> 16) & 0xFFFF, 255);
        words[w + 1] = quantize(rz) | (block << 16) | (sky << 24);
        words[w + 2] = unorm16(u) | (unorm16(v) << 16);
        // ARGB -> RGBA8 (r im niedrigsten Byte), Alpha = Material | Emission << 4
        words[w + 3] = ((argb >> 16) & 0xFF) | (argb & 0xFF00) | ((argb & 0xFF) << 16) | ((material & 0xFF) << 24);
    }

    /** Section-relative Koordinate -> u16 ((rel + 8) * 2048), siehe tvPos() im Shader. */
    private static int quantize(float rel) {
        return Math.max(0, Math.min(65535, Math.round((rel + 8f) * 2048f)));
    }

    private static int unorm16(float f) {
        return Math.max(0, Math.min(65535, Math.round(f * 65535f)));
    }

    private static float[] faceNormal(float x0, float y0, float z0, float x1, float y1, float z1,
                                      float x2, float y2, float z2, float x3, float y3, float z3) {
        float ax = x1 - x0, ay = y1 - y0, az = z1 - z0;
        float bx = x2 - x0, by = y2 - y0, bz = z2 - z0;
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6f) { // degeneriertes erstes Dreieck -> zweites nehmen
            ax = x3 - x0; ay = y3 - y0; az = z3 - z0;
            nx = by * az - bz * ay;
            ny = bz * ax - bx * az;
            nz = bx * ay - by * ax;
            len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        }
        if (len < 1e-6f) return new float[]{0, 1, 0};
        return new float[]{nx / len, ny / len, nz / len};
    }

    private void ensureCapacity() {
        if (quads < axisKey.length) return;
        int n = axisKey.length * 2;
        words = Arrays.copyOf(words, n * WORDS_PER_QUAD);
        pos = Arrays.copyOf(pos, n * 12);
        axisKey = Arrays.copyOf(axisKey, n);
        planeKey = Arrays.copyOf(planeKey, n);
        category = Arrays.copyOf(category, n);
    }

    // ---- Primitiver stabiler Sort (Kategorie, Achse, Ebene, Index) ----

    /** Vergleich zweier Quad-Indices in der Sortierordnung. */
    private int cmpQuads(int a, int b) {
        int c = Integer.compare(category[a] & 0xFF, category[b] & 0xFF);
        if (c != 0) return c;
        c = Integer.compare(axisKey[a], axisKey[b]);
        if (c != 0) return c;
        c = Float.compare(planeKey[a], planeKey[b]);
        return c != 0 ? c : Integer.compare(a, b);
    }

    /** Iterativer Quicksort mit Insertion-Fallback (kein Boxing, kein Comparator). */
    private void sortQuads(int[] order, int n) {
        // Expliziter Stack statt Rekursion (Tiefe log n, Grow-Schutz inklusive)
        int[] stack = sortStack;
        int sp = 0;
        stack[sp++] = 0;
        stack[sp++] = n - 1;
        while (sp > 0) {
            int hi = stack[--sp], lo = stack[--sp];
            int size = hi - lo + 1;
            if (size < 16) {
                for (int i = lo + 1; i <= hi; i++) {
                    int v = order[i], j = i - 1;
                    while (j >= lo && cmpQuads(order[j], v) > 0) {
                        order[j + 1] = order[j];
                        j--;
                    }
                    order[j + 1] = v;
                }
                continue;
            }
            int mid = lo + (size >> 1);
            // Median-of-3 auf lo/mid/hi
            if (cmpQuads(order[lo], order[mid]) > 0) swapOrder(order, lo, mid);
            if (cmpQuads(order[mid], order[hi]) > 0) swapOrder(order, mid, hi);
            if (cmpQuads(order[lo], order[mid]) > 0) swapOrder(order, lo, mid);
            int pivot = order[mid];
            int i = lo, j = hi;
            while (true) {
                while (cmpQuads(order[i], pivot) < 0) i++;
                while (cmpQuads(pivot, order[j]) < 0) j--;
                if (i >= j) break;
                swapOrder(order, i++, j--);
            }
            // Groesseres Intervall zuerst auf den Stack (Stack-Tiefe bleibt klein)
            if (j - lo > hi - i) {
                if (lo < j) {
                    stack[sp++] = lo;
                    stack[sp++] = j;
                }
                if (i < hi) {
                    stack[sp++] = i;
                    stack[sp++] = hi;
                }
            } else {
                if (i < hi) {
                    stack[sp++] = i;
                    stack[sp++] = hi;
                }
                if (lo < j) {
                    stack[sp++] = lo;
                    stack[sp++] = j;
                }
            }
        }
    }

    private static void swapOrder(int[] order, int a, int b) {
        int t = order[a];
        order[a] = order[b];
        order[b] = t;
    }

    // ---- Meshlet-Bau ----

    private MeshResult build(long key, int generation, int version) {
        // Reihenfolge: nach Kategorie, Achse, Ebene (stabil per Originalindex).
        // Primitiver Quicksort auf wiederverwendetem int[]-Scratch: kein Boxing
        // (Integer[] + Lambda erzeugte pro Section Müll im Worker-Pool).
        if (sortIdx.length < quads) sortIdx = new int[Math.max(quads, sortIdx.length * 2)];
        for (int i = 0; i < quads; i++) sortIdx[i] = i;
        if (quads > 1) sortQuads(sortIdx, quads);
        final int[] order = sortIdx;

        // Meshlets duerfen keine Achsen mischen (Ebenentest waere sonst falsch)
        // (transiente Scratch-Arrays, am Ende kopiert der MeshResult nur den belegten Teil)
        int need = quads / QUADS_PER_MESHLET + 8;
        if (mStartTmp.length < need) {
            int n = Math.max(need, mStartTmp.length * 2);
            mStartTmp = new int[n];
            mCountTmp = new int[n];
        }
        int[] mStart = mStartTmp;
        int[] mCount = mCountTmp;
        int meshlets = 0;
        int i = 0;
        while (i < quads) {
            int axis = axisKey[order[i]];
            int cat = category[order[i]];
            int n = 0;
            while (i + n < quads && n < QUADS_PER_MESHLET && axisKey[order[i + n]] == axis
                    && category[order[i + n]] == cat) n++;
            if (meshlets == mStart.length) {
                int grown = meshlets * 2;
                mStart = mStartTmp = Arrays.copyOf(mStart, grown);
                mCount = mCountTmp = Arrays.copyOf(mCount, grown);
            }
            mStart[meshlets] = i;
            mCount[meshlets] = n;
            meshlets++;
            i += n;
        }

        ByteBuffer vb = ByteBuffer.allocate(quads * 4 * VERTEX_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer tb = ByteBuffer.allocate(quads * 2 * 4).order(ByteOrder.LITTLE_ENDIAN);
        float[] bounds = new float[meshlets * 4];
        float[] planes = new float[meshlets * 4];
        byte[] kind = new byte[meshlets];
        for (int m = 0; m < meshlets; m++) {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            float cutoff = Float.MAX_VALUE;
            boolean foliage = false;
            float windMax = 0.0f;
            int axis = axisKey[order[mStart[m]]];
            for (int q = 0; q < mCount[m]; q++) {
                int src = order[mStart[m] + q];
                int base = src * WORDS_PER_QUAD;
                // Material steht im Alpha-Byte (low 4 Bit, siehe writeVertex): 1..4 = Laub/Pflanzen/Ranken
                int mat = (words[base + 3] >>> 24) & 0xF;
                if (mat >= MAT_LEAVES && mat <= MAT_VINE) foliage = true;
                // Wind-Amplitude je Material (terrain_common.slang): Oberkanten 0.10, Laub/Ranken
                // 0.035, mal Regenfaktor bis 2.5, Hüllkurve ~1.6x -> Puffer 4x für den Ebenen-Test
                if (mat == MAT_PLANT_TOP) windMax = Math.max(windMax, 0.10f);
                else if (mat == MAT_LEAVES || mat == MAT_VINE) windMax = Math.max(windMax, 0.035f);
                for (int w = 0; w < WORDS_PER_QUAD; w++) vb.putInt(words[base + w]);
                for (int v = 0; v < 4; v++) {
                    float x = pos[src * 12 + v * 3];
                    float y = pos[src * 12 + v * 3 + 1];
                    float z = pos[src * 12 + v * 3 + 2];
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                    minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                }
                if (axis < AXIS_NONE) cutoff = Math.min(cutoff, planeKey[src]);
                // lokale Indices: Quad q belegt Vertices 4q..4q+3
                int l = q * 4;
                tb.putInt(l | ((l + 1) << 8) | ((l + 2) << 16));
                tb.putInt(l | ((l + 2) << 8) | ((l + 3) << 16));
            }
            float hx = (maxX - minX) * 0.5f, hy = (maxY - minY) * 0.5f, hz = (maxZ - minZ) * 0.5f;
            bounds[m * 4] = minX + hx;
            bounds[m * 4 + 1] = minY + hy;
            bounds[m * 4 + 2] = minZ + hz;
            // Wind (terrain_common.slang: Laub 0.035, Pflanzenoberkanten 0.10, mal Regenfaktor bis
            // 2.5) schwingt Vertices aus statischen Cull-Kugeln -> zu knapp = Popping an Kanten.
            // Nur Laub-Meshlets weiter machen (Cull-Verlust anderswo null).
            bounds[m * 4 + 3] = (float) Math.sqrt(hx * hx + hy * hy + hz * hz) + (foliage ? 0.30f : 0.01f);
            kind[m] = axis == AXIS_WATER ? KIND_WATER : axis == AXIS_TRANSLUCENT ? KIND_TRANSLUCENT : KIND_OPAQUE;
            if (axis < AXIS_NONE) {
                planes[m * 4] = AXES[axis][0];
                planes[m * 4 + 1] = AXES[axis][1];
                planes[m * 4 + 2] = AXES[axis][2];
                // Wind schwingt nur horizontal (XZ): reale Seiten-Ebenen wandern aus dem statischen
                // Cutoff -> Cull-Popping an Laub-/Pflanzen-Seiten. Puffer pro Maximal-Amplitude
                // (Ober-/Unterseiten unberührt, Nicht-Laub unverändert -> Overdraw nur dort).
                planes[m * 4 + 3] = cutoff - 4.0f * windMax;
            }
        }
        vb.flip();
        tb.flip();
        int solid = 0, cutout = 0;
        for (int q = 0; q < quads; q++) {
            if (category[q] == CAT_SOLID) solid++;
            else if (category[q] == CAT_CUTOUT) cutout++;
        }
        // Hash der Raytracing-Geometrie (Positionen + UVs der massiven/Cutout-Quads, liegen vorn):
        // aendert sich nur Wasser/Pflanzen/Licht, bleibt das BLAS der Section bestehen
        long h = 0x9E3779B97F4A7C15L ^ ((long) solid << 32 | cutout);
        int rtWords = (solid + cutout) * WORDS_PER_QUAD;
        for (int w = 0; w < rtWords; w++) {
            int word = vb.getInt(w * 4);
            if ((w & 3) == 1) word &= 0xFFFF;       // nur z, kein Licht
            else if ((w & 3) == 3) continue;        // Farbe/Material irrelevant fuer Strahlen
            h = (h ^ word) * 0x100000001B3L;
        }
        return new MeshResult(key, generation, version, quads, meshlets, vb, tb,
                Arrays.copyOf(mStart, meshlets), Arrays.copyOf(mCount, meshlets), bounds, planes, kind,
                solid, cutout, h, Arrays.copyOf(lights, lightCount * LIGHT_INTS));
    }

    // ---- Fluid-Abgriff (FluidRenderer schreibt per VertexConsumer) ----

    private static final class FluidCapture implements VertexConsumer {
        private final float[] pos = new float[4 * 5];
        private final int[] color = new int[4];
        private final int[] light = new int[4];
        private int count;
        private int cursor = -1;
        private final float[] vbuf = new float[64 * 5];
        private final int[] cbuf = new int[64];
        private final int[] lbuf = new int[64];

        VertexConsumer begin() {
            return this;
        }

        void flush(SectionMesher mesher, int material) {
            for (int q = 0; q + 4 <= count; q += 4) {
                System.arraycopy(vbuf, q * 5, pos, 0, 20);
                System.arraycopy(cbuf, q, color, 0, 4);
                System.arraycopy(lbuf, q, light, 0, 4);
                mesher.putFluidQuad(pos, color, light, material);
            }
            count = 0;
            cursor = -1;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            if (count >= 64) return this; // Fluid-Block hat max. 6 Quads (+Rueckseite)
            cursor = count++;
            vbuf[cursor * 5] = x;
            vbuf[cursor * 5 + 1] = y;
            vbuf[cursor * 5 + 2] = z;
            cbuf[cursor] = -1;
            lbuf[cursor] = 0;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            if (cursor >= 0) cbuf[cursor] = (a << 24) | (r << 16) | (g << 8) | b;
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            if (cursor >= 0) cbuf[cursor] = argb;
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            if (cursor >= 0) {
                vbuf[cursor * 5 + 3] = u;
                vbuf[cursor * 5 + 4] = v;
            }
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            if (cursor >= 0) lbuf[cursor] = (u & 0xFFFF) | (v << 16);
            return this;
        }

        @Override
        public VertexConsumer setUv3(float u, float v) {
            return this; // 26.3: dritter UV-Satz, fuer Fluids ungenutzt
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }
}
