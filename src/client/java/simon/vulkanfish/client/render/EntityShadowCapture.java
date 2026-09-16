package simon.vulkanfish.client.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import simon.vulkanfish.client.mixin.RenderTypeAccessor;
import simon.vulkanfish.client.mixin.StagedDrawAccessor;
import simon.vulkanfish.client.mixin.StagedVertexBufferAccessor;

/**
 * Entity-Geometrie fuer den Schattenpass. Vanilla schreibt alle Entities, Items und Block-
 * Entities eines Frames in einen geteilten Vertexpuffer (StagedVertexBuffer), hochgeladen vor
 * unserem Terrain-Pass. Wir merken uns die Zeichnungen mit schattenwerfendem RenderType
 * (fest/ausgeschnitten, Ruestung, Items, bewegte Bloecke) und zeichnen genau diese Vertices
 * aus Vanillas Puffer in die Schattenkarte – ohne Kopie, auch fuer Entities anderer Mods.
 * Render-Thread.
 */
public final class EntityShadowCapture {
    /** Ein Bereich in Vanillas Vertexpuffer: Byte-Offset, Anzahl, Stride, Offset der Position. */
    public record Batch(long vkBuffer, long byteOffset, int vertexCount, int stride, int posOffset, boolean quads) {
    }

    private static final String[] CASTERS = {"entity_solid", "entity_cutout", "entity_cutout_no_cull",
            "entity_smooth_cutout", "entity_translucent", "entity_translucent_cull", "entity_alpha",
            "armor_", "item_", "solid_moving_block", "cutout_moving_block", "banner",
            "beacon_beam", "end_portal", "leash", "dragon"};

    private static boolean capturing;
    private static final List<StagedVertexBuffer.Draw> DRAWS = new ArrayList<>();
    private static final Set<StagedVertexBuffer.Draw> SEEN = Collections.newSetFromMap(new IdentityHashMap<>());
    private static List<Batch> frame = List.of();

    private EntityShadowCapture() {
    }

    /** Vor Vanillas prepareFrame der Welt (nicht der Hand/GUI). */
    public static void begin() {
        // Immer erfassen solange der GPU-Pfad aktiv ist: Vanillas Option
        // "Entity-Schatten" wuerde sonst unsere echten Schatten mit abschalten.
        // (Vanillas Blob-Schatten rendert der Mod ohnehin nicht.)
        capturing = true;
        DRAWS.clear();
        SEEN.clear();
    }

    public static void onDraw(StagedVertexBuffer.Draw draw, RenderType type) {
        if (!capturing || draw == null || SEEN.contains(draw)) return;
        String name = ((RenderTypeAccessor) type).vulkanfish$name();
        if (name.contains("emissive") || name.contains("glint")) return;
        for (String prefix : CASTERS) {
            if (name.startsWith(prefix)) {
                SEEN.add(draw);
                DRAWS.add(draw);
                return;
            }
        }
    }

    /** Nach prepareFrame: der Puffer ist hochgeladen, Offsets stehen fest. */
    public static void end(StagedVertexBuffer staged) {
        capturing = false;
        List<Batch> out = new ArrayList<>();
        if (DRAWS.isEmpty()) {
            frame = out;
            return;
        }
        GpuBuffer vb = ((StagedVertexBufferAccessor) staged).vulkanfish$vertexBuffer();
        if (vb instanceof VulkanGpuBuffer vk) {
            for (StagedVertexBuffer.Draw d : DRAWS) {
                StagedDrawAccessor a = (StagedDrawAccessor) d;
                int count = a.vulkanfish$vertexCount();
                if (count <= 0) continue;
                PrimitiveTopology topo = a.vulkanfish$topology();
                if (topo != PrimitiveTopology.QUADS && topo != PrimitiveTopology.TRIANGLES) continue;
                VertexFormat format = a.vulkanfish$format();
                VertexFormatElement pos = format.getElement("Position");
                if (pos == null) continue;
                out.add(new Batch(vk.vkBuffer(), a.vulkanfish$vertexOffset(), count, format.getVertexSize(), pos.offset(),
                        topo == PrimitiveTopology.QUADS));
            }
        }
        DRAWS.clear();
        SEEN.clear();
        frame = out;
    }

    /** Batches des aktuellen Frames (fuer den Schattenpass). */
    public static List<Batch> frame() {
        return frame;
    }
}
