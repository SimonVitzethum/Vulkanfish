package simon.vulkanfish.client.lod.gen;

import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import simon.vulkanfish.client.VulkanfishSettings;

/**
 * Worldgen-Quelle fuer das LOD: RandomState + Generator der Welt. Einzelspieler: direkt vom
 * integrierten Server (inklusive Datapacks). Mehrspieler: nur mit bekanntem Seed
 * (Einstellung je Serveradresse) und Vanillas eingebauter Worldgen; der Seed wird gegen den
 * gehashten Seed des Servers geprueft. Kompiliert die Dichtefunktion fuer die GPU und prueft
 * das Programm einmal gegen Vanillas eigene Auswertung.
 */
public final class WorldgenSource {
    private static final Logger LOG = LoggerFactory.getLogger("vulkanfish");

    /** Hoehe/Tiefe der Dimension (Server- oder Client-Level). */
    public final LevelHeightAccessor heights;
    /** Registries fuer Chunk-Container (Biome). */
    public final RegistryAccess registries;
    public final long seed;
    public final RandomState randomState;
    public final NoiseBasedChunkGenerator generator;
    public final NoiseGeneratorSettings settings;
    public final DensityProgram program;
    public final boolean valid;
    /** Mehrspieler mit eingebauter Vanilla-Worldgen (Server-Datapacks unbekannt). */
    public final boolean vanillaOnly;

    private WorldgenSource(LevelHeightAccessor heights, RegistryAccess registries, long seed, RandomState rs,
                           NoiseBasedChunkGenerator gen, NoiseGeneratorSettings settings, DensityProgram program, boolean valid,
                           boolean vanillaOnly) {
        this.heights = heights;
        this.registries = registries;
        this.seed = seed;
        this.randomState = rs;
        this.generator = gen;
        this.settings = settings;
        this.program = program;
        this.valid = valid;
        this.vanillaOnly = vanillaOnly;
    }

    /** Einzelspieler: Server-Welt; Mehrspieler: bekannter Seed + Vanilla-Worldgen; sonst null. */
    public static WorldgenSource forClientLevel(ClientLevel client) {
        try {
            if (client == null) return null;
            var server = Minecraft.getInstance().getSingleplayerServer();
            // Selbsttest des Mehrspieler-Wegs im Einzelspieler: -Dvulkanfish.testMultiplayerSeed
            if (server != null && Boolean.getBoolean("vulkanfish.testMultiplayerSeed")) {
                return forSeed(client, server.overworld().getSeed(), "Selbsttest");
            }
            if (server != null) {
                ServerLevel sl = server.getLevel(client.dimension());
                if (sl == null) return null;
                ChunkGenerator g = sl.getChunkSource().getGenerator();
                if (!(g instanceof NoiseBasedChunkGenerator gen)) {
                    LOG.info("[vulkanfish] LOD-Generator: {} ist kein Noise-Generator – nur echte Chunks", g.getClass().getSimpleName());
                    return null;
                }
                return compile(sl, sl.registryAccess(), sl.getSeed(), sl.getChunkSource().randomState(), gen, false);
            }
            return forMultiplayer(client);
        } catch (Throwable t) {
            LOG.warn("[vulkanfish] LOD-Generator nicht verfuegbar", t);
            return null;
        }
    }

    /**
     * Mehrspieler: Seed aus der Einstellung (je Serveradresse). Der Server schickt nur einen
     * Hash des Seeds (Biom-Zoom); passt er nicht, wird nichts generiert (falscher Seed).
     */
    private static WorldgenSource forMultiplayer(ClientLevel client) {
        var serverData = Minecraft.getInstance().getCurrentServer();
        if (serverData == null) return null;
        Long seed = VulkanfishSettings.seedFor(serverData.ip);
        if (seed == null) {
            LOG.info("[vulkanfish] LOD-Generator: kein Seed fuer {} eingestellt – nur echte Chunks (und Bobby)", serverData.ip);
            return null;
        }
        return forSeed(client, seed, serverData.ip);
    }

    /** Passt der Seed zum Hash, den der Server schickt? null = keine Welt geladen. */
    public static Boolean seedMatches(ClientLevel client, long seed) {
        if (client == null) return null;
        long serverHash = ((simon.vulkanfish.client.mixin.BiomeManagerAccessor) client.getBiomeManager()).vulkanfish$zoomSeed();
        return BiomeManager.obfuscateSeed(seed) == serverHash;
    }

