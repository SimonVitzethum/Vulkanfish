package simon.vulkanfish.client.lod.gen;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import simon.vulkanfish.client.lod.LodMaterials;
import simon.vulkanfish.client.mixin.worldgen.MultiNoiseBiomeSourceInvoker;

/**
 * Vereinfachte Oberflaeche fuer das GPU-LOD, pro Biom einmal bestimmt – mit Vanillas eigenen
 * Surface-Rules der Welt (Datapacks inklusive): fuer jedes Biom laufen Musterchunks (flach
 * tief, flach hoch, unter Wasser, steiler Hang) durch Vanillas SurfaceSystem, das haeufigste
 * Ergebnis wird Oberflaechen- bzw. Fuellmaterial. Baeume: Laub/Stamm aus den Baum-Features
 * des Bioms, Dichte aus dem Biomnamen (Wald, Taiga, Dschungel ...).
 *
 * <p>Dazu die Klima-Parameterliste der Biomquelle fuer die GPU-Suche (exakt wie Vanilla:
 * naechster Punkt nach quadratischem Abstand der quantisierten Parameter).
 */
public final class LodBiomeTable {
    private static final Logger LOG = LoggerFactory.getLogger("vulkanfish");
    // topLow, topHigh, wet, steep, filler, leaves, log, density(x1000),
    // Grundtemperatur(x1000), Schneedecke, Eis, Flags (bit0: gefrierend wie Frozen Ocean,
    // bit8-15: Seegras-Anteil des Meeresbodens 0..255), Seegras
    public static final int FIELDS = 13;

    /** Pro Biom-ID (LodMaterials.biomeId): Material-IDs + Baumdichte. */
    public final int[] table = new int[256 * FIELDS];
    /** Klima-Punkte: je 14 double (min/max x 6 Parameter + offset, offset) und die Biom-ID. */
    public final double[] climate;
    public final int[] climateBiome;
    public final boolean multiNoise;
    /** Suchbaum fuer die GPU (umsortierte Kopie von climate/climateBiome). */
    public ClimateTree tree;
    public final int fallbackBiome;

    private LodBiomeTable(double[] climate, int[] climateBiome, boolean multiNoise, int fallbackBiome) {
        this.climate = climate;
        this.climateBiome = climateBiome;
        this.multiNoise = multiNoise;
        this.fallbackBiome = fallbackBiome;
    }

    public static LodBiomeTable build(WorldgenSource src) {
        var biomeSource = src.generator.getBiomeSource();
        List<Holder<Biome>> biomes = new ArrayList<>(biomeSource.possibleBiomes());
        double[] climate;
        int[] ids;
        boolean multi = biomeSource instanceof MultiNoiseBiomeSource;
        if (multi) {
            var list = ((MultiNoiseBiomeSourceInvoker) biomeSource).vf$parameters().values();
            climate = new double[list.size() * 14];
            ids = new int[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Climate.ParameterPoint p = list.get(i).getFirst();
                Climate.Parameter[] ps = {p.temperature(), p.humidity(), p.continentalness(), p.erosion(), p.depth(), p.weirdness()};
                for (int j = 0; j < 6; j++) {
                    climate[i * 14 + j * 2] = ps[j].min();
                    climate[i * 14 + j * 2 + 1] = ps[j].max();
                }
                climate[i * 14 + 12] = p.offset();
                climate[i * 14 + 13] = 0;
                ids[i] = LodMaterials.biomeId(list.get(i).getSecond());
            }
        } else {
            climate = new double[0];
            ids = new int[0];
        }
        int fallback = biomes.isEmpty() ? 0 : LodMaterials.biomeId(biomes.getFirst());
        LodBiomeTable t = new LodBiomeTable(climate, ids, multi, fallback);
        long t0 = System.nanoTime();
        PalettedContainerFactory containers = PalettedContainerFactory.create(src.registries);
        int n = 0;
        for (Holder<Biome> b : biomes) {
            int id = LodMaterials.biomeId(b);
            if (id <= 0) continue;
            try {
                t.fill(src, containers, b, id, n++);
            } catch (Throwable e) {
                LOG.warn("[vulkanfish] LOD-Biomtabelle: {} fehlgeschlagen ({})", b.unwrapKey().map(k -> k.identifier().toString()).orElse("?"), e.toString());
            }
        }
        LOG.info("[vulkanfish] LOD-Biomtabelle: {} Biome in {} ms, {} Klimapunkte ({})", n,
                (System.nanoTime() - t0) / 1_000_000, ids.length, multi ? "GPU-Suche" : "feste Biomquelle");
        if (multi) {
            t.tree = ClimateTree.build(climate, ids);
            t.tree.validate(src, biomeSource);
        }
        return t;
    }

