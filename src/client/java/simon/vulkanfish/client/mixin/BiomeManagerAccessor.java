package simon.vulkanfish.client.mixin;

import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Gehashter Seed, den der Server schickt (Pruefung eines eingestellten Seeds im Mehrspieler). */
@Mixin(BiomeManager.class)
public interface BiomeManagerAccessor {
    @Accessor("biomeZoomSeed")
    long vulkanfish$zoomSeed();
}