    private static WorldgenSource forSeed(ClientLevel client, long seed, String serverName) {
        long serverHash = ((simon.vulkanfish.client.mixin.BiomeManagerAccessor) client.getBiomeManager()).vulkanfish$zoomSeed();
        if (BiomeManager.obfuscateSeed(seed) != serverHash) {
            LOG.warn("[vulkanfish] LOD-Generator: eingestellter Seed passt nicht zum Server {} – keine Generierung", serverName);
            return null;
        }
        HolderLookup.Provider vanilla = VanillaRegistries.createWorldLookup();
        ResourceKey<Level> dim = client.dimension();
        ResourceKey<NoiseGeneratorSettings> settingsKey;
        BiomeSource biomes;
        var biomeLookup = vanilla.lookupOrThrow(Registries.BIOME);
        var presets = vanilla.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
        if (dim == Level.OVERWORLD) {
            settingsKey = NoiseGeneratorSettings.OVERWORLD;
            biomes = MultiNoiseBiomeSource.createFromPreset(presets.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        } else if (dim == Level.NETHER) {
            settingsKey = NoiseGeneratorSettings.NETHER;
            biomes = MultiNoiseBiomeSource.createFromPreset(presets.getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER));
        } else if (dim == Level.END) {
            settingsKey = NoiseGeneratorSettings.END;
            biomes = TheEndBiomeSource.create(biomeLookup);
        } else {
            LOG.info("[vulkanfish] LOD-Generator: Dimension {} unbekannt – nur echte Chunks", dim.identifier());
            return null;
        }
        Holder<NoiseGeneratorSettings> settings = vanilla.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(settingsKey);
        NoiseBasedChunkGenerator gen = new NoiseBasedChunkGenerator(biomes, settings);
        RandomState rs = RandomState.create(vanilla.lookupOrThrow(Registries.NOISE), seed, settings.value());
        LOG.info("[vulkanfish] LOD-Generator: Mehrspieler {} mit bekanntem Seed, Vanilla-Worldgen ({})", serverName, dim.identifier());
        return compile(client, client.registryAccess(), seed, rs, gen, true);
    }

    private static WorldgenSource compile(LevelHeightAccessor heights, RegistryAccess registries, long seed, RandomState rs,
                                          NoiseBasedChunkGenerator gen, boolean vanillaOnly) {
        NoiseGeneratorSettings settings = gen.generatorSettings().value();
        long t0 = System.nanoTime();
        // 26.3: Klima direkt aus dem Router (humidity/weirdness heissen vegetation/ridges)
        var router = settings.noiseRouter();
        DensityProgram prog = DensityProgram.compile(router.finalDensity(), java.util.List.of(router.temperature(),
                router.vegetation(), router.continents(), router.erosion(), router.depth(), router.ridges()),
                rs, seed, settings.useLegacyRandomSource());
        LOG.info("[vulkanfish] LOD-Generator: Dichte kompiliert in {} ms: {}", (System.nanoTime() - t0) / 1_000_000, prog.stats());
        boolean ok = prog.unsupported.isEmpty() && validate(rs, router.finalDensity(), seed, settings.useLegacyRandomSource());
        return new WorldgenSource(heights, registries, seed, rs, gen, settings, prog, ok, vanillaOnly);
    }

    /** Einstufiges Programm gegen Vanillas compute() an Zufallspunkten. */
    private static boolean validate(RandomState rs, DensityFunction f, long seed, boolean useLegacyRandom) {
        DensityProgram direct = DensityProgram.compileDirect(f, rs, seed, useLegacyRandom);
        if (!direct.unsupported.isEmpty()) {
            LOG.warn("[vulkanfish] LOD-Generator: nicht unterstuetzte Dichtefunktionen {}", direct.unsupported);
            return false;
        }
        if (Boolean.getBoolean("vulkanfish.dumpProgram")) dumpProgram(direct);
        DensityCpu cpu = new DensityCpu(direct);
        Random rnd = new Random(1234);
        double maxErr = 0, sumErr = 0;
        int n = 400, signMismatch = 0;
        String worst = "";
        for (int i = 0; i < n; i++) {
            int x = rnd.nextInt(20000) - 10000, y = rnd.nextInt(384) - 64, z = rnd.nextInt(20000) - 10000;
            // 26.3: compute() ist weg – Vanillas Sampler direkt (exakt, ohne Context)
            double ref = rs.sampleBlockValueUncached(f, x, y, z);
            double got = cpu.evalDirect(x, y, z);
            double err = Math.abs(ref - got);
            if (err > maxErr) {
                maxErr = err;
                worst = x + "/" + y + "/" + z + " ref=" + ref + " got=" + got;
            }
            sumErr += err;
            if ((ref > 0) != (got > 0)) signMismatch++;
        }
        LOG.info("[vulkanfish] LOD-Generator: Programm vs. Vanilla an {} Punkten: max. Fehler {} ({}), mittel {}, Vorzeichen falsch {} ({} Register)",
                n, String.format("%.2e", maxErr), worst, String.format("%.2e", sumErr / n), signMismatch, direct.regCount[DensityProgram.STAGE_V]);
        return signMismatch <= n / 200;
    }

    /** Diagnose-Dump des Direktprogramms (nur mit -Dvulkanfish.dumpProgram). */
    private static void dumpProgram(DensityProgram p) {
        var c = p.code[DensityProgram.STAGE_V];
        String[] names = {"END", "CONST", "COORD", "ADD", "MUL", "MIN", "MAX", "ADDK", "MULK", "MAP", "CLAMP",
                "YGRAD", "NOISE", "SNOISE", "SHIFTA", "SHIFTB", "SHIFT", "RANGE", "INTERVAL", "SPLINE",
                "BLENDED", "LOADF", "LOADI", "STORE", "SUB", "DIV", "LERP"};
        StringBuilder b = new StringBuilder("Direktprogramm (consts=").append(p.consts.size()).append("):");
        for (int pc = 0; pc < c.size();) {
            int op = c.getInt(pc);
            String nm = op >= 0 && op < names.length ? names[op] : "OP" + op;
            int len = DensityProgram.length(c, pc);
            b.append("\n  @").append(pc).append(' ').append(nm);
            for (int i = 1; i < len && pc + i < c.size(); i++) b.append(' ').append(c.getInt(pc + i));
            if (len <= 0) break;
            pc += len;
            if (op == 0) break;
        }
        LOG.info("[vulkanfish] {}", b);
    }
}