    private void fill(WorldgenSource src, PalettedContainerFactory containers, Holder<Biome> biome, int id, int salt) {
        int sea = src.settings.seaLevel();
        int stoneId = LodMaterials.of(src.settings.defaultBlock()).id();
        int low = mostCommonTop(src, containers, biome, salt * 4, sea + 4, -1, false);
        int high = mostCommonTop(src, containers, biome, salt * 4 + 1, Math.min(sea + 70, src.heights.getMaxY() - 40), -1, false);
        int wet = mostCommonTop(src, containers, biome, salt * 4 + 2, sea - 12, sea, false);
        int steep = mostCommonTop(src, containers, biome, salt * 4 + 3, sea + 20, -1, true);
        int filler = fillerOf(src, containers, biome, salt, sea + 4);
        int base = id * FIELDS;
        table[base] = low != 0 ? low : stoneId;
        table[base + 1] = high != 0 ? high : table[base];
        table[base + 2] = wet != 0 ? wet : table[base];
        table[base + 3] = steep != 0 ? steep : stoneId;
        table[base + 4] = filler != 0 ? filler : stoneId;
        int[] tree = trees(biome);
        table[base + 5] = tree[0];
        table[base + 6] = tree[1];
        table[base + 7] = tree[2];
        // Vereisung wie Vanillas Freeze-Feature (Schnee auf Land, Eis auf Wasser), das nicht zu den
        // Oberflaechenregeln gehoert und in der vereinfachten Generierung sonst fehlt
        boolean frozen = biome.unwrapKey().map(k -> k.identifier().getPath().contains("frozen")).orElse(false);
        table[base + 8] = Math.round(biome.value().getBaseTemperature() * 1000f);
        table[base + 9] = LodMaterials.of(Blocks.SNOW.defaultBlockState()).id();
        table[base + 10] = LodMaterials.of(Blocks.ICE.defaultBlockState()).id();
        // Meeresboden-Bewuchs (Vanillas Seegras-Features der Ozeane): im Nahwasser sieht der Grund
        // dadurch gruen aus, generierte Ozeane sollen genauso wirken
        String path = biome.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
        float grass = !path.contains("ocean") || frozen ? 0f : path.contains("warm") || path.contains("lukewarm") ? 0.35f
                : path.contains("cold") ? 0.15f : path.startsWith("deep") ? 0.25f : 0.3f;
        table[base + 11] = (frozenModifier(biome.value()) ? 1 : 0) | (Math.round(grass * 255f) << 8);
        table[base + 12] = LodMaterials.of(Blocks.SEAGRASS.defaultBlockState()).id();
    }

    /**
     * Nutzt das Biom Vanillas FROZEN-Temperaturmodifikator (gefrorene Ozeane: offene Flecken im Eis)?
     * ClimateSettings ist paketprivat -> per Reflection; faellt das aus, bleibt der Name als Hinweis.
     */
    public static boolean frozenModifierOf(Biome biome) {
        return frozenModifier(biome);
    }

    static boolean frozenModifier(Biome biome) {
        try {
            java.lang.reflect.Field f = Biome.class.getDeclaredField("climateSettings");
            f.setAccessible(true);
            Object climate = f.get(biome);
            java.lang.reflect.Method m = climate.getClass().getDeclaredMethod("temperatureModifier");
            m.setAccessible(true);
            return String.valueOf(m.invoke(climate)).equalsIgnoreCase("frozen");
        } catch (Throwable t) {
            return false;
        }
    }

