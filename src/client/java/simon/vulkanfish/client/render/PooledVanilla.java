package simon.vulkanfish.client.render;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Gepoolte Vanilla-Temporaere fuer heisse Pfade: Extract laeuft pro Entity/Partikel pro Frame und
 * allokierte bisher Quaternionf + BlockPos millionenfach pro Sekunde (GC-Druck statt FPS).
 * Die Instanzen werden synchron verbraucht (sofort gelesen, nie behalten) – identische Semantik,
 * null Allokationen. NUR Render-Thread (Extract/Submit laufen dort einzeln).
 */
public final class PooledVanilla {
    private PooledVanilla() {
    }

    /** Quaternionf-Temporaer (Partikel-Facing). Der folgende &lt;init&gt; setzt Identity – wie frisch. */
    public static final Quaternionf QUAT = new Quaternionf();

    /** BlockPos-Temporaer (Licht-Lookups). */
    public static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();

    /** Wie {@code BlockPos.containing(x, y, z)}, ohne Allokation. */
    public static BlockPos containing(double x, double y, double z) {
        return POS.set(x, y, z);
    }

    /** Wie {@code BlockPos.containing(Vec3)}, ohne Allokation. */
    public static BlockPos containing(Vec3 v) {
        return POS.set(v.x(), v.y(), v.z());
    }
}
