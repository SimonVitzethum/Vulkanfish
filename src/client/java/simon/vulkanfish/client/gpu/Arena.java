package simon.vulkanfish.client.gpu;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.NativeResource;

/**
 * Scope-Allocator fuer kurzlebige native Structs (Vulkan-Create-Infos, Barrieren,
 * Deskriptoren): Bump-Allokation auf einem EIGENEN, pro Thread wiederverwendeten
 * MemoryStack -> keine malloc/free pro Frame.
 *
 * <p>Hintergrund: Mojangs Thread-Local-MemoryStack auf dem Render-Thread ist
 * unbrauchbar (fast voll bzw. Frame-Bilanz korrupt), darum ein separater Stack.
 * Arenen duerfen verschachtelt werden (push/pop). {@link #keep} bleibt fuer
 * heap-allokierte Ressourcen, die bei {@link #close} freigegeben werden.
 */
public final class Arena implements AutoCloseable {
    private static final ThreadLocal<MemoryStack> STACKS = ThreadLocal.withInitial(() -> MemoryStack.create(8 << 20));
    private final MemoryStack stack;
    private List<NativeResource> owned;

    public Arena() {
        this.stack = STACKS.get().push();
    }

    /** Der Stack dieses Scopes, fuer {@code VkFoo.calloc(arena.stack())}. */
    public MemoryStack stack() {
        return stack;
    }

    public LongBuffer mallocLong(int n) {
        return stack.mallocLong(n);
    }

    public IntBuffer mallocInt(int n) {
        return stack.mallocInt(n);
    }

    public PointerBuffer mallocPointer(int n) {
        return stack.mallocPointer(n);
    }

    public ByteBuffer malloc(int n) {
        return stack.malloc(n);
    }

    public FloatBuffer mallocFloat(int n) {
        return stack.mallocFloat(n);
    }

    public LongBuffer longs(long... v) {
        return stack.longs(v);
    }

    public IntBuffer ints(int... v) {
        return stack.ints(v);
    }

    public FloatBuffer floats(float... v) {
        return stack.floats(v);
    }

    public PointerBuffer pointers(long... v) {
        return stack.pointers(v);
    }

    public ByteBuffer utf8(String s) {
        return stack.UTF8(s);
    }

    /** Heap-allokierte Ressource bis zum Scope-Ende halten (Ausnahmefall; bevorzugt calloc(stack())). */
    public <T extends NativeResource> T keep(T r) {
        if (owned == null) owned = new ArrayList<>();
        owned.add(r);
        return r;
    }

    @Override
    public void close() {
        if (owned != null) {
            for (int i = owned.size() - 1; i >= 0; i--) {
                try {
                    owned.get(i).free();
                } catch (Throwable ignored) {
                }
            }
            owned = null;
        }
        stack.pop();
    }
}
