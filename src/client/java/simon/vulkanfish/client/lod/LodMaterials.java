package simon.vulkanfish.client.lod;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.VoxelShape;
import simon.vulkanfish.client.mixin.SpriteContentsAccessor;

/**
 * LOD-Materialtabelle: BlockState -> kompakte ID (16 Bit) mit Art (massiv, Laub, Bodendecker,
 * Wasser, Lava, Glas), Durchschnittsfarbe je Seite (aus den echten Modell-Texturen, also auch
 * Resourcepacks) und Toenung pro Biom (Vanillas BlockTintSources). Threadsicher, lazy.
 *
 * <p>Bodendecker (Gras, Blumen, Teppiche, duenner Schnee ...) sind im LOD keine Voxel: sie
 * faerben die Oberseite des Blocks darunter ein – so geht ihre Farbe nicht verloren, ohne
 * Wiesen um einen ganzen Voxel anzuheben.
 */
public final class LodMaterials {
    public static final int KIND_AIR = 0;
    public static final int KIND_SOLID = 1;
    public static final int KIND_LEAVES = 2;
    public static final int KIND_COVER = 3;
    public static final int KIND_WATER = 4;
    public static final int KIND_LAVA = 5;
    public static final int KIND_GLASS = 6;

    public static final int FACE_TOP = 0;
    public static final int FACE_SIDE = 1;
    public static final int FACE_BOTTOM = 2;

    /** Eintrag der Tabelle; Farben als 0xRRGGBB (Gamma, wie die Texturen). */
    public record Material(int id, BlockState state, int kind, int[] color, boolean[] tinted,
                           BlockTintSource tint, int emission, int tintType, int constTint) {
        public boolean isVoxel() {
            return kind != KIND_AIR && kind != KIND_COVER;
        }

        /** Blockiert es den Blick/Himmel (fuer Flaechen- und Himmelslicht-Tests)? */
        public boolean occludes() {
            return kind == KIND_SOLID || kind == KIND_LEAVES || kind == KIND_LAVA || kind == KIND_GLASS;
        }
    }

    private static final ConcurrentHashMap<BlockState, Material> BY_STATE = new ConcurrentHashMap<>();
    private static final List<Material> BY_ID = new ArrayList<>();
    // Lesezugriff pro Block aus vielen Worker-Threads: unveraenderliche Kopie, ohne Lock lesbar
    private static volatile Material[] table = new Material[0];
    private static final ConcurrentHashMap<Holder<Biome>, Integer> BIOME_IDS = new ConcurrentHashMap<>();
    private static final List<Holder<Biome>> BIOMES = new ArrayList<>();
    private static final ConcurrentHashMap<Integer, Integer> TINT_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<TextureAtlasSprite, Integer> SPRITE_COLORS = new ConcurrentHashMap<>();
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static final Material AIR;

    static {
        BIOMES.add(null); // 0 = unbekannt
        AIR = new Material(0, Blocks.AIR.defaultBlockState(), KIND_AIR, new int[3], new boolean[3], null, 0, 0, 0xFFFFFF);
        BY_ID.add(AIR);
        BY_STATE.put(AIR.state(), AIR);
        table = new Material[]{AIR};
    }

    private LodMaterials() {
    }

    /** Nach Resource-Reload: Farben neu bestimmen (IDs bleiben, Tabelle wird neu gefuellt). */
    public static synchronized void reset() {
        BY_STATE.clear();
        synchronized (BY_ID) {
            BY_ID.clear();
            BY_ID.add(AIR);
            table = BY_ID.toArray(new Material[0]);
        }
        BY_STATE.put(AIR.state(), AIR);
        TINT_CACHE.clear();
        SPRITE_COLORS.clear();
        GENERATION.incrementAndGet();
    }

    public static int generation() {
        return GENERATION.get();
    }

    public static Material of(BlockState state) {
        Material m = BY_STATE.get(state);
        if (m != null) return m;
        synchronized (LodMaterials.class) {
            m = BY_STATE.get(state);
            if (m != null) return m;
            m = create(state);
            BY_STATE.put(state, m);
            return m;
        }
    }

