package simon.vulkanfish.client.lod.gen;

import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Worldgen-Quelle fuer das LOD: der echte RandomState + Generator der Welt (Einzelspieler:
 * direkt vom integrierten Server – inklusive Datapacks). Kompiliert die Dichtefunktion fuer
 * die GPU und prueft das Programm einmal gegen Vanillas eigene Auswertung.
 */
public final class WorldgenSource {
    private static final Logger LOG = LoggerFactory.getLogger("vulkanfish");

    public final ServerLevel level;
    public final RandomState randomState;
    public final NoiseBasedChunkGenerator generator;
    public final NoiseGeneratorSettings settings;
    public final DensityProgram program;
    public final boolean valid;

    private WorldgenSource(ServerLevel level, RandomState rs, NoiseBasedChunkGenerator gen, NoiseGeneratorSettings settings,
                           DensityProgram program, boolean valid) {
        this.level = level;
        this.randomState = rs;
        this.generator = gen;
        this.settings = settings;
        this.program = program;
        this.valid = valid;
    }

    /** Einzelspieler: Server-Welt passend zur Client-Dimension, sonst null. */
    public static WorldgenSource forClientLevel(ClientLevel client) {
        try {
            var server = Minecraft.getInstance().getSingleplayerServer();
            if (server == null || client == null) return null;
            ServerLevel sl = server.getLevel(client.dimension());
            if (sl == null) return null;
            ChunkGenerator g = sl.getChunkSource().getGenerator();
            if (!(g instanceof NoiseBasedChunkGenerator gen)) {
                LOG.info("[vulkanfish] LOD-Generator: {} ist kein Noise-Generator – nur echte Chunks", g.getClass().getSimpleName());
                return null;
            }
            RandomState rs = sl.getChunkSource().randomState();
            NoiseGeneratorSettings settings = gen.generatorSettings().value();
            long t0 = System.nanoTime();
            var sampler = rs.sampler();
            DensityProgram prog = DensityProgram.compile(rs.router().finalDensity(), java.util.List.of(sampler.temperature(),
                    sampler.humidity(), sampler.continentalness(), sampler.erosion(), sampler.depth(), sampler.weirdness()));
            LOG.info("[vulkanfish] LOD-Generator: Dichte kompiliert in {} ms: {}", (System.nanoTime() - t0) / 1_000_000, prog.stats());
            boolean ok = prog.unsupported.isEmpty() && validate(rs.router().finalDensity());
            return new WorldgenSource(sl, rs, gen, settings, prog, ok);
        } catch (Throwable t) {
            LOG.warn("[vulkanfish] LOD-Generator nicht verfuegbar", t);
            return null;
        }
    }

    /** Einstufiges Programm gegen Vanillas compute() an Zufallspunkten. */
    private static boolean validate(DensityFunction f) {
        DensityProgram direct = DensityProgram.compileDirect(f);
        if (!direct.unsupported.isEmpty()) {
            LOG.warn("[vulkanfish] LOD-Generator: nicht unterstuetzte Dichtefunktionen {}", direct.unsupported);
            return false;
        }
        DensityCpu cpu = new DensityCpu(direct);
        Random rnd = new Random(1234);
        double maxErr = 0, sumErr = 0;
        int n = 400, signMismatch = 0;
        for (int i = 0; i < n; i++) {
            int x = rnd.nextInt(20000) - 10000, y = rnd.nextInt(384) - 64, z = rnd.nextInt(20000) - 10000;
            double ref = f.compute(new DensityFunction.SinglePointContext(x, y, z));
            double got = cpu.evalDirect(x, y, z);
            double err = Math.abs(ref - got);
            maxErr = Math.max(maxErr, err);
            sumErr += err;
            if ((ref > 0) != (got > 0)) signMismatch++;
        }
        LOG.info("[vulkanfish] LOD-Generator: Programm vs. Vanilla an {} Punkten: max. Fehler {}, mittel {}, Vorzeichen falsch {} ({} Register)",
                n, String.format("%.2e", maxErr), String.format("%.2e", sumErr / n), signMismatch, direct.regCount[DensityProgram.STAGE_V]);
        return signMismatch <= n / 200;
    }
}
