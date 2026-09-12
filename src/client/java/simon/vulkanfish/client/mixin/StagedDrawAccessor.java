package simon.vulkanfish.client.mixin;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(StagedVertexBuffer.Draw.class)
public interface StagedDrawAccessor {
    @Accessor("vertexOffset")
    int vulkanfish$vertexOffset();

    @Accessor("vertexCount")
    int vulkanfish$vertexCount();

    @Accessor("format")
    VertexFormat vulkanfish$format();

    @Accessor("primitiveTopology")
    PrimitiveTopology vulkanfish$topology();
}
