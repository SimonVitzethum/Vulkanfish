package simon.vulkanfish.client.mixin;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.util.List;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(StagedVertexBuffer.Draw.class)
public interface StagedDrawAccessor {
    @Accessor("vertexOffset")
    int vulkanfish$vertexOffset();

    @Accessor("vertexCount")
    int vulkanfish$vertexCount();

    @Accessor("vertexCount")
    void vulkanfish$setVertexCount(int count);

    @Accessor("indexCount")
    int vulkanfish$indexCount();

    @Accessor("indexCount")
    void vulkanfish$setIndexCount(int count);

    @Accessor("vertexBufferSize")
    int vulkanfish$vertexBufferSize();

    @Accessor("vertexBufferSize")
    void vulkanfish$setVertexBufferSize(int size);

    @Accessor("format")
    VertexFormat vulkanfish$format();

    @Accessor("primitiveTopology")
    PrimitiveTopology vulkanfish$topology();

    @Accessor("quadSorting")
    VertexSorting vulkanfish$quadSorting();

    @Accessor("vertexBufferSlices")
    List<ByteBufferBuilder.Result> vulkanfish$slices();
}
