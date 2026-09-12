package simon.vulkanfish.client.lod.gen;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.CubicSpline;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import simon.vulkanfish.client.mixin.worldgen.BlendedNoiseAccessor;
import simon.vulkanfish.client.mixin.worldgen.DfClampAccessor;
import simon.vulkanfish.client.mixin.worldgen.DfConstantAccessor;
import simon.vulkanfish.client.mixin.worldgen.DfIntervalSelectAccessor;
import simon.vulkanfish.client.mixin.worldgen.DfMappedAccessor;
import simon.vulkanfish.client.mixin.worldgen.DfMulOrAddAccessor;
import simon.vulkanfish.client.mixin.worldgen.DfNoiseAccessor;
import simon.vulkanfish.client.mixin.worldgen.DfRangeChoiceAccessor;
import simon.vulkanfish.client.mixin.worldgen.DfShiftAAccessor;
import simon.vulkanfish.client.mixin.worldgen.DfShiftAccessor;
import simon.vulkanfish.client.mixin.worldgen.DfShiftBAccessor;
import simon.vulkanfish.client.mixin.worldgen.DfShiftedNoiseAccessor;
import simon.vulkanfish.client.mixin.worldgen.DfYGradientAccessor;
import simon.vulkanfish.client.mixin.worldgen.ImprovedNoiseAccessor;
import simon.vulkanfish.client.mixin.worldgen.NormalNoiseAccessor;
import simon.vulkanfish.client.mixin.worldgen.PerlinNoiseAccessor;

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
 */
public final class DensityProgram {
    // ---- Opcodes (identisch in worldgen.slang) ----
    public static final int OP_END = 0, OP_CONST = 1, OP_COORD = 2, OP_ADD = 3, OP_MUL = 4, OP_MIN = 5, OP_MAX = 6,
            OP_ADDK = 7, OP_MULK = 8, OP_MAP = 9, OP_CLAMP = 10, OP_YGRAD = 11, OP_NOISE = 12, OP_SNOISE = 13,
            OP_SHIFTA = 14, OP_SHIFTB = 15, OP_SHIFT = 16, OP_RANGE = 17, OP_INTERVAL = 18, OP_SPLINE = 19,
            OP_BLENDED = 20, OP_LOADF = 21, OP_LOADI = 22, OP_STORE = 23;
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
    // Noise-Tabellen
    public final List<ImprovedNoise> improved = new ArrayList<>();
    public final List<double[]> octaves = new ArrayList<>(); // {improvedIdx, inputFactor, valueFactor}
    public final IntArrayList normals = new IntArrayList();  // je Noise: firstStart, firstCount, secondStart, secondCount
    public final List<Double> normalFactor = new ArrayList<>();
    public final List<double[]> blended = new ArrayList<>(); // Skalare + 40 Oktav-Indizes (-1 = keine)
    public final List<String> unsupported = new ArrayList<>();

    private final boolean direct;
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
    public static DensityProgram compile(DensityFunction finalDensity) {
        return compile(finalDensity, java.util.List.of());
    }

    /** Dichte (gestuft) + direkt ausgewertete Zusatzfunktionen (Stufe D, z. B. Klima) in einem Programm. */
    public static DensityProgram compile(DensityFunction finalDensity, List<DensityFunction> direct) {
        DensityProgram p = new DensityProgram(false);
        p.resultReg = p.node(finalDensity, STAGE_V);
        p.directResults = new int[direct.size()];
        for (int i = 0; i < direct.size(); i++) p.directResults[i] = p.node(direct.get(i), STAGE_D);
        p.allocate();
        return p;
    }

    /** Einstufig, ohne Caches: Referenz gegen Vanillas compute(). */
    public static DensityProgram compileDirect(DensityFunction f) {
        DensityProgram p = new DensityProgram(true);
        p.resultReg = p.node(f, STAGE_V);
        p.allocate();
        return p;
    }

