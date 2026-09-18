package simon.vulkanfish.client.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.gen.Invoker;
import simon.vulkanfish.client.gpu.TerrainStreamer;
import simon.vulkanfish.client.gpu.WorkerPool;

/**
 * Trichter aller Section-Invalidierungen (Blockaenderung, Licht, Chunk-Laden):
 * dieselben Sections, die Vanilla neu kompiliert, meshen wir neu.
 *
 * <p>Zusaetzlich paralleler Entity-Extract: Vanillas Schleife (sichtbar? Zustand bauen) laeuft
 * seriell auf dem Render-Thread – bei 2000+ Entities messbar. Pass 1 filtert seriell (Vanilla-
 * identisch, inkl. xOld-Sync), Pass 2 baut die Zustaende auf den Workern (reine Lesevorgaenge +
 * frische State-Objekte; dieselben Races gegen den Server-Thread wie Vanilla), Pass 3 haengt sie
 * in Originalreihenfolge an (Draw-Reihenfolge/Transparenz unveraendert). Submit bleibt bewusst
 * seriell: geteilter Collector, strikt geordnete Nodes, beliebiger Mod-Code in Feature-Renderern.
 * Abschaltbar: -Dvulkanfish.entityParallel=false. Anker mit require = 0 (Optimierung crasht nie).
 *
 * <p>Typ-Sortierung (Multidraw-Ersatz): executeGroup faehrt pro Draw einen vollen
 * Pipeline-Bind + Draw – Vanillas Konsolidierung (lastDraw/indexOf) greift nur bei gleichen Typen
 * hintereinander. 2000 gemischte Entities = ~1800 Draws. Stabile Sortierung nach EntityType
 * (Registrierungs-ID, deterministisch, innerhalb eines Typs bleibt die Ordnung) macht daraus
 * Dutzende – gleiche Fragmente, keine Reorder-Artefakte (Vanillas Transluzenz-Ordnung zwischen
 * Typen war ohnehin beliebig). Echtes vkCmd*Indirect geht nicht: Vanillas Backend duldet keine
 * fremden Commands (State-Tracking-Desync) – das Ziel (wenige Calls) ist so identisch erreicht.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    private static final Logger LOG = LoggerFactory.getLogger("vulkanfish");
    private static final boolean PARALLEL =
            !"false".equals(System.getProperty("vulkanfish.entityParallel", "true"));
    /** Frame-kritisch: laeuft vor allem Bauen (Worker sind sonst am Meshen). */
    private static final long PRIO_EXTRACT = Long.MIN_VALUE + (1L << 20);
    private static final int EXTRACT_TASKS =
            Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() / 2));
    private static boolean parallelLogged;
    /** Registrierungs-IDs der EntityTypen (Singletons, nie veraltet; nur Render-Thread). */
    private static final java.util.IdentityHashMap<EntityType<?>, Integer> TYPE_IDS = new java.util.IdentityHashMap<>();

    @Shadow
    private ClientLevel level;
    @Shadow
    private LevelRenderer levelRenderer;
    @Shadow
    private Minecraft minecraft;
    @Invoker("isEntityVisible")
    protected abstract boolean invokeIsEntityVisible(Entity entity, Frustum frustum, double camX, double camY, double camZ);
    @Invoker("extractEntity")
    protected abstract EntityRenderState invokeExtractEntity(Entity entity, float partialTickTime);

    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
    private void vulkanfish$sectionDirty(int sectionX, int sectionY, int sectionZ, boolean playerChanged, CallbackInfo ci) {
        TerrainStreamer.markDirty(sectionX, sectionY, sectionZ);
    }

    private static int typeId(Entity e) {
        EntityType<?> t = e.getType();
        Integer id = TYPE_IDS.get(t);
        if (id == null) {
            id = BuiltInRegistries.ENTITY_TYPE.getId(t);
            TYPE_IDS.put(t, id);
        }
        return id;
    }

    @Inject(method = "extractVisibleEntities", at = @At("HEAD"), cancellable = true, require = 0)
    private void vulkanfish$parallelExtract(Camera camera, Frustum frustum, DeltaTracker deltaTracker,
                                            LevelRenderState output, CallbackInfo ci) {
        var cameraPos = camera.position();
        double camX = cameraPos.x(), camY = cameraPos.y(), camZ = cameraPos.z();
        var tickRateManager = this.minecraft.level.tickRateManager();
        Entity.setViewScale(Mth.clamp(this.minecraft.options.getEffectiveRenderDistance() / 8.0, 1.0, 2.5)
                * this.minecraft.options.entityDistanceScaling().get());
        // Pass 1 (seriell, Vanilla-identisch): filtern + xOld-Sync
        List<Entity> vis = new ArrayList<>();
        for (Entity entity : this.level.entitiesForRendering()) {
            if (!this.invokeIsEntityVisible(entity, frustum, camX, camY, camZ)
                    || entity == camera.entity() && !camera.isDetached()
                    && (!(camera.entity() instanceof LivingEntity) || !((LivingEntity) camera.entity()).isSleeping())
                    || entity instanceof LocalPlayer && camera.entity() != entity)
                continue;
            if (entity.tickCount == 0) {
                entity.xOld = entity.getX();
                entity.yOld = entity.getY();
                entity.zOld = entity.getZ();
            }
            vis.add(entity);
        }
        if (vis.size() >= 64) {
            // Gleiche Typen hintereinander (stabil, deterministisch): aus ~1800 Draws werden Dutzende.
            vis.sort((a, b) -> Integer.compare(typeId(a), typeId(b)));
        }
        if (!PARALLEL || vis.size() < 32) {
            // Kleine Mengen: seriell wie Vanilla (kein Thread-Overhead)
            for (Entity entity : vis) {
                float partialEntity = deltaTracker.getGameTimeDeltaPartialTick(!tickRateManager.isEntityFrozen(entity));
                output.entityRenderStates.add(this.invokeExtractEntity(entity, partialEntity));
            }
            output.lastEntityRenderStateCount = output.entityRenderStates.size();
            ci.cancel();
            return;
        }
        // Pass 2 (Worker): Zustaende bauen
        int n = vis.size();
        EntityRenderState[] states = new EntityRenderState[n];
        int tasks = Math.min(EXTRACT_TASKS, n);
        CountDownLatch done = new CountDownLatch(tasks);
        AtomicReference<Throwable> error = new AtomicReference<>();
        int chunk = (n + tasks - 1) / tasks;
        for (int t = 0; t < tasks; t++) {
            final int from = t * chunk, to = Math.min(n, from + chunk);
            if (from >= to) {
                done.countDown();
                continue;
            }
            WorkerPool.submit(PRIO_EXTRACT, () -> {
                try {
                    for (int i = from; i < to; i++) {
                        Entity entity = vis.get(i);
                        float partial = deltaTracker.getGameTimeDeltaPartialTick(!tickRateManager.isEntityFrozen(entity));
                        states[i] = this.invokeExtractEntity(entity, partial);
                    }
                } catch (Throwable th) {
                    error.compareAndSet(null, th);
                } finally {
                    done.countDown();
                }
            });
        }
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        Throwable th = error.get();
        if (th instanceof RuntimeException re) throw re; // z. B. ReportedException wie bei Vanilla
        if (th != null) throw new RuntimeException(th);
        // Pass 3 (seriell): Originalreihenfolge wiederherstellen
        for (EntityRenderState state : states) output.entityRenderStates.add(state);
        output.lastEntityRenderStateCount = output.entityRenderStates.size();
        if (!parallelLogged) {
            parallelLogged = true;
            LOG.info("[vulkanfish] Entity-Extract parallel: {} Entities auf {} Tasks", n, tasks);
        }
        ci.cancel();
    }
}