    public static Material byId(int id) {
        Material[] t = table;
        return id >= 0 && id < t.length ? t[id] : AIR;
    }

    public static int biomeId(Holder<Biome> biome) {
        if (biome == null) return 0;
        Integer id = BIOME_IDS.get(biome);
        if (id != null) return id;
        synchronized (BIOMES) {
            id = BIOME_IDS.get(biome);
            if (id != null) return id;
            if (BIOMES.size() >= 256) return 0; // mehr als 255 Biome: Rest ungetoent
            id = BIOMES.size();
            BIOMES.add(biome);
            BIOME_IDS.put(biome, id);
            return id;
        }
    }

    /** Endfarbe einer Seite inkl. Biom-Toenung (0xRRGGBB). */
    public static int faceColor(Material m, int face, int biomeId) {
        int c = m.color()[face];
        if (!m.tinted()[face] || m.tint() == null) return c;
        int key = (m.id() << 8) | (biomeId & 0xFF);
        Integer tint = TINT_CACHE.get(key);
        if (tint == null) {
            tint = computeTint(m, biomeId);
            TINT_CACHE.put(key, tint);
        }
        return multiply(c, tint);
    }

    private static int computeTint(Material m, int biomeId) {
        Holder<Biome> biome;
        synchronized (BIOMES) {
            biome = biomeId > 0 && biomeId < BIOMES.size() ? BIOMES.get(biomeId) : null;
        }
        try {
            if (biome == null) return m.tint().color(m.state()) & 0xFFFFFF;
            return m.tint().colorInWorld(m.state(), new BiomeTintGetter(biome.value()), BlockPos.ZERO) & 0xFFFFFF;
        } catch (Throwable t) {
            return 0xFFFFFF;
        }
    }

    public static int multiply(int a, int b) {
        int r = ((a >> 16) & 0xFF) * ((b >> 16) & 0xFF) / 255;
        int g = ((a >> 8) & 0xFF) * ((b >> 8) & 0xFF) / 255;
        int bl = (a & 0xFF) * (b & 0xFF) / 255;
        return (r << 16) | (g << 8) | bl;
    }

    private static Material create(BlockState state) {
        int id;
        synchronized (BY_ID) {
            id = BY_ID.size();
        }
        if (id > 0xFFFF) return AIR;
        int kind = classify(state);
        int[] color = new int[3];
        boolean[] tinted = new boolean[3];
        BlockTintSource tint = null;
        if (kind != KIND_AIR) {
            tint = averageColors(state, color, tinted);
        }
        int[] tt = classifyTint(state, tint);
        Material m = new Material(id, state, kind, color, tinted, tint, Math.min(state.getLightEmission(), 15), tt[0], tt[1]);
        synchronized (BY_ID) {
            BY_ID.add(m);
            table = BY_ID.toArray(new Material[0]);
        }
        return m;
    }

    private static int classify(BlockState state) {
        if (state.isAir()) return KIND_AIR;
        var block = state.getBlock();
        FluidState fluid = state.getFluidState();
        if (block instanceof LiquidBlock) {
            return fluid.is(Fluids.LAVA) || fluid.is(Fluids.FLOWING_LAVA) ? KIND_LAVA : KIND_WATER;
        }
        VoxelShape collision = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (!fluid.isEmpty() && collision.isEmpty()) return KIND_WATER; // Seegras, Kelp: sieht aus wie Wasser
        if (block instanceof LeavesBlock) return KIND_LEAVES;
        VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        double height = shape.isEmpty() ? 0.0 : shape.max(Direction.Axis.Y);
        if (block instanceof VegetationBlock || (collision.isEmpty() && height < 0.5) || height < 0.3) {
            return KIND_COVER;
        }
        if (block instanceof net.minecraft.world.level.block.HalfTransparentBlock) return KIND_GLASS;
        return KIND_SOLID;
    }

