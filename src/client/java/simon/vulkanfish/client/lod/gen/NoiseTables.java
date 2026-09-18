package simon.vulkanfish.client.lod.gen;

import java.lang.reflect.Field;
import net.minecraft.world.level.levelgen.synth.GradientNoise;
import net.minecraft.world.level.levelgen.synth.NoiseStack;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import net.minecraft.world.level.levelgen.synth.SmearedPerlinNoise;

/**
 * Reflection-Zugriff auf Rausch-Tabellen (26.3): PerlinNoise haelt Permutation + Versatz in
 * der Basisklasse GradientNoise (protected), SmearedPerlinNoise den Y-Fudge privat, NoiseStack
 * die Lagen protected. Felder sind stabil (Konstruktor-Signaturen), ein Fehlschlag meldet
 * "unsupported" statt zu crashen.
 */
public final class NoiseTables {
    private NoiseTables() {
    }

    private static Field field(Class<?> cls, String name) throws NoSuchFieldException {
        Class<?> c = cls;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(cls.getName() + "." + name);
    }

    /** Permutationstabelle (256 Byte) eines PerlinNoise. */
    public static byte[] perms(PerlinNoise n) {
        try {
            return (byte[]) field(GradientNoise.class, "perms").get(n);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Versatz (xo, yo, zo) eines PerlinNoise. */
    public static double[] offsets(PerlinNoise n) {
        try {
            double[] o = new double[3];
            o[0] = field(GradientNoise.class, "offsetX").getDouble(n);
            o[1] = field(GradientNoise.class, "offsetY").getDouble(n);
            o[2] = field(GradientNoise.class, "offsetZ").getDouble(n);
            return o;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Y-Fudge-Skala eines SmearedPerlinNoise (BlendedNoise-Lagen). */
    public static double fudge(SmearedPerlinNoise n) {
        try {
            return field(SmearedPerlinNoise.class, "fudgeYScale").getDouble(n);
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    /** Lagen eines NoiseStacks (protected Feld + protected Typ – hier entkoppelt). */
    public record LayerData(GradientNoise noise, double frequency, double amplitude) {
    }

    /** Lagen eines NoiseStacks als eigene Records (Reflection bleibt hier gekapselt). */
    public static java.util.List<LayerData> stackLayers(NoiseStack stack) {
        try {
            Object[] layers = (Object[]) field(NoiseStack.class, "layers").get(stack);
            var out = new java.util.ArrayList<LayerData>(layers.length);
            var noiseM = layers.getClass().getComponentType().getMethod("noise");
            var freqM = layers.getClass().getComponentType().getMethod("frequency");
            var ampM = layers.getClass().getComponentType().getMethod("amplitude");
            for (Object layer : layers) {
                Object n = noiseM.invoke(layer);
                if (!(n instanceof GradientNoise gn)) return null;
                out.add(new LayerData(gn, (double) freqM.invoke(layer), (float) ampM.invoke(layer)));
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }
}
