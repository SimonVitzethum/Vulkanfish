package simon.vulkanfish.client.lod.gen;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.floats.FloatList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.CubicSpline;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.generator.ConstantFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.GradientFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.NoiseFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.ShiftNoiseFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.BlendDensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.CacheFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.ClampFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.InterpolatedFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.IntervalSelectFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.LerpFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.RangeChoiceFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.SplineFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.UnaryFunction;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NoiseStack;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import net.minecraft.world.level.levelgen.synth.SmearedPerlinNoise;

/**
 * Uebersetzt einen (geseedeten) DensityFunction-Baum der Welt – inkl. aller Datapack-
 * Aenderungen, denn es ist genau der Baum aus dem RandomState des Servers – in lineare
 * Register-Programme fuer die GPU. Drei Stufen wie Vanillas NoiseChunk:
 * <ul>
 *   <li>F: flat_cache-Teilbaeume am Quart-Raster (x,z Vielfache von 4, y = 0)</li>
 *   <li>C: interpolated-Teilbaeume an den Zellecken (4 x 8 x 4 Bloecke)</li>
 *   <li>V: der Rest pro Block; interpolated = trilinear aus C, flat_cache = Quart aus F</li>
 * </ul>
 * Gemeinsame Teilbaeume (gleiches Objekt) werden je Stufe nur einmal berechnet (= cache_once).
 * Mit {@code direct = true} entsteht ein einstufiges Programm ohne Caches – exakt die
 * Semantik von {@code DensityFunction.compute}, zum Pruefen gegen Vanilla.
 *
 * <p>26.3: Vanillas DensityFunction-Baeume sind Records (direkte Zugriffe, keine Mixins mehr);
 * das Rauschen sind NoiseStack-Lagen aus PerlinNoise (gleiche Gitter-/Gradienten-Mathematik wie
 * zuvor, Permutationen je Oktave). BlendedNoise mischt drei Fbm-Stapel (SmearedPerlinNoise mit
 * eingebautem Y-Fudge) per lerp(clamp(main + 0.5)).
 */
public final class DensityProgram {
    // ---- Opcodes (identisch in worldgen.slang) ----
    public static final int OP_END = 0, OP_CONST = 1, OP_COORD = 2, OP_ADD = 3, OP_MUL = 4, OP_MIN = 5, OP_MAX = 6,
            OP_ADDK = 7, OP_MULK = 8, OP_MAP = 9, OP_CLAMP = 10, OP_YGRAD = 11, OP_NOISE = 12, OP_SNOISE = 13,
            OP_SHIFTA = 14, OP_SHIFTB = 15, OP_SHIFT = 16, OP_RANGE = 17, OP_INTERVAL = 18, OP_SPLINE = 19,
            OP_BLENDED = 20, OP_LOADF = 21, OP_LOADI = 22, OP_STORE = 23,
            OP_SUB = 24, OP_DIV = 25, OP_LERP = 26;
    public static final int STAGE_F = 0, STAGE_C = 1, STAGE_V = 2, STAGE_D = 3; // D: direkt (Klima), ohne Caches
    public static final int CONST_REF = 0x80000000;

    // ---- Ergebnis ----
    public final IntArrayList[] code = {new IntArrayList(), new IntArrayList(), new IntArrayList(), new IntArrayList()};
    public final int[] regCount = new int[4];
    /** Ergebnisregister der direkten Stufe D (z. B. die 6 Klimaparameter). */
    public int[] directResults = new int[0];
    public final FloatArrayList consts = new FloatArrayList();
    public int flatSlots, interpSlots;
    public int resultReg = -1;
    // Noise-Tabellen (26.3: PerlinNoise-Lagen aus NoiseStacks; Fudge je Oktave fuers Smearing)
    public final List<PerlinNoise> improved = new ArrayList<>();
    public final List<double[]> octaves = new ArrayList<>(); // {tableIdx, frequenz, amplitude, fudge}
    public final IntArrayList normals = new IntArrayList();  // je Normal: start, anzahl (flache Lagen)
    public final List<Double> normalFactor = new ArrayList<>(); // 1.0 (Normierung steckt in den Amplituden)
    public final List<double[]> blended = new ArrayList<>(); // [xzMul, yMul, xzFac, yFac, limitSmear, mainSmear,
    // minStart, minCount, maxStart, maxCount, mainStart, mainCount] (Oktav-Bereiche)
    public final List<String> unsupported = new ArrayList<>();

