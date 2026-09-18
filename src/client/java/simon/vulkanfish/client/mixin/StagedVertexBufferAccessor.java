package simon.vulkanfish.client.mixin;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(StagedVertexBuffer.class)
public interface StagedVertexBufferAccessor {
    @Accessor("currentVertexBuffer")
    @Nullable GpuBuffer vulkanfish$vertexBuffer();
}
