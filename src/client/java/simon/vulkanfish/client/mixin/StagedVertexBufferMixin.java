package simon.vulkanfish.client.mixin;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draw-Merging im Upload (ein Draw pro RenderType statt pro Typwechsel): {@code upload()} vergibt
 * die Puffer-Offsets strikt in Listenreihenfolge – wer die Liste vorher umsortiert, bestimmt das
 * Layout. Gleiche Typen (identische Pipeline!) werden stabil aneinandergelegt und gefaltet:
 * Slices anhaengen, Zaehler summieren, gefaltete Draws leeren ({@code isEmpty} -> sie werden
 * ueberall uebersprungen, auch in den unberuehrten Gruppenlisten). Ergebnis pro Typ ein
 * zusammenhaengender Bereich = ein {@code drawFromBuffer} statt Dutzende.
 *
 * <p>Korrekt: Key ist die RenderType-Instanz (Singletons – niemals typuebergreifend!), plus
 * Format/Topologie/Sortierung. Stabile Faltung = identische Fragmentreihenfolge innerhalb eines
 * Typs; typuebergreifend aendert sich nur, was ohnehin beliebig (opaque/depth) oder normalisiert
 * (Upload-Sortierung) war. Unbekannte Typen (nicht unsere Hook-Gruppe: Partikel, GUI) bleiben
 * unberuehrt in Originalreihenfolge. Anker mit require = 0 (Optimierung crasht nie).
 */
@Mixin(StagedVertexBuffer.class)
public class StagedVertexBufferMixin {
    @Shadow
    private List<StagedVertexBuffer.Draw> draws;

    private record MergeKey(RenderType type, VertexFormat format, PrimitiveTopology topology, boolean sorted) {
    }

    @Inject(method = "upload", at = @At("HEAD"), require = 0)
    private void vulkanfish$mergeDraws(CallbackInfo ci) {
        if (draws.size() < 2) {
            simon.vulkanfish.client.render.EntityShadowCapture.takeDrawTypes();
            return;
        }
        Map<StagedVertexBuffer.Draw, RenderType> types =
                simon.vulkanfish.client.render.EntityShadowCapture.takeDrawTypes();
        // Nur falten (in place, Reihenfolge unveraendert): Jede ueberlebende Draw ist danach ein
        // zusammenhaengender Bereich, den der Upload direkt hintereinander kopiert – kein
        // Umsortieren noetig, Phasen-/Gruppenlisten bleiben unangetastet (geleerte Draws werden
        // dort per isEmpty uebersprungen).
        Map<MergeKey, StagedVertexBuffer.Draw> first = new LinkedHashMap<>();
        for (StagedVertexBuffer.Draw d : draws) {
            StagedDrawAccessor a = (StagedDrawAccessor) d;
            RenderType t = types.get(d);
            if (t == null) continue; // unbekannt/Partikel/GUI: unveraendert
            MergeKey k = new MergeKey(t, a.vulkanfish$format(), a.vulkanfish$topology(), a.vulkanfish$quadSorting() != null);
            StagedVertexBuffer.Draw f = first.get(k);
            if (f == null) {
                first.put(k, d);
                continue;
            }
            StagedDrawAccessor fa = (StagedDrawAccessor) f;
            fa.vulkanfish$slices().addAll(a.vulkanfish$slices());
            fa.vulkanfish$setVertexBufferSize(fa.vulkanfish$vertexBufferSize() + a.vulkanfish$vertexBufferSize());
            fa.vulkanfish$setVertexCount(fa.vulkanfish$vertexCount() + a.vulkanfish$vertexCount());
            fa.vulkanfish$setIndexCount(fa.vulkanfish$indexCount() + a.vulkanfish$indexCount());
            a.vulkanfish$slices().clear(); // leer -> isEmpty -> ueberall uebersprungen
            a.vulkanfish$setVertexBufferSize(0);
            a.vulkanfish$setVertexCount(0);
            a.vulkanfish$setIndexCount(0);
        }
    }
}
