package simon.vulkanfish.client.mixin;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Zugriff auf Item-Layer fuer den Bake-Key (erste Quad-Identitaet + Layer-Zahl).
 * Fail-fast bei Vanilla-Umbenennung (besser als stilles Fehlverhalten).
 */
@Mixin(ItemStackRenderState.class)
public interface ItemStackRenderStateAccessor {
    @Accessor("layers")
    ItemStackRenderState.LayerRenderState[] vulkanfish$layers();

    @Accessor("activeLayerCount")
    int vulkanfish$activeLayerCount();
}