    private final boolean direct;
    private RandomState randomState;
    private long seed;
    private boolean useLegacyRandom;
    private final Map<Object, Integer>[] memo = new Map[]{new IdentityHashMap<>(), new IdentityHashMap<>(), new IdentityHashMap<>(), new IdentityHashMap<>()};
    private final Map<Object, Integer> flatSlot = new IdentityHashMap<>();
    private final Map<Object, Integer> interpSlot = new IdentityHashMap<>();
    private final Map<Object, Integer> normalIdx = new IdentityHashMap<>();
    private final Map<Object, Integer> improvedIdx = new IdentityHashMap<>();
    private final Map<Object, Integer> blendedIdx = new IdentityHashMap<>();
    private final int[] nextVreg = new int[4];

    private DensityProgram(boolean direct) {
        this.direct = direct;
    }

    /** Gestuftes GPU-Programm fuer die Dichte (finalDensity des Routers). */
    public static DensityProgram compile(DensityFunction finalDensity, List<DensityFunction> directFns, RandomState rs,
                                         long seed, boolean useLegacyRandom) {
        DensityProgram p = new DensityProgram(false);
        p.randomState = rs;
        p.seed = seed;
        p.useLegacyRandom = useLegacyRandom;
        p.resultReg = p.node(finalDensity, STAGE_V);
        p.directResults = new int[directFns.size()];
        for (int i = 0; i < directFns.size(); i++) p.directResults[i] = p.node(directFns.get(i), STAGE_D);
        p.allocate();
        p.validateTables();
        return p;
    }

    /** Einstufig, ohne Caches: Referenz gegen Vanillas compute(). */
    public static DensityProgram compileDirect(DensityFunction f, RandomState rs, long seed, boolean useLegacyRandom) {
        DensityProgram p = new DensityProgram(true);
        p.randomState = rs;
        p.seed = seed;
        p.useLegacyRandom = useLegacyRandom;
        p.resultReg = p.node(f, STAGE_V);
        p.allocate();
        p.validateTables();
        return p;
    }

    /**
     * Typ-Schluessel wie im Registry (26.3: per instanceof statt Codec-Roundtrip – schneller
     * und ohne Registry-Abhaengigkeit; Strings bleiben die Codec-Pfade).
     */
    private static String type(DensityFunction df) {
        if (df instanceof InterpolatedFunction) return "interpolated";
        if (df instanceof CacheFunction) return "cache";
        if (df instanceof BlendDensityFunction) return "blend_density";
        if (df instanceof DensityFunctions.HolderHolder) return "holder";
        if (df instanceof ConstantFunction) return "constant";
        if (df instanceof BinaryFunction bf) return bf.type().name().toLowerCase(java.util.Locale.ROOT);
        if (df instanceof UnaryFunction uf) return uf.type().name().toLowerCase(java.util.Locale.ROOT);
        if (df instanceof ClampFunction) return "clamp";
        if (df instanceof GradientFunction) return "gradient";
        if (df instanceof NoiseFunction) return "noise";
        if (df instanceof ShiftNoiseFunction.ShiftA) return "shift_a";
        if (df instanceof ShiftNoiseFunction.ShiftB) return "shift_b";
        if (df instanceof ShiftNoiseFunction.Shift) return "shift";
        if (df instanceof RangeChoiceFunction) return "range_choice";
        if (df instanceof IntervalSelectFunction) return "interval_select";
        if (df instanceof LerpFunction) return "lerp";
        if (df instanceof SplineFunction) return "spline";
        if (df instanceof BlendedNoise) return "old_blended_noise";
        try {
            Identifier id = BuiltInRegistries.DENSITY_FUNCTION_TYPE.getKey(df.codec());
            if (id != null) return id.getPath();
        } catch (Throwable ignored) {
        }
        return df.getClass().getSimpleName();
    }

