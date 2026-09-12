package simon.vulkanfish.client.gpu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entity-Archetypen: ein BLAS / ein Meshlet-Block pro Modell-Typ statt pro
 * Entity. 30k Zombies = 1 Archetyp-Geometrie, 30k Instanz-Transforms.
 *
 * <p>v1 baeckt pro Archetyp genau ein Meshlet (&lt;=64 Verts / 124 Tris):
 * CUBE (Fallback), LOW humanoide Box-Geometrie (Spieler/Zombie/Skelett-Naeherung
 * aus 3 Quadern). Echte EntityModel-Parts (Baking aus
 * {@code EntityModelPart}-Wuerfeln) sind als naechster Schritt vorbereitet:
 * {@link #bakeFromBoxes} nimmt bereits Box-Listen im Model-Raum.
 */
public final class ArchetypeStore {
    private static final Logger LOG = LoggerFactory.getLogger("vulkanfish");

    /** CPU-spiegelnde Box im Model-Raum (min/max + Farbe). */
    public record ModelBox(float minX, float minY, float minZ,
                           float maxX, float maxY, float maxZ,
                           float r, float g, float b) {}

    private final Map<String, Integer> indexByName = new LinkedHashMap<>();
    private final List<List<ModelBox>> boxesByArchetype = new ArrayList<>();

    public int register(String name, List<ModelBox> boxes) {
        return indexByName.computeIfAbsent(name, k -> {
            boxesByArchetype.add(List.copyOf(boxes));
            return boxesByArchetype.size() - 1;
        });
    }

    /** Baeckt Box-Listen zu einem Meshlet (12 Tris pro Box, deduplizierte Ecken). */
    public BakedMeshlet bakeFromBoxes(List<ModelBox> boxes) {
        List<Float> pos = new ArrayList<>();
        List<Float> nrm = new ArrayList<>();
        List<Float> col = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        for (ModelBox b : boxes) {
            // 6 Flaechen als unabhaengige Quads (24 Verts), Normalen pro Flaeche
            float[][] corners = {
                {b.minX(), b.minY(), b.minZ()}, {b.maxX(), b.minY(), b.minZ()},
                {b.maxX(), b.maxY(), b.minZ()}, {b.minX(), b.maxY(), b.minZ()},
                {b.minX(), b.minY(), b.maxZ()}, {b.maxX(), b.minY(), b.maxZ()},
                {b.maxX(), b.maxY(), b.maxZ()}, {b.minX(), b.maxY(), b.maxZ()},
            };
            int[][] faces = {{0, 1, 2, 3}, {5, 4, 7, 6}, {4, 0, 3, 7},
                             {1, 5, 6, 2}, {4, 5, 1, 0}, {3, 2, 6, 7}};
            float[][] normals = {{0, 0, -1}, {0, 0, 1}, {-1, 0, 0},
                                 {1, 0, 0}, {0, -1, 0}, {0, 1, 0}};
            for (int f = 0; f < 6; f++) {
                int base = pos.size() / 3;
                for (int k = 0; k < 4; k++) {
                    float[] c = corners[faces[f][k]];
                    pos.add(c[0]); pos.add(c[1]); pos.add(c[2]);
                    nrm.add(normals[f][0]); nrm.add(normals[f][1]); nrm.add(normals[f][2]);
                    col.add(b.r()); col.add(b.g()); col.add(b.b());
                }
                idx.add(base); idx.add(base + 1); idx.add(base + 2);
                idx.add(base); idx.add(base + 2); idx.add(base + 3);
            }
        }
        return new BakedMeshlet(toFloatArray(pos), toFloatArray(nrm), toFloatArray(col),
                idx.stream().mapToInt(Integer::intValue).toArray());
    }

    public record BakedMeshlet(float[] positions, float[] normals, float[] colors, int[] indices) {
        public int vertexCount() {
            return positions.length / 3;
        }

        public int triCount() {
            return indices.length / 3;
        }
    }

    /** Standard-Archetypen registrieren (einmal beim Start). */
    public void registerDefaults() {
        register("cube", List.of(new ModelBox(-0.5f, 0f, -0.5f, 0.5f, 1f, 0.5f, 1f, 1f, 1f)));
        // stark vereinfachter Humanoid (Torso + Kopf), 2 Boxen = 48 Verts / 24 Tris
        register("humanoid_low", List.of(
                new ModelBox(-0.3f, 0.7f, -0.15f, 0.3f, 1.5f, 0.15f, 0.9f, 0.85f, 0.8f),
                new ModelBox(-0.2f, 1.5f, -0.2f, 0.2f, 1.9f, 0.2f, 0.95f, 0.8f, 0.65f)));
        LOG.info("[vulkanfish] Archetypen: {}", indexByName.keySet());
    }

    public int archetypeCount() {
        return boxesByArchetype.size();
    }

    public int indexOf(String name) {
        return indexByName.getOrDefault(name, 0);
    }

    public List<ModelBox> boxesOf(int archetype) {
        return boxesByArchetype.get(archetype);
    }

    private static float[] toFloatArray(List<Float> l) {
        float[] a = new float[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }
}
