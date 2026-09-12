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
    /**
     * @param fullCover Bodendecker, der die ganze Oberseite bedeckt (Schneeschicht, Teppich): ersetzt
     *                  die Oberseitenfarbe, statt wie Pflanzen mit ihr gemischt zu werden
     */
    public record Material(int id, BlockState state, int kind, int[] color, boolean[] tinted,
                           BlockTintSource tint, int emission, int tintType, int constTint, boolean fullCover, int texId) {
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
    // nach Registry-Schluessel (Client-Registry und Vanilla-Lookup liefern verschiedene Holder desselben Bioms)
    private static final ConcurrentHashMap<Object, Integer> BIOME_IDS = new ConcurrentHashMap<>();
    private static final List<Holder<Biome>> BIOMES = new ArrayList<>();
    private static final ConcurrentHashMap<Integer, Integer> TINT_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<TextureAtlasSprite, Integer> SPRITE_COLORS = new ConcurrentHashMap<>();
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static final Material AIR;

    /**
     * Aussehen einer LOD-Flaeche beim Zeichnen: je Seite (oben, Seite, unten) Sprite im Block-Atlas
     * (u0, v0, u1, v1; leer = keine Textur), dessen mittlere Helligkeit (0..255, Gamma) sowie Farbe
     * und Toenungsart. Gleiches Aussehen teilt sich eine ID (Quads tragen sie statt einer Farbe);
     * der Shader moduliert die Durchschnittsfarbe mit der Textur -> gleiche Muster wie das Nahfeld.
     */
    public record Appearance(int id, float[] rect, int[] luma, int[] color, int[] tint, int constTint) {
    }

    public static final int MAX_APPEARANCES = 8192; // 13 Bit im Quad
    private static final ConcurrentHashMap<List<Object>, Appearance> APPEAR_BY_KEY = new ConcurrentHashMap<>();
    private static final List<Appearance> APPEARANCES = new ArrayList<>();
    private static volatile Appearance[] appearTable = new Appearance[0];

    static {
        BIOMES.add(null); // 0 = unbekannt
        AIR = new Material(0, Blocks.AIR.defaultBlockState(), KIND_AIR, new int[3], new boolean[3], null, 0, 0, 0xFFFFFF, false, 0);
        // Aussehen 0: neutrales Grau ohne Textur (Tabelle voll)
        Appearance none = new Appearance(0, new float[12], new int[3], new int[]{0x808080, 0x808080, 0x808080}, new int[3], 0xFFFFFF);
        APPEARANCES.add(none);
        appearTable = new Appearance[]{none};
        BY_ID.add(AIR);
        BY_STATE.put(AIR.state(), AIR);
        table = new Material[]{AIR};
    }

    private LodMaterials() {
    }

    /**
     * Nach Resource-Reload: Farben neu bestimmen. Die IDs bleiben (gebaute Saeulen und die
     * GPU-Tabelle verweisen darauf); die GPU laedt die Tabelle ueber generation() neu hoch.
     */
    public static synchronized void reset() {
        TINT_CACHE.clear();
        SPRITE_COLORS.clear();
        synchronized (APPEARANCES) {
            // neu in Material-Reihenfolge vergeben -> IDs meist gleich
            APPEAR_BY_KEY.clear();
            APPEARANCES.subList(1, APPEARANCES.size()).clear();
            appearTable = APPEARANCES.toArray(new Appearance[0]);
        }
        synchronized (BY_ID) {
            for (int i = 1; i < BY_ID.size(); i++) {
                Material old = BY_ID.get(i);
                Material m = build(old.id(), old.state());
                BY_ID.set(i, m);
                BY_STATE.put(m.state(), m);
            }
            table = BY_ID.toArray(new Material[0]);
        }
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
        Object key = biome.unwrapKey().map(k -> (Object) k).orElse(biome);
        Integer id = BIOME_IDS.get(key);
        if (id != null) return id;
        synchronized (BIOMES) {
            id = BIOME_IDS.get(key);
            if (id != null) return id;
            if (BIOMES.size() >= 256) return 0; // mehr als 255 Biome: Rest ungetoent
            id = BIOMES.size();
            BIOMES.add(biome);
            BIOME_IDS.put(key, id);
            return id;
        }
    }

    /** Endfarbe einer Seite inkl. Biom-Toenung (0xRRGGBB). */
    public static int faceColor(Material m, int face, int biomeId) {
        int c = m.color()[face];
        if (m.tinted()[face] && m.tintType() == TINT_WATER) return multiply(c, biomeColors(biomeId)[3]);
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
        Material m = build(id, state);
        synchronized (BY_ID) {
            BY_ID.add(m);
            table = BY_ID.toArray(new Material[0]);
        }
        return m;
    }

    private static Material build(int id, BlockState state) {
        int kind = classify(state);
        int[] color = new int[3];
        boolean[] tinted = new boolean[3];
        TextureAtlasSprite[] sprites = new TextureAtlasSprite[3];
        BlockTintSource tint = null;
        if (kind != KIND_AIR) {
            tint = averageColors(state, color, tinted, sprites);
        }
        int[] tt = classifyTint(state, tint);
        boolean liquid = kind == KIND_WATER && state.getBlock() instanceof LiquidBlock;
        if (liquid) {
            // Fluessigkeiten haben kein Blockmodell: graue Wassertextur + Biom-Wasserfarbe (wie der Fluid-Renderer);
            // Wasserpflanzen (Seegras, Kelp) behalten ihr eigenes Aussehen (Grund unter ihnen im LOD)
            java.util.Arrays.fill(tinted, true);
            tt = new int[]{TINT_WATER, 0};
        }
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY)
                && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY)) {
            // verschneites Gras/Myzel: die Oberseite ist in Vanilla immer von Schnee bedeckt
            color[FACE_TOP] = snowColor();
            tinted[FACE_TOP] = false;
            sprites[FACE_TOP] = snowSprite();
        }
        if (liquid) java.util.Arrays.fill(sprites, null); // eigene Wasser-Schattierung, kein Muster
        int texId = kind == KIND_AIR ? 0 : appearance(sprites, color, tinted, tt[0], tt[1]);
        return new Material(id, state, kind, color, tinted, tint, Math.min(state.getLightEmission(), 15), tt[0], tt[1],
                kind == KIND_COVER && coversTop(state), texId);
    }

    private static int appearance(TextureAtlasSprite[] sprites, int[] color, boolean[] tinted, int tintType, int constTint) {
        int[] tints = new int[3];
        for (int f = 0; f < 3; f++) tints[f] = tinted[f] ? tintType : TINT_NONE;
        List<Object> key = List.of(sprites[0] == null ? "" : sprites[0], sprites[1] == null ? "" : sprites[1],
                sprites[2] == null ? "" : sprites[2], color[0], color[1], color[2], tints[0], tints[1], tints[2],
                tintType == TINT_CONST ? constTint : 0);
        Appearance a = APPEAR_BY_KEY.get(key);
        if (a != null) return a.id();
        synchronized (APPEARANCES) {
            a = APPEAR_BY_KEY.get(key);
            if (a != null) return a.id();
            if (APPEARANCES.size() >= MAX_APPEARANCES) return 0;
            float[] rect = new float[12];
            int[] luma = new int[3];
            for (int f = 0; f < 3; f++) {
                TextureAtlasSprite sp = sprites[f];
                if (sp == null) continue;
                int c = spriteColor(sp);
                if (c < 0) continue;
                rect[f * 4] = sp.getU0();
                rect[f * 4 + 1] = sp.getV0();
                rect[f * 4 + 2] = sp.getU1();
                rect[f * 4 + 3] = sp.getV1();
                luma[f] = Math.round(((c >> 16) & 0xFF) * 0.2126f + ((c >> 8) & 0xFF) * 0.7152f + (c & 0xFF) * 0.0722f);
            }
            a = new Appearance(APPEARANCES.size(), rect, luma, color.clone(), tints, tintType == TINT_CONST ? constTint : 0xFFFFFF);
            APPEARANCES.add(a);
            APPEAR_BY_KEY.put(key, a);
            appearTable = APPEARANCES.toArray(new Appearance[0]);
            return a.id();
        }
    }

    public static Appearance[] appearances() {
        return appearTable;
    }

    /** Deckt die Form die ganze Blockoberseite ab (Schneeschicht, Teppich, Moosteppich)? */
    private static boolean coversTop(BlockState state) {
        try {
            VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (shape.isEmpty()) return false;
            return shape.min(Direction.Axis.X) < 0.01 && shape.max(Direction.Axis.X) > 0.99
                    && shape.min(Direction.Axis.Z) < 0.01 && shape.max(Direction.Axis.Z) > 0.99;
        } catch (Throwable t) {
            return false;
        }
    }

    private static TextureAtlasSprite snowSprite() {
        try {
            return Minecraft.getInstance().getModelManager().getBlockStateModelSet()
                    .get(Blocks.SNOW_BLOCK.defaultBlockState()).particleMaterial().sprite();
        } catch (Throwable t) {
            return null;
        }
    }

    private static int snowColor() {
        try {
            int c = spriteColor(Minecraft.getInstance().getModelManager().getBlockStateModelSet()
                    .get(Blocks.SNOW_BLOCK.defaultBlockState()).particleMaterial().sprite());
            return c >= 0 ? c : 0xF0F8F8;
        } catch (Throwable t) {
            return 0xF0F8F8;
        }
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
    private static BlockTintSource averageColors(BlockState state, int[] color, boolean[] tinted, TextureAtlasSprite[] sprites) {
        Minecraft mc = Minecraft.getInstance();
        BlockStateModel model = mc.getModelManager().getBlockStateModelSet().get(state);
        long[][] sum = new long[3][4]; // untoent: r,g,b,n  (Index 3 = Anzahl)
        long[][] sumT = new long[3][4];
        int tintIndex = -1;
        TextureAtlasSprite[][] first = new TextureAtlasSprite[2][3]; // erstes Sprite je Seite: untoent, toent
        List<BlockStateModelPart> parts = new ArrayList<>();
        try {
            model.collectParts(RandomSource.create(42L), parts);
        } catch (Throwable ignored) {
        }
        for (BlockStateModelPart part : parts) {
            for (Direction dir : Direction.values()) {
                for (BakedQuad q : part.getQuads(dir)) tintIndex = accumulate(q, sum, sumT, tintIndex, first);
            }
            for (BakedQuad q : part.getQuads(null)) tintIndex = accumulate(q, sum, sumT, tintIndex, first);
        }
        TextureAtlasSprite particle = model.particleMaterial().sprite();
        int fallback = spriteColor(particle);
        for (int f = 0; f < 3; f++) {
            if (sum[f][3] > 0) {
                color[f] = avg(sum[f]);
                sprites[f] = first[0][f];
            } else if (sumT[f][3] > 0) {
                color[f] = avg(sumT[f]);
                tinted[f] = true;
                sprites[f] = first[1][f];
            } else {
                // keine Quads in diese Richtung: andere Seite, sonst Partikel-Textur
                int of = sum[FACE_SIDE][3] > 0 ? FACE_SIDE : sum[FACE_TOP][3] > 0 ? FACE_TOP : -1;
                if (of >= 0) {
                    color[f] = avg(sum[of]);
                    sprites[f] = first[0][of];
                } else if (sumT[FACE_SIDE][3] > 0 || sumT[FACE_TOP][3] > 0) {
                    int tf = sumT[FACE_SIDE][3] > 0 ? FACE_SIDE : FACE_TOP;
                    color[f] = avg(sumT[tf]);
                    tinted[f] = true;
                    sprites[f] = first[1][tf];
                } else {
                    color[f] = fallback;
                    sprites[f] = particle;
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

    private static int accumulate(BakedQuad q, long[][] sum, long[][] sumT, int tintIndex, TextureAtlasSprite[][] first) {
        int c = spriteColor(q.materialInfo().sprite());
        if (c < 0) return tintIndex;
        Direction d = q.direction();
        int f = d == Direction.UP ? FACE_TOP : d == Direction.DOWN ? FACE_BOTTOM : FACE_SIDE;
        boolean t = q.materialInfo().isTinted();
        if (first[t ? 1 : 0][f] == null) first[t ? 1 : 0][f] = q.materialInfo().sprite();
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
                        int p = img.getPixel(x, y); // ARGB
                        int al = (p >>> 24) & 0xFF;
                        if (al < 16) continue;
                        r += (long) ((p >> 16) & 0xFF) * al;
                        g += (long) ((p >> 8) & 0xFF) * al;
                        b += (long) (p & 0xFF) * al;
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
