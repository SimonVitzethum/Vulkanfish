package simon.vulkanfish.client.mixin;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import simon.vulkanfish.client.lod.LodManager;

/** Chunk verlaesst den Client: letzten echten Stand ins LOD uebernehmen (bleibt als Fernfeld sichtbar). */
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {
    @Inject(method = "drop", at = @At("HEAD"))
    private void vulkanfish$captureForLod(ChunkPos pos, CallbackInfo ci) {
        LodManager lod = LodManager.instance();
        if (lod == null) return;
        LevelChunk chunk = ((ClientChunkCache) (Object) this).getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false);
        if (chunk != null) lod.captureLive(chunk);
    }
}