    /** Durchschnittsfarbe je Seite aus den Modell-Quads; liefert die Toenungsquelle (Tint-Index). */
    private static BlockTintSource averageColors(BlockState state, int[] color, boolean[] tinted) {
        Minecraft mc = Minecraft.getInstance();
        BlockStateModel model = mc.getModelManager().getBlockStateModelSet().get(state);
        long[][] sum = new long[3][4]; // untoent: r,g,b,n  (Index 3 = Anzahl)
        long[][] sumT = new long[3][4];
        int tintIndex = -1;
        List<BlockStateModelPart> parts = new ArrayList<>();
        try {
            model.collectParts(RandomSource.create(42L), parts);
        } catch (Throwable ignored) {
        }
        for (BlockStateModelPart part : parts) {
            for (Direction dir : Direction.values()) {
                for (BakedQuad q : part.getQuads(dir)) tintIndex = accumulate(q, sum, sumT, tintIndex);
            }
            for (BakedQuad q : part.getQuads(null)) tintIndex = accumulate(q, sum, sumT, tintIndex);
        }
        int fallback = spriteColor(model.particleMaterial().sprite());
        for (int f = 0; f < 3; f++) {
            if (sum[f][3] > 0) {
                color[f] = avg(sum[f]);
            } else if (sumT[f][3] > 0) {
                color[f] = avg(sumT[f]);
                tinted[f] = true;
            } else {
                // keine Quads in diese Richtung: andere Seite, sonst Partikel-Textur
                long[] any = sum[FACE_SIDE][3] > 0 ? sum[FACE_SIDE] : sum[FACE_TOP][3] > 0 ? sum[FACE_TOP] : null;
                if (any != null) {
                    color[f] = avg(any);
                } else if (sumT[FACE_SIDE][3] > 0 || sumT[FACE_TOP][3] > 0) {
                    color[f] = avg(sumT[FACE_SIDE][3] > 0 ? sumT[FACE_SIDE] : sumT[FACE_TOP]);
                    tinted[f] = true;
                } else {
                    color[f] = fallback;
                }
            }
        }
        if (tintIndex < 0) return null;
        try {
            return mc.getBlockColors().getTintSource(state, tintIndex);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int accumulate(BakedQuad q, long[][] sum, long[][] sumT, int tintIndex) {
        int c = spriteColor(q.materialInfo().sprite());
        if (c < 0) return tintIndex;
        Direction d = q.direction();
        int f = d == Direction.UP ? FACE_TOP : d == Direction.DOWN ? FACE_BOTTOM : FACE_SIDE;
        boolean t = q.materialInfo().isTinted();
        long[] s = t ? sumT[f] : sum[f];
        s[0] += (c >> 16) & 0xFF;
        s[1] += (c >> 8) & 0xFF;
        s[2] += c & 0xFF;
        s[3]++;
        return t && tintIndex < 0 ? q.materialInfo().tintIndex() : tintIndex;
    }

    private static int avg(long[] s) {
        return (int) (s[0] / s[3]) << 16 | (int) (s[1] / s[3]) << 8 | (int) (s[2] / s[3]);
    }

    /** Alpha-gewichteter Mittelwert aller Pixel des Sprites (0xRRGGBB), -1 wenn leer. */
    private static int spriteColor(TextureAtlasSprite sprite) {
        if (sprite == null) return -1;
        return SPRITE_COLORS.computeIfAbsent(sprite, sp -> {
            try {
                NativeImage img = ((SpriteContentsAccessor) sp.contents()).vulkanfish$originalImage();
                if (img == null) return 0x808080;
                int w = sp.contents().width(), h = sp.contents().height(); // erstes Animationsbild
                long r = 0, g = 0, b = 0, a = 0;
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int p = img.getPixel(x, y); // ABGR
                        int al = (p >>> 24) & 0xFF;
                        if (al < 16) continue;
                        r += (long) (p & 0xFF) * al;
                        g += (long) ((p >> 8) & 0xFF) * al;
                        b += (long) ((p >> 16) & 0xFF) * al;
                        a += al;
                    }
                }
                if (a == 0) return -1;
                return (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
            } catch (Throwable t) {
                return 0x808080;
            }
        });
    }

    // ---------- GPU-Tabellen ----------
    public static final int TINT_NONE = 0, TINT_GRASS = 1, TINT_FOLIAGE = 2, TINT_DRY_FOLIAGE = 3, TINT_WATER = 4, TINT_CONST = 5;
    private static final int SENTINEL_GRASS = 0x010203, SENTINEL_FOLIAGE = 0x040506, SENTINEL_DRY = 0x070809, SENTINEL_WATER = 0x0A0B0C;

    /**
     * Toenungsart fuer die GPU ohne echte Biome: der Getter liefert je Vanilla-Farbaufloeser einen
     * Kennwert; kommt er unveraendert zurueck, ist es diese Art, sonst eine feste Farbe.
     */
    private static int[] classifyTint(BlockState state, BlockTintSource tint) {
        if (tint == null) return new int[]{TINT_NONE, 0xFFFFFF};
        try {
            int c = tint.colorInWorld(state, new SentinelTintGetter(), BlockPos.ZERO) & 0xFFFFFF;
            if (c == SENTINEL_GRASS) return new int[]{TINT_GRASS, 0};
            if (c == SENTINEL_FOLIAGE) return new int[]{TINT_FOLIAGE, 0};
            if (c == SENTINEL_DRY) return new int[]{TINT_DRY_FOLIAGE, 0};
            if (c == SENTINEL_WATER) return new int[]{TINT_WATER, 0};
            return new int[]{TINT_CONST, c};
        } catch (Throwable t) {
            return new int[]{TINT_GRASS, 0};
        }
    }

    public static Material[] snapshot() {
        return table;
    }

    public static int biomeCount() {
        synchronized (BIOMES) {
            return BIOMES.size();
        }
    }

    /** Gras, Laub, Trockenlaub, Wasser (0xRRGGBB) eines Bioms – fuer die GPU-Toenung. */
    public static int[] biomeColors(int id) {
        Holder<Biome> b;
        synchronized (BIOMES) {
            b = id > 0 && id < BIOMES.size() ? BIOMES.get(id) : null;
        }
        if (b == null) return new int[]{0x79C05A, 0x59AE30, 0xA0A069, 0x3F76E4};
        Biome biome = b.value();
        return new int[]{
                net.minecraft.client.renderer.BiomeColors.GRASS_COLOR_RESOLVER.getColor(biome, 0, 0) & 0xFFFFFF,
                net.minecraft.client.renderer.BiomeColors.FOLIAGE_COLOR_RESOLVER.getColor(biome, 0, 0) & 0xFFFFFF,
                net.minecraft.client.renderer.BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER.getColor(biome, 0, 0) & 0xFFFFFF,
                net.minecraft.client.renderer.BiomeColors.WATER_COLOR_RESOLVER.getColor(biome, 0, 0) & 0xFFFFFF};
    }

    private record SentinelTintGetter() implements BlockAndTintGetter {
        @Override
        public CardinalLighting cardinalLighting() {
            return CardinalLighting.DEFAULT;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            if (resolver == net.minecraft.client.renderer.BiomeColors.GRASS_COLOR_RESOLVER) return SENTINEL_GRASS;
            if (resolver == net.minecraft.client.renderer.BiomeColors.FOLIAGE_COLOR_RESOLVER) return SENTINEL_FOLIAGE;
            if (resolver == net.minecraft.client.renderer.BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER) return SENTINEL_DRY;
            if (resolver == net.minecraft.client.renderer.BiomeColors.WATER_COLOR_RESOLVER) return SENTINEL_WATER;
            return SENTINEL_GRASS;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return null;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    }

    /** Minimaler Getter: nur die Biom-Farbe zaehlt (fuer Vanillas Toenungsquellen). */
    private record BiomeTintGetter(Biome biome) implements BlockAndTintGetter {
        @Override
        public CardinalLighting cardinalLighting() {
            return CardinalLighting.DEFAULT;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return resolver.getColor(biome, pos.getX(), pos.getZ());
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return null;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    }
}
