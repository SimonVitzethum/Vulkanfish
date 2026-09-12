package simon.vulkanfish.client.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanillas Datafixer fuer gespeicherte Chunks (alte Weltversionen) auch fuer das LOD. */
@Mixin(ChunkMap.class)
public interface ChunkMapInvoker {
    @Invoker("upgradeChunkTag")
    CompoundTag vulkanfish$upgradeChunkTag(CompoundTag tag);
}
