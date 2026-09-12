package simon.vulkanfish.client.lod.gen;

import java.util.EnumSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * Macht aus der GPU-Dichte (2 Bit pro Block) einen Chunk mit Vanilla-Oberflaeche – im
 * Worker-Thread, ohne Server-Chunks anzufassen: eigener ProtoChunk, Biome aus Vanillas
 * Biomquelle, Oberflaeche aus Vanillas SurfaceSystem mit den Surface-Rules der Welt
 * (Datapacks inklusive). Strukturen bleiben aussen vor (leerer Beardifier), sonst wuerde
 * Vanilla echte Chunks laden.
 *
 * <p>Tempo: Stein tiefer als {@value #SURFACE_DEPTH} Bloecke unter der naechsten offenen Stelle
 * darueber wird vorher zum Platzhalter (Barriere); Vanillas Regeln laufen nur auf "Standard-
 * block", also nur auf den obersten Schichten. Im LOD zaehlt der Platzhalter wieder als Stein.
 */
public final class GeneratedChunkBuilder {
    private static final int SURFACE_DEPTH = 16;
    public static final BlockState PLACEHOLDER = Blocks.BARRIER.defaultBlockState();

    private final WorldgenSource src;
    private final PalettedContainerFactory containers;
    private final Aquifer.FluidPicker fluidPicker;
    private final WorldGenerationContext context;
    private final long biomeSeed;

    public GeneratedChunkBuilder(WorldgenSource src) {
        this.src = src;
        this.containers = PalettedContainerFactory.create(src.level.registryAccess());
        int sea = src.settings.seaLevel();
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        Aquifer.FluidStatus seaStatus = new Aquifer.FluidStatus(sea, src.settings.defaultFluid());
        this.fluidPicker = (x, y, z) -> y < Math.min(-54, sea) ? lava : seaStatus;
        this.context = new WorldGenerationContext(src.generator, src.level);
        this.biomeSeed = BiomeManager.obfuscateSeed(src.level.getSeed());
    }

    /**
     * @param words GPU-Ausgabe des Chunks: [y][z] je 16 Bloecke x 2 Bit (0 Luft, 1 Stein, 2 Fluessigkeit, 3 Lava)
     */
    /** Statistik (ns) je Schritt: Bloecke, Heightmaps, Biome, Oberflaeche. */
    public static final java.util.concurrent.atomic.AtomicLongArray TIMES = new java.util.concurrent.atomic.AtomicLongArray(5);

    public LevelChunkSection[] build(int cx, int cz, int[] words, int wordOffset) {
        long t0 = System.nanoTime();
        int minY = src.level.getMinY(), height = src.level.getHeight();
        ProtoChunk chunk = new ProtoChunk(new ChunkPos(cx, cz), UpgradeData.EMPTY, src.level, containers, null);
        BlockState stone = src.settings.defaultBlock(), fluid = src.settings.defaultFluid(), lava = Blocks.LAVA.defaultBlockState();
        LevelChunkSection[] sections = chunk.getSections();
        // pro Saeule von oben: Tiefe unter der letzten offenen Stelle -> Platzhalter
        int[] depth = new int[256];
        for (int ly = height - 1; ly >= 0; ly--) {
            LevelChunkSection sec = sections[ly >> 4];
            int sy = ly & 15;
            for (int lz = 0; lz < 16; lz++) {
                int word = words[wordOffset + ly * 16 + lz];
                if (word == 0) {
                    for (int lx = 0; lx < 16; lx++) depth[lz * 16 + lx] = 0;
                    continue;
                }
                for (int lx = 0; lx < 16; lx++) {
                    int code = (word >>> (lx * 2)) & 3;
                    int col = lz * 16 + lx;
                    if (code == 0) {
                        depth[col] = 0;
                        continue;
                    }
                    BlockState st;
                    if (code == 1) {
                        st = ++depth[col] > SURFACE_DEPTH ? PLACEHOLDER : stone;
                    } else {
                        depth[col] = 0;
                        st = code == 2 ? fluid : lava;
                    }
                    sec.setBlockState(lx, sy, lz, st, false);
                }
            }
        }
        long t1 = System.nanoTime();
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
        long t2 = System.nanoTime();
        var biomeSource = src.generator.getBiomeSource();
        var sampler = src.randomState.sampler();
        chunk.fillBiomesFromNoise(biomeSource, sampler);
        chunk.setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.NOISE); // Biome gelten ab BIOMES
        long t3 = System.nanoTime();
        int qx0 = cx * 4, qz0 = cz * 4, qyMin = minY >> 2, qyMax = (minY + height) >> 2;
        BiomeManager biomes = new BiomeManager((qx, qy, qz) -> {
            if (qx >= qx0 && qx < qx0 + 4 && qz >= qz0 && qz < qz0 + 4 && qy >= qyMin && qy < qyMax) {
                return chunk.getNoiseBiome(qx, qy, qz);
            }
            return biomeSource.getNoiseBiome(qx, qy, qz, sampler);
        }, biomeSeed);
        NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(c -> NoiseChunk.forChunk(c, src.randomState, Beardifier.EMPTY,
                src.settings, fluidPicker, Blender.empty()));
        src.randomState.surfaceSystem().buildSurface(src.randomState, biomes, src.settings.useLegacyRandomSource(), context,
                chunk, noiseChunk, src.settings.surfaceRule(), null);
        long t4 = System.nanoTime();
        TIMES.addAndGet(0, t1 - t0);
        TIMES.addAndGet(1, t2 - t1);
        TIMES.addAndGet(2, t3 - t2);
        TIMES.addAndGet(3, t4 - t3);
        TIMES.incrementAndGet(4);
        return sections;
    }
}