    private int emit(int stage, int op, int... args) {
        int dst = nextVreg[stage]++;
        IntArrayList c = code[stage];
        c.add(op);
        c.add(dst);
        for (int a : args) c.add(a);
        return dst;
    }

    private int k(double v) {
        consts.add((float) v);
        return consts.size() - 1;
    }

    private int node(DensityFunction df, int stage) {
        if (df instanceof DensityFunctions.HolderHolder h) return node(h.function().value(), stage);
        Integer m = memo[stage].get(df);
        if (m != null) return m;
        int r = build(df, stage);
        memo[stage].put(df, r);
        return r;
    }

    private static boolean isZero(DensityFunction f) {
        return f instanceof ConstantFunction c && c.value() == 0.0f;
    }

    private int build(DensityFunction df, int stage) {
        String t = type(df);
        switch (t) {
            case "interpolated": {
                DensityFunction inner = ((InterpolatedFunction) df).input();
                if (direct || stage != STAGE_V) return node(inner, stage); // D/F/C: direkt
                Integer slot = interpSlot.get(inner);
                if (slot == null) {
                    int v = node(inner, STAGE_C);
                    slot = interpSlots++;
                    code[STAGE_C].add(OP_STORE);
                    code[STAGE_C].add(slot);
                    code[STAGE_C].add(v);
                    interpSlot.put(inner, slot);
                }
                return emit(stage, OP_LOADI, slot);
            }
            case "cache", "blend_density":
                // cache = reine Memoisierung (macht unser memo), blend_density = Kontext-Markierung
                return node(((df instanceof CacheFunction c) ? c.input() : ((BlendDensityFunction) df).input()), stage);
            case "blend_alpha":
                return emit(stage, OP_CONST, k(1.0));
            case "blend_offset", "beardifier":
                return emit(stage, OP_CONST, k(0.0));
            case "constant":
                return emit(stage, OP_CONST, k(((ConstantFunction) df).value()));
            case "add", "mul", "min", "max", "sub", "div": {
                var bf = (BinaryFunction) df;
                // Konstanten-Faltung wie frueher MulOrAdd (rechte Konstante reicht den Shadern)
                if ((t.equals("add") || t.equals("mul")) && bf.right() instanceof ConstantFunction kc) {
                    int a = node(bf.left(), stage);
                    return emit(stage, t.equals("mul") ? OP_MULK : OP_ADDK, a, k(kc.value()));
                }
                int a = node(bf.left(), stage);
                int b = node(bf.right(), stage);
                int op = switch (t) {
                    case "add" -> OP_ADD;
                    case "mul" -> OP_MUL;
                    case "min" -> OP_MIN;
                    case "max" -> OP_MAX;
                    case "sub" -> OP_SUB;
                    default -> OP_DIV;
                };
                return emit(stage, op, a, b);
            }
            case "abs", "square", "cube", "sqrt", "half_negative", "quarter_negative",
                    "reciprocal", "negate", "squeeze", "log", "sign": {
                var uf = (UnaryFunction) df;
                int a = node(uf.input(), stage);
                int mapType = switch (t) {
                    case "abs" -> 0;
                    case "square" -> 1;
                    case "cube" -> 2;
                    case "half_negative" -> 3;
                    case "quarter_negative" -> 4;
                    case "reciprocal" -> 5;
                    case "negate" -> 7;
                    case "sqrt" -> 8;
                    case "log" -> 9;
                    case "sign" -> 10;
                    default -> 6; // squeeze
                };
                return emit(stage, OP_MAP, a, mapType);
            }
            case "clamp": {
                var c = (ClampFunction) df;
                return emit(stage, OP_CLAMP, node(c.input(), stage), k(c.min()), k(c.max()));
            }
            case "gradient": {
                var g = (GradientFunction) df;
                // Vanilla nutzt nur Y + Clamp (Bauart wie y_clamped_gradient)
                if (g.axis() != net.minecraft.core.Direction.Axis.Y
                        || g.tiling() != net.minecraft.world.level.levelgen.densityfunction.TilingMode.CLAMP_TO_EDGE) {
                    break;
                }
                return emit(stage, OP_YGRAD, k(g.fromCoordinate()), k(g.toCoordinate()), k(g.fromValue()), k(g.toValue()));
            }
            case "noise": {
                // 26.3: Shifts stecken im selben Record (null-Konstanten = unverschoben)
                var n = (NoiseFunction) df;
                boolean plain = isZero(n.shiftX()) && isZero(n.shiftY()) && isZero(n.shiftZ());
                if (plain) {
                    return emit(stage, OP_NOISE, normal(n.noise()), k(n.xzScale()), k(n.yScale()));
                }
                int sx = node(n.shiftX(), stage), sy = node(n.shiftY(), stage), sz = node(n.shiftZ(), stage);
                return emit(stage, OP_SNOISE, normal(n.noise()), sx, sy, sz, k(n.xzScale()), k(n.yScale()));
            }
            case "shift_a":
                return emit(stage, OP_SHIFTA, normal(((ShiftNoiseFunction.ShiftA) df).offsetNoise()));
            case "shift_b":
                return emit(stage, OP_SHIFTB, normal(((ShiftNoiseFunction.ShiftB) df).offsetNoise()));
            case "shift":
                return emit(stage, OP_SHIFT, normal(((ShiftNoiseFunction.Shift) df).offsetNoise()));
            case "range_choice": {
                var r = (RangeChoiceFunction) df;
                int in = node(r.input(), stage);
                int a = node(r.whenInRange(), stage);
                int b = node(r.whenOutOfRange(), stage);
                return emit(stage, OP_RANGE, in, k(r.minInclusive()), k(r.maxExclusive()), a, b);
            }
            case "interval_select": {
                var s = (IntervalSelectFunction) df;
                int in = node(s.input(), stage);
                FloatList thr = s.thresholds();
                int kOff = consts.size();
                for (int i = 0; i < thr.size(); i++) consts.add(thr.getFloat(i));
                List<DensityFunction> fs = s.functions();
                int[] args = new int[3 + fs.size()];
                args[0] = in;
                args[1] = fs.size();
                args[2] = kOff;
                for (int i = 0; i < fs.size(); i++) args[3 + i] = node(fs.get(i), stage);
                return emit(stage, OP_INTERVAL, args);
            }
            case "lerp": {
                var l = (LerpFunction) df;
                int a = node(l.alpha(), stage);
                int f = node(l.first(), stage);
                int s = node(l.second(), stage);
                return emit(stage, OP_LERP, a, f, s);
            }
            case "spline":
                return spline(((SplineFunction) df).spline(), stage);
            case "old_blended_noise":
                return emit(stage, OP_BLENDED, blended((BlendedNoise) df));
            default:
                break;
        }
        if (!unsupported.contains(t)) unsupported.add(t);
        return emit(stage, OP_CONST, k(0.0));
    }

