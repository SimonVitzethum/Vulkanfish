package simon.vulkanfish.client.mixin;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Zugriff auf Item-Layer-Quads fuer den Bake-Key (26.3: ItemQuads statt loser Listen).
 */
@Mixin(ItemStackRenderState.LayerRenderState.class)
public interface ItemLayerQuadsAccessor {
    @Accessor("quads")
    ItemQuads vulkanfish$quads();
}
