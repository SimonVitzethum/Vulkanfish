package simon.vulkanfish.client.gpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chunk -&gt; Meshlet Pipeline.
 *
 * <p>Mesh-Shader-Only: Es gibt KEINEN klassischen Vertex-Buffer pro Chunk mehr.
 * Stattdessen werden Chunk-Sections in Meshlets (max 64 Verts / 124 Tris)
 * zerlegt, jeweils mit Bounding-Sphere + Normal-Cone + LOD-Fehler.
 * Der Task-Shader cullt (Frustum + Hi-Z + Cone + LOD), der Mesh-Shader
 * expandiert nur sichtbare Meshlets.
 *
 * <p>250 Chunks: Nahfeld (r&lt;32) volle Meshlets, Mittelfeld (32..96) 2:1
 * geclustert, Fernfeld (96..250) aggressive LOD-Cluster (8:1, nur Silhouette
 * + Farbe). Streaming per Transfer-Queue, Kompaktierung per Compute.
 * CPU macht nur: "Section x,z dirty" melden – Meshing/Clustern läuft auf GPU.
 */
public final class MeshletManager {
    private static final Logger LOG = LoggerFactory.getLogger("vulkanfish");
    private final GpuDrivenConfig config;
    private long meshletCount;

    public MeshletManager(GpuDrivenConfig config) {
        this.config = config;
    }

    public void markSectionDirty(int x, int y, int z) {
        // Nur in Dirty-Ringbuffer schreiben; GPU-Compute baut Meshlets um.
    }

    /**
     * Bobby-Fernfeld einspeisen: Chunk (x,z) mit LOD-Stufe aus Heightfield-
     * Snapshot. CPU kopiert nur Hoehen/Farben (16x16) in den Upload-Ring;
     * {@code lod_build.slang} erzeugt Cluster+Meshlet auf der GPU.
     */
    public void queueBobbyChunk(int x, int z, int lod, float[] heights, float[] colors) {
        // Ringbuffer-Push (Transfer-Queue laedt hoch, lodBuild baut).
    }

    public long meshletCount() {
        return meshletCount;
    }

    /** Screen-Space-Error-Schwelle für LOD-Wahl im Task-Shader. */
    public float lodErrorThreshold() {
        return 1.0f;
    }
}