    /** Verschachtelte Splines: Kinder zuerst (gerade Linie), Werte als Register oder Konstanten. */
    private int spline(CubicSpline<SplineFunction.Coordinate> s, int stage) {
        if (s instanceof CubicSpline.Constant<SplineFunction.Coordinate> c) {
            return emit(stage, OP_CONST, k(c.value()));
        }
        var mp = (CubicSpline.Multipoint<SplineFunction.Coordinate>) s;
        int coord = node(mp.coordinate().function(), stage);
        int n = mp.locations().length;
        int kOff = consts.size();
        for (float l : mp.locations()) consts.add(l);
        for (float d : mp.derivatives()) consts.add(d);
        int[] args = new int[3 + n];
        args[0] = coord;
        args[1] = n;
        args[2] = kOff;
        for (int i = 0; i < n; i++) {
            CubicSpline<SplineFunction.Coordinate> v = mp.values().get(i);
            if (v instanceof CubicSpline.Constant<SplineFunction.Coordinate> cv) {
                args[3 + i] = CONST_REF | k(cv.value());
            } else {
                args[3 + i] = spline(v, stage);
            }
        }
        return emit(stage, OP_SPLINE, args);
    }

    /** Normal-Rauschen: Lagen des NoiseStacks (Frequenz + Amplitude fix und fertig). */
    private int normal(Holder<NormalNoise> holder) {
        ResourceKey<NormalNoise> key = holder.unwrapKey().orElse(null);
        if (key == null || randomState == null) {
            if (!unsupported.contains("unbound-noise")) unsupported.add("unbound-noise");
            return 0;
        }
        Integer idx = normalIdx.get(holder);
        if (idx != null) return idx;
        Noise noise = randomState.getOrCreateNoise(key);
        if (!(noise instanceof NoiseStack stack)) {
            if (!unsupported.contains("non-stack-noise")) unsupported.add("non-stack-noise");
            return 0;
        }
        java.util.List<NoiseTables.LayerData> layers = NoiseTables.stackLayers(stack);
        if (layers == null) {
            if (!unsupported.contains("noise-layers")) unsupported.add("noise-layers");
            return 0;
        }
        int start = octaves.size();
        for (NoiseTables.LayerData layer : layers) {
            // Normale Stapel sind glatt (PerlinNoise); Smear faellt hier nie an (Fudge 0)
            if (!(layer.noise() instanceof PerlinNoise pn) || layer.noise() instanceof SmearedPerlinNoise) {
                if (!unsupported.contains("non-perlin-layer")) unsupported.add("non-perlin-layer");
                return 0;
            }
            octaves.add(new double[]{improved(pn), layer.frequency(), layer.amplitude(), 0.0});
        }
        idx = normalFactor.size();
        normals.add(start);
        normals.add(octaves.size() - start);
        normalFactor.add(1.0); // Normierung steckt in den Amplituden
        normalIdx.put(holder, idx);
        return idx;
    }