    /** Musterchunk (flach, optional Wasser darueber / Hang) durch Vanillas Oberflaeche, haeufigster oberster Block. */
    private int mostCommonTop(WorldgenSource src, PalettedContainerFactory containers, Holder<Biome> biome, int salt,
                              int topY, int waterTop, boolean slope) {
        ProtoChunk chunk = surfaceChunk(src, containers, biome, salt, topY, waterTop, slope);
        Object2IntOpenHashMap<BlockState> counts = new Object2IntOpenHashMap<>();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int y = slope ? topY + x * 2 - 16 : topY;
                BlockState st = chunk.getBlockState(new BlockPos(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z));
                if (slope && (x < 2 || x > 13)) continue;
                if (!st.isAir() && st.getFluidState().isEmpty()) counts.addTo(st, 1);
            }
        }
        BlockState best = null;
        int bestN = 0;
        for (var e : counts.object2IntEntrySet()) {
            if (e.getIntValue() > bestN) {
                bestN = e.getIntValue();
                best = e.getKey();
            }
        }
        return best == null ? 0 : LodMaterials.of(best).id();
    }

    private int fillerOf(WorldgenSource src, PalettedContainerFactory containers, Holder<Biome> biome, int salt, int topY) {
        ProtoChunk chunk = surfaceChunk(src, containers, biome, salt * 7 + 5, topY, -1, false);
        BlockState st = chunk.getBlockState(new BlockPos(chunk.getPos().getMinBlockX() + 8, topY - 2, chunk.getPos().getMinBlockZ() + 8));
        return st.isAir() ? 0 : LodMaterials.of(st).id();
    }

    private static ProtoChunk surfaceChunk(WorldgenSource src, PalettedContainerFactory containers, Holder<Biome> biome, int salt,
                                           int topY, int waterTop, boolean slope) {
        // weit draussen, damit nichts mit echten Positionen kollidiert (Rauschen der Oberflaeche variiert trotzdem)
        ChunkPos pos = new ChunkPos(100_000 + salt * 3, -100_000 + salt * 5);
        ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, src.heights, containers, null);
        int minY = src.heights.getMinY();
        BlockState stone = src.settings.defaultBlock(), water = src.settings.defaultFluid();
        LevelChunkSection[] sections = chunk.getSections();
        for (int x = 0; x < 16; x++) {
            int top = slope ? topY + x * 2 - 16 : topY;
            for (int z = 0; z < 16; z++) {
                for (int y = Math.max(minY, top - 24); y <= top; y++) set(sections, minY, x, y, z, stone);
                for (int y = top + 1; y < waterTop; y++) set(sections, minY, x, y, z, water);
            }
        }
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
        chunk.fillBiomesFromNoise((qx, qy, qz) -> biome);
        chunk.setPersistedStatus(ChunkStatus.TERRAIN);
        BiomeManager bm = new BiomeManager((qx, qy, qz) -> biome, BiomeManager.obfuscateSeed(src.seed));
        int sea = src.settings.seaLevel();
        Aquifer.FluidStatus seaStatus = new Aquifer.FluidStatus(sea, water);
        // 26.3: NoiseChunk direkt bauen (Volumen des Chunks), MaterialSystem statt SurfaceSystem
        var volume = new net.minecraft.world.level.levelgen.densityfunction.DensityVolume(
                16, src.heights.getHeight(), 16, pos.getMinBlockX(), minY, pos.getMinBlockZ());
        NoiseChunk nc = new NoiseChunk(src.randomState, Beardifier.EMPTY, src.settings,
                (x, y, z) -> seaStatus, Blender.empty(), volume);
        src.randomState.surfaceSystem().buildSurface(src.randomState, bm,
                new WorldGenerationContext(src.generator, src.heights), chunk, nc,
                src.settings.materialRule().value(), java.util.Set.of());
        return chunk;
    }

    private static void set(LevelChunkSection[] sections, int minY, int x, int y, int z, BlockState st) {
        int ly = y - minY;
        if (ly < 0 || (ly >> 4) >= sections.length) return;
        sections[ly >> 4].setBlockState(x, ly & 15, z, st, false);
    }

    /** {Laub-ID, Stamm-ID, Dichte x1000}: Baum-Features des Bioms, Dichte nach Biomart. */
    private static int[] trees(Holder<Biome> biome) {
        BlockState leaves = null, log = null;
        try {
            outer:
            for (var set : biome.value().getGenerationSettings().features()) {
                for (var placed : set) {
                    java.util.List<Holder<net.minecraft.world.level.levelgen.feature.Feature>> all = new ArrayList<>();
                    placed.value().getFeatures().forEach(all::add);
                    for (Holder<net.minecraft.world.level.levelgen.feature.Feature> h : List.copyOf(all)) {
                        h.value().getSubFeatures().forEach(all::add);
                    }
                    for (Holder<net.minecraft.world.level.levelgen.feature.Feature> h : all) {
                        // 26.3: TreeFeature ist ein Record (kein ConfiguredFeature mehr)
                        if (h.value() instanceof net.minecraft.world.level.levelgen.feature.TreeFeature tf) {
                            RandomSource r = RandomSource.create(1);
                            try {
                                leaves = tf.foliageProvider().value().getState(null, r, BlockPos.ZERO);
                                log = tf.trunkProvider().value().getState(null, r, BlockPos.ZERO);
                            } catch (Throwable ignored) {
                            }
                            if (leaves != null) break outer;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        String name = biome.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
        double density;
        if (name.contains("dark_forest")) density = 0.30;
        else if (name.contains("jungle")) density = name.contains("sparse") ? 0.08 : name.contains("bamboo") ? 0.12 : 0.35;
        else if (name.contains("taiga") || name.contains("grove")) density = 0.22;
        else if (name.contains("forest")) density = 0.22;
        else if (name.contains("mangrove")) density = 0.25;
        else if (name.contains("swamp")) density = 0.08;
        else if (name.contains("cherry")) density = 0.07;
        else if (name.contains("savanna")) density = 0.02;
        else if (name.contains("wooded")) density = 0.05;
        else if (name.contains("plains") || name.contains("meadow")) density = 0.006;
        else density = leaves != null ? 0.10 : 0.0;
        if (leaves == null || log == null) {
            if (density <= 0) return new int[]{0, 0, 0};
            leaves = Blocks.OAK_LEAVES.defaultBlockState();
            log = Blocks.OAK_LOG.defaultBlockState();
        }
        return new int[]{LodMaterials.of(leaves).id(), LodMaterials.of(log).id(), (int) Math.round(density * 1000)};
    }
}
