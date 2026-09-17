package simon.vulkanfish.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * Entity-Staffelung gegen Massen-Szenen (z. B. TNT-Felder auf Servern): Ferne Entities werden nicht
 * jeden Frame extrahiert/submittet – unter 48 Bloecken jeder Frame, bis 128 jeder zweite, darueber
 * jeder vierte. Extract + Submit + Draw kosten so nur noch anteilig; aus der Ferne ist das unsichtbar
 * (ein Frame Versatz bei 60 FPS). Spieler, leuchtende Entities (Umriss-Pass) und der Enderdrache sind
 * ausgenommen. Render-Thread. Abschaltbar: -Dvulkanfish.entityStagger=false.
 */
public final class EntityStagger {
    private static final boolean ON = !"false".equals(System.getProperty("vulkanfish.entityStagger", "true"));
    private static long frame;
    private static long skipped;

    private EntityStagger() {
    }

    /** Pro Frame einmal aus extractVisibleEntities (neue Frame-Nummer). */
    public static void nextFrame() {
        if (ON) frame++;
    }

    /** true = diesen Frame extrahieren/submitten (aus isEntityVisible). */
    public static boolean visible(Entity e) {
        if (!ON || e == null) return true;
        if (e instanceof net.minecraft.world.entity.player.Player) return true;
        if (e.isCurrentlyGlowing()) return true;
        if (e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon) return true;
        var mc = Minecraft.getInstance();
        if (mc.gameRenderer == null) return true;
        var cam = mc.gameRenderer.mainCamera();
        if (cam == null) return true;
        double d2 = e.distanceToSqr(cam.position());
        if (d2 < 48.0 * 48.0) return true;
        long gate = d2 < 128.0 * 128.0 ? 1 : 3;
        if (((frame + e.getId()) & gate) == 0) return true;
        skipped++;
        return false;
    }

    /** Uebersprungene Extracts seit dem letzten Abruf (Diagnose im CPU-Log). */
    public static long takeSkipped() {
        long s = skipped;
        skipped = 0;
        return s;
    }
}