    private int improved(PerlinNoise n) {
        Integer idx = improvedIdx.get(n);
        if (idx != null) return idx;
        idx = improved.size();
        improved.add(n);
        improvedIdx.put(n, idx);
        return idx;
    }

    /**
     * BlendedNoise (26.3): drei Fbm-Stapel aus createFbmSet (Vanilla-Zufall) + 5 Skalare.
     * Ergebnis: lerp(clamp(main + 0.5), min, max); Smear steckt je Lage (Fudge).
     */
    private int blended(BlendedNoise b) {
        Integer idx = blendedIdx.get(b);
        if (idx != null) return idx;
        if (randomState == null) {
            if (!unsupported.contains("unbound-blend")) unsupported.add("unbound-blend");
            return 0;
        }
        RandomSource rnd = blendRandom();
        NoiseStack min = null, max = null, main = null;
        try {
            var set = b.createFbmSet(rnd);
            min = set.minLimitNoise();
            max = set.maxLimitNoise();
            main = set.mainNoise();
        } catch (Throwable t) {
            if (!unsupported.contains("fbm-set")) unsupported.add("fbm-set");
            return 0;
        }
        double xzMul = 684.412 * b.xzScale(), yMul = 684.412 * b.yScale();
        double limitSmear = yMul * b.smearScaleMultiplier(), mainSmear = limitSmear / b.yFactor();
        int minStart = octaves.size();
        appendStack(min);
        int minCount = octaves.size() - minStart;
        int maxStart = octaves.size();
        appendStack(max);
        int maxCount = octaves.size() - maxStart;
        int mainStart = octaves.size();
        appendStack(main);
        int mainCount = octaves.size() - mainStart;
        double[] d = new double[]{xzMul, yMul, b.xzFactor(), b.yFactor(), limitSmear, mainSmear,
                minStart, minCount, maxStart, maxCount, mainStart, mainCount};
        idx = blended.size();
        blended.add(d);
        blendedIdx.put(b, idx);
        return idx;
    }