    private static String type(DensityFunction df) {
        try {
            Identifier id = BuiltInRegistries.DENSITY_FUNCTION_TYPE.getKey(df.codec().codec());
            return id == null ? df.getClass().getSimpleName() : id.getPath();
        } catch (UnsupportedOperationException e) {
            return "holder";
        }
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

    private int build(DensityFunction df, int stage) {
        String t = type(df);
        switch (t) {
            case "interpolated": {
                DensityFunction inner = ((DensityFunctions.MarkerOrMarked) df).wrapped();
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
            case "flat_cache": {
                DensityFunction inner = ((DensityFunctions.MarkerOrMarked) df).wrapped();
                // D (Klima) liest die F-Werte mit: der Punkt liegt dort immer auf dem F-Gitter
                if (direct || stage == STAGE_F) return node(inner, stage);
                Integer slot = flatSlot.get(inner);
                if (slot == null) {
                    int v = node(inner, STAGE_F);
                    slot = flatSlots++;
                    code[STAGE_F].add(OP_STORE);
                    code[STAGE_F].add(slot);
                    code[STAGE_F].add(v);
                    flatSlot.put(inner, slot);
                }
                return emit(stage, OP_LOADF, slot);
            }
            case "cache_2d", "cache_once", "cache_all_in_cell", "blend_density":
                return node(((DensityFunctions.MarkerOrMarked) df).wrapped(), stage);
            case "blend_alpha":
                return emit(stage, OP_CONST, k(1.0));
            case "blend_offset", "beardifier":
                return emit(stage, OP_CONST, k(0.0));
            case "constant":
                return emit(stage, OP_CONST, k(((DfConstantAccessor) df).vf$Value()));
            case "add", "mul", "min", "max": {
                if (df instanceof DfMulOrAddAccessor mo) {
                    int a = node(mo.vf$Input(), stage);
                    boolean mul = t.equals("mul");
                    return emit(stage, mul ? OP_MULK : OP_ADDK, a, k(mo.vf$Argument()));
                }
                var two = (DensityFunctions.TwoArgumentSimpleFunction) df;
                int a = node(two.argument1(), stage);
                int b = node(two.argument2(), stage);
                int op = switch (t) {
                    case "add" -> OP_ADD;
                    case "mul" -> OP_MUL;
                    case "min" -> OP_MIN;
                    default -> OP_MAX;
                };
                return emit(stage, op, a, b);
            }
            case "abs", "square", "cube", "half_negative", "quarter_negative", "invert", "squeeze": {
                DfMappedAccessor mp = (DfMappedAccessor) df;
                int a = node(mp.vf$Input(), stage);
                int mapType = switch (t) {
                    case "abs" -> 0;
                    case "square" -> 1;
                    case "cube" -> 2;
                    case "half_negative" -> 3;
                    case "quarter_negative" -> 4;
                    case "invert" -> 5;
                    default -> 6; // squeeze
                };
                return emit(stage, OP_MAP, a, mapType);
            }
            case "clamp": {
                DfClampAccessor c = (DfClampAccessor) df;
                return emit(stage, OP_CLAMP, node(c.vf$Input(), stage), k(c.vf$MinValue()), k(c.vf$MaxValue()));
            }
            case "y_clamped_gradient": {
                DfYGradientAccessor g = (DfYGradientAccessor) df;
                return emit(stage, OP_YGRAD, k(g.vf$FromY()), k(g.vf$ToY()), k(g.vf$FromValue()), k(g.vf$ToValue()));
            }
            case "noise": {
                DfNoiseAccessor n = (DfNoiseAccessor) df;
                return emit(stage, OP_NOISE, normal(n.vf$Noise().noise()), k(n.vf$XzScale()), k(n.vf$YScale()));
            }
            case "shifted_noise": {
                DfShiftedNoiseAccessor n = (DfShiftedNoiseAccessor) df;
                int sx = node(n.vf$ShiftX(), stage), sy = node(n.vf$ShiftY(), stage), sz = node(n.vf$ShiftZ(), stage);
                return emit(stage, OP_SNOISE, normal(n.vf$Noise().noise()), sx, sy, sz, k(n.vf$XzScale()), k(n.vf$YScale()));
            }
            case "shift_a":
                return emit(stage, OP_SHIFTA, normal(((DfShiftAAccessor) df).vf$OffsetNoise().noise()));
            case "shift_b":
                return emit(stage, OP_SHIFTB, normal(((DfShiftBAccessor) df).vf$OffsetNoise().noise()));
            case "shift":
                return emit(stage, OP_SHIFT, normal(((DfShiftAccessor) df).vf$OffsetNoise().noise()));
            case "range_choice": {
                DfRangeChoiceAccessor r = (DfRangeChoiceAccessor) df;
                int in = node(r.vf$Input(), stage);
                int a = node(r.vf$WhenInRange(), stage);
                int b = node(r.vf$WhenOutOfRange(), stage);
                return emit(stage, OP_RANGE, in, k(r.vf$MinInclusive()), k(r.vf$MaxExclusive()), a, b);
            }
            case "interval_select": {
                DfIntervalSelectAccessor s = (DfIntervalSelectAccessor) df;
                int in = node(s.vf$Input(), stage);
                DoubleList thr = s.vf$Thresholds();
                int kOff = consts.size();
                for (int i = 0; i < thr.size(); i++) consts.add((float) thr.getDouble(i));
                List<DensityFunction> fs = s.vf$Functions();
                int[] args = new int[3 + fs.size()];
                args[0] = in;
                args[1] = fs.size();
                args[2] = kOff;
                for (int i = 0; i < fs.size(); i++) args[3 + i] = node(fs.get(i), stage);
                return emit(stage, OP_INTERVAL, args);
            }
            case "spline":
                return spline(((DensityFunctions.Spline) df).spline(), stage);
            case "old_blended_noise":
                return emit(stage, OP_BLENDED, blended((BlendedNoise) df));
            default:
                if (!unsupported.contains(t)) unsupported.add(t);
                return emit(stage, OP_CONST, k(0.0));
        }
    }

    /** Verschachtelte Splines: Kinder zuerst (gerade Linie), Werte als Register oder Konstanten. */
    private int spline(CubicSpline<DensityFunctions.Spline.Coordinate> s, int stage) {
        if (s instanceof CubicSpline.Constant<DensityFunctions.Spline.Coordinate> c) {
            return emit(stage, OP_CONST, k(c.value()));
        }
        var mp = (CubicSpline.Multipoint<DensityFunctions.Spline.Coordinate>) s;
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
            CubicSpline<DensityFunctions.Spline.Coordinate> v = mp.values().get(i);
            if (v instanceof CubicSpline.Constant<DensityFunctions.Spline.Coordinate> cv) {
                args[3 + i] = CONST_REF | k(cv.value());
            } else {
                args[3 + i] = spline(v, stage);
            }
        }
        return emit(stage, OP_SPLINE, args);
    }

    private int normal(NormalNoise n) {
        if (n == null) {
            if (!unsupported.contains("unbound-noise")) unsupported.add("unbound-noise");
            return 0;
        }
        Integer idx = normalIdx.get(n);
        if (idx != null) return idx;
        NormalNoiseAccessor a = (NormalNoiseAccessor) n;
        int[] first = perlin(a.vf$First());
        int[] second = perlin(a.vf$Second());
        idx = normalFactor.size();
        normals.add(first[0]);
        normals.add(first[1]);
        normals.add(second[0]);
        normals.add(second[1]);
        normalFactor.add(a.vf$ValueFactor());
        normalIdx.put(n, idx);
        return idx;
    }

    /** Oktaven einer PerlinNoise: Eingangs- und Wertfaktor pro Oktave vorab ausgerechnet. */
    private int[] perlin(PerlinNoise p) {
        PerlinNoiseAccessor a = (PerlinNoiseAccessor) p;
        ImprovedNoise[] levels = a.vf$NoiseLevels();
        DoubleList amps = a.vf$Amplitudes();
        double factor = a.vf$LowestFreqInputFactor();
        double valueFactor = a.vf$LowestFreqValueFactor();
        int start = octaves.size();
        for (int i = 0; i < levels.length; i++) {
            if (levels[i] != null) octaves.add(new double[]{improved(levels[i]), factor, amps.getDouble(i) * valueFactor});
            factor *= 2.0;
            valueFactor /= 2.0;
        }
        return new int[]{start, octaves.size() - start};
    }

    private int improved(ImprovedNoise n) {
        Integer idx = improvedIdx.get(n);
        if (idx != null) return idx;
        idx = improved.size();
        improved.add(n);
        improvedIdx.put(n, idx);
        return idx;
    }

    /** BlendedNoise: 8 Skalare, dann je 16 min/max- und 8 Haupt-Oktaven (getOctaveNoise-Reihenfolge). */
    private int blended(BlendedNoise b) {
        Integer idx = blendedIdx.get(b);
        if (idx != null) return idx;
        BlendedNoiseAccessor a = (BlendedNoiseAccessor) b;
        double[] d = new double[8 + 40];
        d[0] = a.vf$XzMultiplier();
        d[1] = a.vf$YMultiplier();
        d[2] = a.vf$XzFactor();
        d[3] = a.vf$YFactor();
        d[4] = a.vf$SmearScaleMultiplier();
        for (int i = 0; i < 16; i++) {
            d[8 + i] = octave(a.vf$MinLimitNoise(), i);
            d[24 + i] = octave(a.vf$MaxLimitNoise(), i);
        }
        for (int i = 0; i < 8; i++) d[40 + i] = octave(a.vf$MainNoise(), i);
        idx = blended.size();
        blended.add(d);
        blendedIdx.put(b, idx);
        return idx;
    }

    private int octave(PerlinNoise p, int i) {
        ImprovedNoise n = p.getOctaveNoise(i);
        return n == null ? -1 : improved(n);
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
                    if ((v & CONST_REF) != 0) continue;
                    c.set(r, phys[v]);
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
            case OP_ADD, OP_MUL, OP_MIN, OP_MAX -> new int[]{pc + 2, pc + 3};
            case OP_ADDK, OP_MULK, OP_MAP, OP_CLAMP -> new int[]{pc + 2};
            case OP_SNOISE -> new int[]{pc + 3, pc + 4, pc + 5};
            case OP_RANGE -> new int[]{pc + 2, pc + 5, pc + 6};
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
            case OP_ADD, OP_MUL, OP_MIN, OP_MAX, OP_ADDK, OP_MULK, OP_MAP, OP_STORE -> op == OP_STORE ? 3 : 4;
            case OP_CLAMP -> 5;
            case OP_NOISE -> 5;
            case OP_YGRAD -> 6;
            case OP_SNOISE -> 8;
            case OP_RANGE -> 7;
            case OP_INTERVAL, OP_SPLINE -> 5 + c.getInt(pc + 3);
            default -> 1;
        };
    }

    public String stats() {
        return String.format("F %d Worte/%d Reg, C %d Worte/%d Reg, V %d Worte/%d Reg, D %d Worte/%d Reg; %d flat, %d interp; %d Normal-, %d Perlin-Oktaven, %d Improved, %d Blended; nicht unterstuetzt: %s",
                code[0].size(), regCount[0], code[1].size(), regCount[1], code[2].size(), regCount[2], code[3].size(), regCount[3], flatSlots, interpSlots,
                normalFactor.size(), octaves.size(), improved.size(), blended.size(), unsupported);
    }
}