    private void appendStack(NoiseStack stack) {
        java.util.List<NoiseTables.LayerData> layers = NoiseTables.stackLayers(stack);
        if (layers == null) return;
        for (NoiseTables.LayerData layer : layers) {
            if (!(layer.noise() instanceof PerlinNoise pn)) continue;
            // Smear steckt je Lage (SmearedPerlinNoise); glatte Lagen haben Fudge 0
            double fudge = pn instanceof SmearedPerlinNoise spn ? NoiseTables.fudge(spn) : 0.0;
            octaves.add(new double[]{improved(pn), layer.frequency(), layer.amplitude(), fudge});
        }
    }

    /** Vanillas Zufall fuer createFbmSet (BlendedNoise.NOISE_SEED "minecraft:terrain"). */
    private RandomSource blendRandom() {
        if (useLegacyRandom) return new LegacyRandomSource(seed);
        try {
            java.lang.reflect.Field f = RandomState.class.getDeclaredField("random");
            f.setAccessible(true);
            Object factory = f.get(randomState);
            return (RandomSource) factory.getClass().getMethod("fromHashOf", Identifier.class)
                    .invoke(factory, BlendedNoise.NOISE_SEED);
        } catch (Throwable t) {
            // Notfall: anderer Seed-Strom (faellt in validate() auf, kein stiller Fehler)
            return new LegacyRandomSource(seed + 0x9E3779B9L);
        }
    }

    /** Tabellen greifbar (Reflection ok)? Sonst ehrlich "unsupported" (vanillaOnly-Pfad). */
    private void validateTables() {
        for (PerlinNoise n : improved) {
            if (NoiseTables.perms(n) == null || NoiseTables.offsets(n) == null) {
                if (!unsupported.contains("noise-tables")) unsupported.add("noise-tables");
                return;
            }
        }
        for (double[] o : octaves) {
            if (Double.isNaN(o[3])) {
                if (!unsupported.contains("noise-tables")) unsupported.add("noise-tables");
                return;
            }
        }
    }

    // ---------- Registervergabe: virtuelle Register -> wenige physische (Lebensdauer) ----------

    private void allocate() {
        for (int s = 0; s < 4; s++) {
            IntArrayList c = code[s];
            c.add(OP_END);
            int vregs = nextVreg[s];
            int[] lastUse = new int[vregs];
            java.util.Arrays.fill(lastUse, -1);
            // 1. letzte Verwendung jedes virtuellen Registers
            for (int pc = 0; c.getInt(pc) != OP_END; ) {
                int op = c.getInt(pc);
                int[] reads = readSlots(c, pc);
                for (int r : reads) {
                    int v = c.getInt(r);
                    if ((v & CONST_REF) == 0) lastUse[v] = pc;
                }
                pc += length(c, pc);
            }
            if (s == STAGE_V && resultReg >= 0) lastUse[resultReg] = Integer.MAX_VALUE;
            if (s == STAGE_D) for (int r : directResults) lastUse[r] = Integer.MAX_VALUE;
            // 2. physische Register vergeben, nach letzter Nutzung freigeben
            int[] phys = new int[vregs];
            java.util.Arrays.fill(phys, -1);
            java.util.ArrayDeque<Integer> free = new java.util.ArrayDeque<>();
            int maxPhys = 0;
            for (int pc = 0; c.getInt(pc) != OP_END; ) {
                int op = c.getInt(pc);
                int len = length(c, pc);
                int[] reads = readSlots(c, pc);
                for (int r : reads) {
                    int v = c.getInt(r);
                    if ((v & CONST_REF) == 0) lastUse[v] = pc;
                }
                // Quellen, deren Leben hier endet, freigeben (Ziel darf sie wiederverwenden)
                for (int r : reads) {
                    int orig = findOrig(phys, c.getInt(r));
                    if (orig >= 0 && lastUse[orig] == pc) {
                        free.push(phys[orig]);
                        lastUse[orig] = -2;
                    }
                }
                if (op != OP_STORE) {
                    int v = c.getInt(pc + 1);
                    int p = free.isEmpty() ? maxPhys++ : free.pop();
                    phys[v] = p;
                    c.set(pc + 1, p);
                    if (lastUse[v] == -1) free.push(p); // nie gelesen
                }
                pc += len;
            }
            if (s == STAGE_V && resultReg >= 0) resultReg = phys[resultReg];
            if (s == STAGE_D) for (int i = 0; i < directResults.length; i++) directResults[i] = phys[directResults[i]];
            regCount[s] = maxPhys;
        }
    }

    private static int findOrig(int[] phys, int p) {
        // physisches Register -> aktuell gebundenes virtuelles (linear, nur beim Kompilieren)
        for (int v = phys.length - 1; v >= 0; v--) if (phys[v] == p) return v;
        return -1;
    }

    /** Positionen (Indizes in code) der gelesenen Register-Operanden. */
    static int[] readSlots(IntArrayList c, int pc) {
        int op = c.getInt(pc);
        return switch (op) {
            case OP_ADD, OP_MUL, OP_MIN, OP_MAX, OP_SUB, OP_DIV -> new int[]{pc + 2, pc + 3};
            case OP_ADDK, OP_MULK, OP_MAP, OP_CLAMP -> new int[]{pc + 2};
            case OP_SNOISE -> new int[]{pc + 3, pc + 4, pc + 5};
            case OP_RANGE -> new int[]{pc + 2, pc + 5, pc + 6};
            case OP_LERP -> new int[]{pc + 2, pc + 3, pc + 4};
            case OP_INTERVAL -> {
                int n = c.getInt(pc + 3);
                int[] r = new int[1 + n];
                r[0] = pc + 2;
                for (int i = 0; i < n; i++) r[1 + i] = pc + 5 + i;
                yield r;
            }
            case OP_SPLINE -> {
                int n = c.getInt(pc + 3);
                int[] r = new int[1 + n];
                r[0] = pc + 2;
                for (int i = 0; i < n; i++) r[1 + i] = pc + 5 + i;
                yield r;
            }
            case OP_STORE -> new int[]{pc + 2};
            default -> new int[0];
        };
    }

    static int length(IntArrayList c, int pc) {
        int op = c.getInt(pc);
        return switch (op) {
            case OP_CONST, OP_COORD, OP_LOADF, OP_LOADI, OP_SHIFTA, OP_SHIFTB, OP_SHIFT, OP_BLENDED -> 3;
            case OP_ADD, OP_MUL, OP_MIN, OP_MAX, OP_SUB, OP_DIV, OP_ADDK, OP_MULK, OP_MAP, OP_STORE -> op == OP_STORE ? 3 : 4;
            case OP_CLAMP, OP_NOISE, OP_LERP -> 5;
            case OP_YGRAD -> 6;
            case OP_SNOISE -> 8;
            case OP_RANGE -> 7;
            case OP_INTERVAL, OP_SPLINE -> 5 + c.getInt(pc + 3);
            default -> 1;
        };
    }

    public String stats() {
        return String.format("F %d Worte/%d Reg, C %d Worte/%d Reg, V %d Worte/%d Reg, D %d Worte/%d Reg; %d flat, %d interp; %d Normal-, %d Oktav-Lagen, %d Perlin, %d Blended; nicht unterstuetzt: %s",
                code[0].size(), regCount[0], code[1].size(), regCount[1], code[2].size(), regCount[2], code[3].size(), regCount[3], flatSlots, interpSlots,
                normalFactor.size(), octaves.size(), improved.size(), blended.size(), unsupported);
    }
}
