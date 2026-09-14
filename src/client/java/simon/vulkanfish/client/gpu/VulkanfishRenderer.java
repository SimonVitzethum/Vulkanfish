package simon.vulkanfish.client.gpu;

import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Einstiegspunkt, der aus Mixins aufgerufen wird.
 *
 * <p>26.2 rendert ueber Blaze3D {@code LevelRenderer.render(...)} mit
 * Vulkan-Backend. Der Mod klinkt sich dort ein:
 * <ol>
 *   <li>{@code LevelRenderer.render} HEAD: Kamera/Himmel erfassen, Terrain-
 *       Streamer ticken (Dirty-Sections -> Worker-Meshing).</li>
 *   <li>{@code ChunkSectionsToRender.renderGroup(OPAQUE)}: statt Vanillas
 *       Opaque-Terrain den nativen Frame (Upload, Hi-Z, Meshlet-Cull,
 *       Mesh-Draw, Composite) in Mojangs Submission haengen; Farbe + Tiefe
 *       landen im Main-Target, Vanilla rendert danach Entities/Translucent.</li>
 *   <li>Fallback: fehlen Mesh-Shader oder schlaegt etwas fehl, rendert
 *       Vanilla unveraendert.</li>
 * </ol>
 */
public final class VulkanfishRenderer {
    private static final Logger LOG = LoggerFactory.getLogger("vulkanfish");
    private static final boolean SNAPSHOTS = Boolean.getBoolean("vulkanfish.snapshots");
    private static final long EXIT_AFTER_FRAMES = Long.getLong("vulkanfish.exitAfterFrames", 0L);
    private static final boolean TEST_WORLD_EDITS = Boolean.getBoolean("vulkanfish.testWorldEdits");
    private static final boolean SCENE_TEST = Boolean.getBoolean("vulkanfish.sceneTest");
    private static final boolean LOD_TEST = Boolean.getBoolean("vulkanfish.lodTest");
    private static final boolean ICE_TEST = Boolean.getBoolean("vulkanfish.iceTest");
    private static final boolean CAVE_TEST = Boolean.getBoolean("vulkanfish.caveTest");
    private static final boolean LOD_RETURN = Boolean.getBoolean("vulkanfish.lodReturn");
    private static final boolean NEAR_TEST = Boolean.getBoolean("vulkanfish.nearTest");
    private static final boolean SHADOW_TEST = Boolean.getBoolean("vulkanfish.shadowTest");
    private static final boolean FG_TEST = Boolean.getBoolean("vulkanfish.fgTest");
    private static final boolean RR_TEST = Boolean.getBoolean("vulkanfish.rrTest");
    private static final boolean BOBBY_TEST = Boolean.getBoolean("vulkanfish.bobbyTest");
    private static final boolean ENTITY_TEST = Boolean.getBoolean("vulkanfish.entityTest");
    private static final boolean LIGHT_TEST = Boolean.getBoolean("vulkanfish.lightTest");
    private static final String BIOME_TEST = System.getProperty("vulkanfish.biomeTest"); // z. B. deep_frozen_ocean
    // Startort des Nahfeld-Tests (-Dvulkanfish.nearX/Z, z. B. 10000000 fuer die Genauigkeit bei hohen Koordinaten)
    private static final int NEAR_X = Integer.getInteger("vulkanfish.nearX", 34);
    private static final int NEAR_Z = Integer.getInteger("vulkanfish.nearZ", -69);
    // Vanilla baut SOLID/CUTOUT nicht mehr, solange wir das Opaque-Terrain liefern (SectionCompilerMixin)
    private static volatile boolean vanillaOpaqueDisabled;
    private final GpuDrivenConfig config;
    private final AsyncFrameGraph frameGraph;
    private final BlazeDeviceInterop device;
    private final BobbyInterop bobby;
    private boolean initialized;

    public VulkanfishRenderer(GpuDrivenConfig config, AsyncFrameGraph frameGraph, BlazeDeviceInterop device, BobbyInterop bobby) {
        this.config = config;
        this.frameGraph = frameGraph;
        this.device = device;
        this.bobby = bobby;
    }

    private final SlangShaderLoader shaderLoader = new SlangShaderLoader();
    private NativePassRunner nativeRunner;
    private TerrainStreamer streamer;
    private simon.vulkanfish.client.lod.LodManager lod;
    private boolean initAttempted;
    private boolean shutDown;
    private NativePassRunner.FrameUniformsData pendingFrame;
    private long pendingScreenshot = -1;
    /** Selbsttest: Screenshot inkl. GUI (beim Blit auf die Swapchain statt am Ende des Level-Renderns). */
    private static volatile long pendingGuiScreenshot = -1;

    /** Aus VulkanGpuSurfaceMixin vor dem Blit: das Hauptziel enthaelt jetzt auch die GUI. */
    public static void beforeSwapchainBlit() {
        long tag = pendingGuiScreenshot;
        if (tag < 0) return;
        pendingGuiScreenshot = -1;
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.Screenshot.grab(mc.gameDirectory, mc.gameRenderer.mainRenderTarget(),
                msg -> LOG.info("[vulkanfish] Selbsttest-Screenshot {}: {}", tag, msg.getString()));
    }
    // CPU-Zeit unseres Codes pro Frame (Render-Thread), Log alle 10 s
    private long cpuNanosStart, cpuNanosTerrain, cpuNanosWater, cpuNanosTaa;
    private int cpuFrames;
    private long cpuLogMs;

    public void init() {
        // Nur Buchhaltung am Client-Start; Device-Probe passiert lazy im
        // ersten Frame (siehe ensureInitialized), weil beim Mod-Init noch
        // kein Blaze3D-Device existiert.
        frameGraph.init();
    }

    /** Idempotent, Render-Thread. Einmaliger Aufbau sobald Device da ist. */
    private synchronized void ensureInitialized() {
        if (initAttempted || shutDown) return;
        initAttempted = true;
        // 1. Native Handles aus Blaze3D (VulkanDevice -> LWJGL). Ohne Device kein Pfad.
        if (!device.probe()) {
            LOG.warn("[vulkanfish] Lazy-Init: noch kein Device – neuer Versuch naechster Frame");
            initAttempted = false; // spaeter erneut versuchen
            return;
        }
        // 2. Mesh-Shader muessen auf MOJANGS Device aktiv sein (VulkanBackendMixin)
        boolean meshPath = config.enableMeshShaders() && device.supportsMeshShading()
                && !Boolean.getBoolean("vulkanfish.vanillaCompare"); // Selbsttest: Vanillas Bild zum Vergleich
        if (!meshPath) {
            LOG.warn("[vulkanfish] Mesh-Shading auf Mojangs Device nicht aktiv -> Vanilla-Pfad");
            return;
        }
        // 3. Bobby optional (Fernfeld-Quelle fuer 250-Chunk-LOD).
        boolean bobbyOn = config.enableBobbyFarField() && bobby.probe();
        try {
            shaderLoader.load();
            LOG.info("[vulkanfish] {} Slang-SPIR-V-Module geladen", shaderLoader.moduleCount());
        } catch (Exception e) {
            LOG.warn("[vulkanfish] SPIR-V fehlt (compileSlang noch nicht gelaufen?), Fallback auf Vanilla-Pfad", e);
            return;
        }
        // 5. Native Pipelines/Puffer auf Mojangs Device (Push-Deskriptoren wie Blaze3D).
        nativeRunner = new NativePassRunner(device, config.enableHizCulling());
        if (!nativeRunner.init(shaderLoader)) {
            LOG.warn("[vulkanfish] Nativ deaktiviert ({}), Vanilla rendert", nativeRunner.disableReason());
            nativeRunner.destroy(); // bis dahin angelegte Objekte nicht auf Mojangs Device liegen lassen
            nativeRunner = null;
            return;
        }
        streamer = new TerrainStreamer(nativeRunner);
        simon.vulkanfish.client.VulkanfishSettings.initDefaults(config);
        if (config.enableLod() && nativeRunner.lodReady()) {
            lod = new simon.vulkanfish.client.lod.LodManager(simon.vulkanfish.client.VulkanfishSettings.lodChunks(),
                    simon.vulkanfish.client.VulkanfishSettings.lodPixelError());
            nativeRunner.setLod(lod);
            lod.setNearField(streamer);
            nativeRunner.setLodGpuBudget(simon.vulkanfish.client.VulkanfishSettings.lodGpuMs());
            lod.setRunner(nativeRunner);
            FrameDataCapture.lodFogDistance = lod.farBlocks();
            LOG.info("[vulkanfish] LOD-Fernfeld: {} Chunks, max. {} px Fehler pro Voxel, {} Worker-Threads, GPU-Budget {} ms",
                    simon.vulkanfish.client.VulkanfishSettings.lodChunks(), simon.vulkanfish.client.VulkanfishSettings.lodPixelError(),
                    WorkerPool.threads(), simon.vulkanfish.client.VulkanfishSettings.lodGpuMs());
        }
        initialized = true;
        vanillaOpaqueDisabled = true;
        LOG.info("[vulkanfish] GPU-driven renderer init: mesh={} hiz={} rt={} (Pass folgt) bobby={}",
                meshPath, config.enableHizCulling(), device.supportsRaytracing(), bobbyOn);
    }

    /** Pro Frame aus LevelRendererMixin (Render-Thread, vor Vanillas Frame-Graph). */
    public void onFrameStart(net.minecraft.client.renderer.state.level.CameraRenderState cameraState,
                             net.minecraft.client.renderer.state.level.SkyRenderState skyState) {
        ensureInitialized();
        pendingFrame = null;
        if (!initialized || !nativeRunner.isReady()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || cameraState == null) return;
        long t0 = System.nanoTime();
        applyLiveSettings();
        streamer.tick(mc.level, cameraState.pos.x, cameraState.pos.y, cameraState.pos.z);
        if (lod != null) {
            // Winkel eines Pixels: 2*tan(fov/2)/Hoehe = 2/(m11*Hoehe)
            float pixelAngle = FrameDataCapture.hasProjection()
                    ? 2.0f / (Math.max(FrameDataCapture.projectionM11(), 0.01f) * Math.max(mc.getWindow().getHeight(), 1)) : 0f;
            lod.tick(mc.level, cameraState.pos.x, cameraState.pos.y, cameraState.pos.z, pixelAngle,
                    mc.options.getEffectiveRenderDistance());
        }
        pendingFrame = FrameDataCapture.capture(frameGraph.currentFrame(), cameraState, skyState,
                NativePassRunner.SHADOW_RES);
        cpuNanosStart += System.nanoTime() - t0;
        if (streamer.overflowed() && vanillaOpaqueDisabled && lod == null) {
            // GPU-Scene voll und kein Fernfeld, das entfernte Sections uebernimmt: Vanilla muss die
            // restlichen Sections wieder selbst meshen (Hybrid, dort ohne unser Licht)
            LOG.warn("[vulkanfish] GPU-Scene voll -> Vanilla meshet Opaque wieder mit (Sichtweite reduzieren spart das)");
            restoreVanillaOpaque();
        }
    }

    /** Selbsttest-Ablauf je Frame (nativ aus renderOpaqueTerrain, Vanilla-Vergleich aus onFrameEnd). */
    private void selfTestFrame(long f) {
        if (EXIT_AFTER_FRAMES > 0) {
            // Selbsttest laeuft ohne Fensterfokus: Minecraft pausiert dann den integrierten Server
            // (keine Blockupdates, Zeit steht). Pause-Screen schliessen, Optionen bleiben unveraendert.
            Minecraft mc = Minecraft.getInstance();
            var screen = mc.gui.screen();
            if (screen instanceof net.minecraft.client.gui.screens.PauseScreen) mc.gui.setScreen(null);
            mc.options.pauseOnLostFocus = false; // sonst liegt das Pausemenue in GUI-/FG-Aufnahmen (nicht gespeichert)
            // Maus nicht fangen: echte Mausbewegung wuerde sonst die Testkamera drehen
            if (mc.mouseHandler.isMouseGrabbed()) mc.mouseHandler.releaseMouse();
            // Ohne Eingaben drosselt Minecraft nach einer Weile auf 30 FPS (AFK) – lange Tests aktiv halten
            mc.getFramerateLimitTracker().onInputReceived();
        }
        if (EXIT_AFTER_FRAMES > 0 && f == 300 && !FG_TEST && Boolean.getBoolean("vulkanfish.fgNoVsync")) {
            Minecraft.getInstance().options.enableVsync().set(false); // Frame Generation ohne Anzeige-Takt pruefen
        }
        if (EXIT_AFTER_FRAMES > 0 && Boolean.getBoolean("vulkanfish.dlssModeCycle") && f > 400 && f % 150 == 0) {
            // DLSS-Modi im laufenden Spiel durchschalten (Zielgroessen, NGX-Neuanlage)
            int m = (int) ((f / 150) % 5);
            System.setProperty("vulkanfish.dlssMode", Integer.toString(m));
            pendingScreenshot = f + 60;
            LOG.info("[vulkanfish] DLSS-Modus-Test: Modus {}", m);
        }
        if (EXIT_AFTER_FRAMES > 0 && Boolean.getBoolean("vulkanfish.uiTest")) {
            // Menues mit Vulkanfish-Zusaetzen: Server bearbeiten (Seed-Feld), Vulkanfish-Einstellungen
            Minecraft mc = Minecraft.getInstance();
            if (f == 400) mc.gui.setScreen(new net.minecraft.client.gui.screens.ManageServerScreen(null,
                    net.minecraft.network.chat.Component.literal("Server bearbeiten"), ok -> {},
                    new net.minecraft.client.multiplayer.ServerData("Test", "example.org",
                            net.minecraft.client.multiplayer.ServerData.Type.OTHER)));
            if (f == 460) pendingGuiScreenshot = f;
            if (f == 480) mc.gui.setScreen(new simon.vulkanfish.client.gui.VulkanfishSettingsScreen(null, mc.options));
            if (f == 540) pendingGuiScreenshot = f;
            if (f == 560) mc.gui.setScreen(null);
        }
        if (EXIT_AFTER_FRAMES > 0 && Boolean.getBoolean("vulkanfish.hizTest")) {
            // A/B: gleiches Standbild mit/ohne Hi-Z -> Differenz = faelschlich weggecullte Geometrie
            if (f == 560) pendingScreenshot = f;
            if (f == 580) NativePassRunner.hizForceOff = true;
            if (f == 620) pendingScreenshot = f;
            if (f == 640) NativePassRunner.hizForceOff = false;
        }
        if (EXIT_AFTER_FRAMES > 0 && CAVE_TEST && TEST_WORLD_EDITS) {
            caveTest(f);
        } else if (EXIT_AFTER_FRAMES > 0 && ICE_TEST && TEST_WORLD_EDITS) {
            iceTest(f);
        } else if (EXIT_AFTER_FRAMES > 0 && BIOME_TEST != null) {
            biomeTest(f);
        } else if (EXIT_AFTER_FRAMES > 0 && SHADOW_TEST) {
            shadowTest(f);
        } else if (EXIT_AFTER_FRAMES > 0 && LIGHT_TEST) {
            lightTest(f);
        } else if (EXIT_AFTER_FRAMES > 0 && ENTITY_TEST && TEST_WORLD_EDITS) {
            entityTest(f);
        } else if (EXIT_AFTER_FRAMES > 0 && BOBBY_TEST) {
            bobbyTest(f);
        } else if (EXIT_AFTER_FRAMES > 0 && RR_TEST && TEST_WORLD_EDITS) {
            rrTest(f);
        } else if (EXIT_AFTER_FRAMES > 0 && FG_TEST) {
            fgTest(f);
        } else if (EXIT_AFTER_FRAMES > 0 && LOD_TEST && NEAR_TEST) {
            nearFieldTest(f);
        } else if (EXIT_AFTER_FRAMES > 0 && LOD_TEST && LOD_RETURN) {
            lodReturnTest(f);
        } else if (EXIT_AFTER_FRAMES > 0 && LOD_TEST) {
            lodTest(f);
        } else if (EXIT_AFTER_FRAMES > 0 && SCENE_TEST && TEST_WORLD_EDITS) {
            sceneTest(f);
        } else if (EXIT_AFTER_FRAMES > 0 && SNAPSHOTS) {
            // Selbsttest-Tageszeiten fuer Look-Vergleiche: Mittag, Sonnenuntergang, Nacht
            // Wasser-Szene nur in der Test-Welt (Kopie), nie in einer echten Welt
            if (f == 300 && TEST_WORLD_EDITS) {
                LOG.info("[vulkanfish] Selbsttest: Spieler bei {}", Minecraft.getInstance().player.blockPosition());
                selfTestCommand("gamerule randomTickSpeed 0");          // kein Zufrieren im Schneebiom
                selfTestCommand("gamerule minecraft:random_tick_speed 0");
                selfTestCommand("execute at @p run fill ~-10 ~0 ~2 ~10 ~3 ~22 minecraft:air"); // Schneedecke weg
                selfTestCommand("execute at @p run fill ~-10 ~-4 ~2 ~10 ~-1 ~22 minecraft:water");
                selfTestCommand("execute at @p run fill ~-3 ~-4 ~8 ~3 ~-3 ~12 minecraft:stone");
                selfTestCommand("execute at @p run fill ~-10 ~-1 ~15 ~0 ~-1 ~22 minecraft:ice");   // Eis auf dem Teich
                selfTestCommand("execute at @p run fill ~2 ~0 ~5 ~4 ~1 ~5 minecraft:glass");
                selfTestCommand("execute at @p run fill ~5 ~0 ~5 ~7 ~1 ~5 minecraft:light_blue_stained_glass");
            }
            if (f == 400) FrameDataCapture.testSunAngle = 20.0f;   // Vormittag/Mittag
            if (f == 1050) FrameDataCapture.testSunAngle = 80.0f;  // tiefe Abendsonne
            if (f == 1300) FrameDataCapture.testSunAngle = 180.0f; // Mitternacht
            // Schatten-Debug-Ansicht kurz vor den Mittag-/Abend-Screenshots
            if (f == 930 || f == 1180) NativePassRunner.debugView = 1;
            if (f == 960 || f == 1210) NativePassRunner.debugView = 0;
            if (f == 1400) NativePassRunner.debugView = 5; // Wasser magenta
            if (f == 1450 && TEST_WORLD_EDITS) selfTestCommand("execute at @p run tp @p ~ ~-3 ~8"); // in den Teich
            if (f == 1440) NativePassRunner.debugView = 0;
            if (f == 950 || f == 1200 || f == 1000 || f == 1250 || f == 1420 || f == 1500 || f == 1560) {
                pendingScreenshot = f;
            }
        }
        boolean ownCamera = FG_TEST || RR_TEST || BOBBY_TEST || ENTITY_TEST || LIGHT_TEST || Boolean.getBoolean("vulkanfish.uiTest");
        if (EXIT_AFTER_FRAMES > 0 && !ownCamera && !SCENE_TEST && !LOD_TEST && !ICE_TEST && !CAVE_TEST && !SHADOW_TEST && BIOME_TEST == null && f > 700 && Minecraft.getInstance().player != null) {
            // Selbsttest: Kamera drehen/neigen -> Hi-Z-Reprojektion + Frustum unter Bewegung
            var player = Minecraft.getInstance().player;
            if (f >= 1450 && TEST_WORLD_EDITS) {
                lockView(player, 0.0f, -35.0f); // unter Wasser nach oben: Snell-Fenster
            } else if (((f >= 1380 && f <= 1440) || (f >= 960 && f <= 1010) || (f >= 1220 && f <= 1260)) && TEST_WORLD_EDITS) {
                lockView(player, 0.0f, 40.0f); // Blick nach Sueden (+Z) auf den Test-Teich
            } else {
                lockView(player, player.getYRot() + 4.0f, (float) Math.sin(f * 0.02) * 35.0f);
            }
        }
        if (EXIT_AFTER_FRAMES > 0 && f == EXIT_AFTER_FRAMES) {
            // Selbsttest: sauber beenden (prueft den Shutdown-Pfad)
            LOG.info("[vulkanfish] Selbsttest: {} Frames, beende Client", f);
            Minecraft.getInstance().stop();
        }
    }

    private long vanillaTestFrame;

    /** Einstellungen aus dem Spiel (VulkanfishSettings) jeden Frame uebernehmen – Aenderungen gelten sofort. */
    private boolean taaWasOn = true;

    private void applyLiveSettings() {
        NativePassRunner.rtForceOff = !simon.vulkanfish.client.VulkanfishSettings.raytracing() || Boolean.getBoolean("vulkanfish.rtOff");
        NativePassRunner.dlaaWanted = simon.vulkanfish.client.VulkanfishSettings.dlss() && !"false".equals(System.getProperty("vulkanfish.dlss"))
                && !"false".equals(System.getProperty("vulkanfish.dlaa")); // nur DLAA aus (Selbsttest TAA + FG)
        NativePassRunner.rrWanted = simon.vulkanfish.client.VulkanfishSettings.rayReconstruction()
                || "true".equals(System.getProperty("vulkanfish.rr"));
        if (lod != null) {
            lod.setDistanceChunks(simon.vulkanfish.client.VulkanfishSettings.lodChunks());
            lod.setPixelError(simon.vulkanfish.client.VulkanfishSettings.lodPixelError());
            FrameDataCapture.lodFogDistance = lod.farBlocks();
            nativeRunner.setLodGpuBudget(simon.vulkanfish.client.VulkanfishSettings.lodGpuMs());
        }
    }

    // Render-Position je Entity im Vorframe (fuer die Bewegungsvektoren)
    private final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<double[]> prevEntityPos = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
    private float[] entityMotion = new float[12 * 64];
    private long entityFrame;

    /**
     * Bewegte Entities (Render-Position seit dem Vorframe verschoben) als Boxen + Bewegung an den
     * Bewegungsvektor-Pass: DLAA und Frame Generation behandeln sie sonst wie stehende Kulisse
     * (Nachziehen, falsch eingefuegte Zwischenbilder).
     */
    private void gatherMovingEntities() {
        Minecraft mc = Minecraft.getInstance();
        var level = mc.level;
        var d = FrameDataCapture.last;
        if (level == null || d == null) return;
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        long frame = ++entityFrame;
        int n = 0;
        var cam = mc.gameRenderer.mainCamera().position();
        it.unimi.dsi.fastutil.ints.IntOpenHashSet seen = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
        for (var e : level.entitiesForRendering()) {
            if (e == null) continue;
            var pos = e.getPosition(pt);
            if (pos.distanceToSqr(cam) > 160.0 * 160.0) continue;
            int id = e.getId();
            seen.add(id);
            double[] prev = prevEntityPos.get(id);
            double[] now = {pos.x, pos.y, pos.z, frame};
            prevEntityPos.put(id, now);
            if (prev == null || prev[3] != frame - 1) continue; // neu oder im Vorframe nicht erfasst
            double dx = pos.x - prev[0], dy = pos.y - prev[1], dz = pos.z - prev[2];
            double m2 = dx * dx + dy * dy + dz * dz;
            if (m2 < 1e-8 || m2 > 64.0) continue; // steht bzw. teleportiert
            if (n >= 256) continue;
            if (entityMotion.length < (n + 1) * 12) entityMotion = java.util.Arrays.copyOf(entityMotion, entityMotion.length * 2);
            var box = e.getBoundingBox().move(pos.subtract(e.position())).inflate(0.3);
            int o = n * 12;
            entityMotion[o] = (float) (box.minX - d.originX());
            entityMotion[o + 1] = (float) (box.minY - d.originY());
            entityMotion[o + 2] = (float) (box.minZ - d.originZ());
            entityMotion[o + 4] = (float) (box.maxX - d.originX());
            entityMotion[o + 5] = (float) (box.maxY - d.originY());
            entityMotion[o + 6] = (float) (box.maxZ - d.originZ());
            entityMotion[o + 8] = (float) dx;
            entityMotion[o + 9] = (float) dy;
            entityMotion[o + 10] = (float) dz;
            n++;
        }
        if (prevEntityPos.size() > seen.size() + 64) prevEntityPos.keySet().retainAll(seen);
        nativeRunner.setEntityMotion(entityMotion, n);
    }

    /** Notweg aus GameRendererScaleMixin: niemand hat hochskaliert -> linear aufziehen. */
    public static void upscaleFallback(com.mojang.blaze3d.pipeline.RenderTarget low, com.mojang.blaze3d.pipeline.RenderTarget full) {
        var r = simon.vulkanfish.client.VulkanfishClient.RENDERER;
        if (r != null && r.nativeRunner != null) r.nativeRunner.upscaleFallback(low, full);
    }

    /** Aus LevelRendererMixin am Ende von render(): Frame-Graph komplett (inkl. Entities, Wasser). */
    public void onFrameEnd() {
        if (!initialized && EXIT_AFTER_FRAMES > 0 && Minecraft.getInstance().level != null) {
            selfTestFrame(++vanillaTestFrame); // Vergleichslauf ohne unseren Renderer (Vanilla-Bild)
        }
        boolean taaOn = simon.vulkanfish.client.VulkanfishSettings.taa();
        if (taaOn && !taaWasOn && nativeRunner != null) nativeRunner.resetTaaHistory();
        taaWasOn = taaOn;
        if (initialized && nativeRunner != null && nativeRunner.isReady() && taaOn) {
            long t0 = System.nanoTime();
            if (!"false".equals(System.getProperty("vulkanfish.entityMV"))) gatherMovingEntities();
            // DLSS Super Resolution: aus dem kleinen Ziel ins echte (danach gilt wieder das grosse)
            nativeRunner.renderTaa(Minecraft.getInstance().gameRenderer.mainRenderTarget(), RenderScale.active() ? RenderScale.full() : null);
            RenderScale.endLevel();
            cpuNanosTaa += System.nanoTime() - t0;
        }
        // naechster Frame: kleiner rendern nur, wenn DLSS das Ergebnis hochrechnen kann
        int mode = simon.vulkanfish.client.VulkanfishSettings.dlssMode();
        String forced = System.getProperty("vulkanfish.dlssMode");
        if (forced != null) mode = Integer.parseInt(forced.trim());
        boolean upscale = initialized && nativeRunner != null && taaOn && nativeRunner.canUpscale()
                && mode > 0 && mode < RenderScale.MODE_SCALE.length;
        RenderScale.setWanted(upscale ? RenderScale.MODE_SCALE[mode] : 1.0f);
        if (initialized) logCpu();
        FrameDataCapture.taaJitter = initialized && nativeRunner != null && nativeRunner.isReady() && simon.vulkanfish.client.VulkanfishSettings.taa();
        if (pendingScreenshot < 0) return;
        // Screenshot.grab kopiert SOFORT im Command-Stream -> erst hier ist das Level-Bild fertig
        long tag = pendingScreenshot;
        pendingScreenshot = -1;
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.Screenshot.grab(mc.gameDirectory, mc.gameRenderer.mainRenderTarget(),
                msg -> LOG.info("[vulkanfish] Selbsttest-Screenshot {}: {}", tag, msg.getString()));
    }

    /**
     * Aus ChunkSectionsToRenderMixin unmittelbar VOR Vanillas OPAQUE-Gruppe
     * (die danach nur noch nicht abgedeckte Sections zeichnet).
     * @return true, wenn unser Frame aufgenommen wurde
     */
    public boolean renderOpaqueTerrain(GpuTextureView atlas) {
        NativePassRunner.FrameUniformsData data = pendingFrame;
        pendingFrame = null;
        if (data == null || !initialized || !nativeRunner.isReady()
                || !(atlas instanceof VulkanGpuTextureView vkAtlas)) {
            return false;
        }
        long t0 = System.nanoTime();
        int slot = frameGraph.beginFrame();
        boolean ok = nativeRunner.renderFrame(slot, data, streamer,
                Minecraft.getInstance().gameRenderer.mainRenderTarget(), vkAtlas.vkImageView());
        frameGraph.endFrame(slot);
        cpuNanosTerrain += System.nanoTime() - t0;
        if (!ok && !nativeRunner.isReady() && vanillaOpaqueDisabled) {
            restoreVanillaOpaque(); // nativer Pfad endgueltig aus -> Vanilla komplett zurueck
        }
        long f = frameGraph.currentFrame();
        if (ok && SNAPSHOTS) {
            // Beweis-Snapshots (run/*.png): G-Buffer-Normalen, dann Composite
            if (f == 600) {
                nativeRunner.requestSnapshot(2); // Normale/Licht
            } else if (f == 630) {
                nativeRunner.requestSnapshot(1);
            } else if (f == 660) {
                nativeRunner.requestSnapshot(0);
            } else if (f == 700) {
                pendingScreenshot = f;
            }
        }
        selfTestFrame(f);
        return ok;
    }

    /**
     * Hybrid-Filter fuer Vanillas Opaque-Draws: true = unsere GPU-Scene hat die
     * Section, Vanilla laesst sie aus. Nur solange der native Pfad laeuft.
     */
    public boolean coversSection(long sectionNode) {
        return initialized && nativeRunner.isReady() && pendingFrame != null && streamer.covers(sectionNode);
    }

    /** Aus ChunkSectionsToRenderMixin vor Vanillas TRANSLUCENT-Gruppe. */
    public void renderWater() {
        if (!initialized || nativeRunner == null || !nativeRunner.isReady()) return;
        long t0 = System.nanoTime();
        nativeRunner.renderWater(Minecraft.getInstance().gameRenderer.mainRenderTarget());
        cpuNanosWater += System.nanoTime() - t0;
        if (!nativeRunner.isReady() && vanillaOpaqueDisabled) restoreVanillaOpaque();
    }

    private void logCpu() {
        cpuFrames++;
        long now = System.currentTimeMillis();
        if (now - cpuLogMs < 10_000) return;
        cpuLogMs = now;
        double n = Math.max(cpuFrames, 1) * 1e6;
        LOG.info("[vulkanfish] CPU/Frame (Render-Thread): streamer+capture {} ms, terrain-aufnahme {} ms, wasser {} ms, taa {} ms",
                String.format(java.util.Locale.ROOT, "%.3f", cpuNanosStart / n), String.format(java.util.Locale.ROOT, "%.3f", cpuNanosTerrain / n),
                String.format(java.util.Locale.ROOT, "%.3f", cpuNanosWater / n), String.format(java.util.Locale.ROOT, "%.3f", cpuNanosTaa / n));
        cpuNanosStart = cpuNanosTerrain = cpuNanosWater = cpuNanosTaa = 0;
        cpuFrames = 0;
    }

    /**
     * Szenen-Selbsttest (-Dvulkanfish.sceneTest, nur Test-Welt): feste Kulisse an absoluten
     * Koordinaten – Glas auf Eis, verschiedenfarbiges Glas aneinander, Glas unter/ueber
     * Wasser, Lichtquellen mit Schattenwerfern – feste Kamera, Screenshots mittags und
     * nachts. Aufeinanderfolgende Frames (x00/x01/x02) machen Flackern per Differenz sichtbar.
     */
    private void sceneTest(long f) {
        if (f == 300) {
            selfTestCommand("gamerule minecraft:random_tick_speed 0");
            selfTestCommand("gamerule minecraft:advance_time false");
            selfTestCommand("gamerule doDaylightCycle false");
            selfTestCommand("gamerule minecraft:advance_weather false");
            selfTestCommand("weather clear");
            selfTestCommand("fill 18 63 -78 50 80 -44 minecraft:air");
            selfTestCommand("fill 18 58 -78 50 62 -44 minecraft:stone");
            // Wasserbecken mit Glas darin und darueber
            selfTestCommand("fill 26 59 -57 42 61 -49 minecraft:stone");
            selfTestCommand("fill 27 60 -56 41 61 -50 minecraft:water");
            selfTestCommand("fill 27 62 -56 41 62 -50 minecraft:water");
            selfTestCommand("setblock 33 61 -53 minecraft:glass");
            selfTestCommand("setblock 36 63 -53 minecraft:glass");
            // Eis mit Glas/Buntglas darauf
            selfTestCommand("fill 27 62 -63 33 62 -58 minecraft:ice");
            selfTestCommand("fill 28 63 -62 29 64 -61 minecraft:glass");
            selfTestCommand("fill 31 63 -62 31 64 -59 minecraft:red_stained_glass");
            // Verschiedenfarbiges Glas direkt aneinander
            selfTestCommand("fill 35 63 -62 35 65 -59 minecraft:glass");
            selfTestCommand("fill 36 63 -62 36 65 -59 minecraft:light_blue_stained_glass");
            selfTestCommand("fill 37 63 -62 37 65 -59 minecraft:lime_stained_glass");
            // Lichtquellen + Schattenwerfer
            selfTestCommand("setblock 40 63 -60 minecraft:torch");
            selfTestCommand("fill 40 63 -58 40 65 -58 minecraft:stone_bricks");
            selfTestCommand("setblock 26 63 -54 minecraft:lantern");
            selfTestCommand("setblock 26 63 -52 minecraft:oak_fence");
            selfTestCommand("setblock 43 63 -50 minecraft:soul_torch");
            selfTestCommand("setblock 42 63 -50 minecraft:oak_log");
            selfTestCommand("setblock 25 63 -60 minecraft:redstone_torch");
            selfTestCommand("setblock 34 63 -48 minecraft:glowstone");
            selfTestCommand("setblock 34 64 -49 minecraft:stone_slab");
        }
        var player = Minecraft.getInstance().player;
        if (f >= 320 && player != null) {
            if (f == 320) selfTestCommand("tp @p 34 69 -69 0 38");
            // Nahaufnahme von Sueden: Steinsaeule vor der Fackel wirft ihren Schatten zur Kamera
            if (f == 1150) selfTestCommand("tp @p 40.5 67 -52.5 180 45");
            boolean close = f >= 1150;
            lockView(player, close ? 180.0f : 0.0f, close ? 45.0f : 38.0f);
            if (f == 1450) selfTestCommand("tp @p 34 72 -40 0 5"); // ueber die Kante ins Gelaende
            if (f >= 1450) {
                // Bewegung fuer den Hi-Z-A/B: gleichmaessig drehen (Disocclusion an Silhouetten)
                lockView(player, 180.0f + (Math.min(f, 1540) - 1450) * 2.5f, 8.0f); // ab 1540 still (A/B im selben Bild)
            }
        }
        // GPU-Zeiten nur ueber die Nacht-Nahaufnahme mitteln (viele Fackel-Pixel = RT-Last)
        if (f == 1250 && nativeRunner != null) nativeRunner.passTimings();
        if (f == 1349 && nativeRunner != null) LOG.info("[vulkanfish] Selbsttest GPU-Zeit Nacht-Nahaufnahme: {}", nativeRunner.passTimings());
        // Fullbright (nur im Test, ohne Speichern) + Videoeinstellungen einmal oeffnen
        if (f == 1200) simon.vulkanfish.client.VulkanfishSettings.setFullbrightTransient(60);
        if (f == 1230) pendingScreenshot = f;
        if (f == 1235) simon.vulkanfish.client.VulkanfishSettings.setFullbrightTransient(0);
        if (f == 1260) {
            Minecraft mc = Minecraft.getInstance();
            mc.gui.setScreen(new net.minecraft.client.gui.screens.options.VideoSettingsScreen(null, mc, mc.options));
        }
        if (f == 1290 && Minecraft.getInstance().gui.screen() instanceof net.minecraft.client.gui.screens.options.OptionsSubScreen s) {
            // Abschnitt Vulkanfish vorhanden? (GUI landet nicht im Level-Screenshot)
            StringBuilder sb = new StringBuilder();
            collectWidgetText(s, sb);
            LOG.info("[vulkanfish] Selbsttest Videoeinstellungen: {}", sb);
        }
        if (f == 1300) {
            Minecraft mc = Minecraft.getInstance();
            mc.gui.setScreen(new simon.vulkanfish.client.gui.VulkanfishSettingsScreen(null, mc.options));
        }
        if (f == 1310 && Minecraft.getInstance().gui.screen() instanceof simon.vulkanfish.client.gui.VulkanfishSettingsScreen vs) {
            StringBuilder sb = new StringBuilder();
            collectWidgetText(vs, sb);
            LOG.info("[vulkanfish] Selbsttest Vulkanfish-Einstellungen: {}", sb);
        }
        if (f == 1315) Minecraft.getInstance().gui.setScreen(null);
        if (f == 1350) NativePassRunner.debugView = 9;
        if (f == 1410) NativePassRunner.debugView = 0;
        if (f == 330) FrameDataCapture.testSunAngle = 20.0f;   // Vormittag
        if (f == 900) FrameDataCapture.testSunAngle = 180.0f;  // Mitternacht
        if (f == 800 || f == 801 || f == 802 || f == 1100 || f == 1101 || f == 1400 || f == 1440
                || f == 1500 || f == 1520 || f == 1540 || f == 1560 || f == 1600 || f == 1640) pendingScreenshot = f;
        if (f == 1580) NativePassRunner.hizForceOff = true;
        if (f == 1620) NativePassRunner.hizForceOff = false;
    }

    /**
     * LOD-Selbsttest (-Dvulkanfish.lodTest): aus 190 Bloecken Hoehe ueber das Fernfeld schauen,
     * nach dem Laden Screenshots in zwei Richtungen, GPU-Zeiten ueber eine ruhige Phase.
     * Keine Weltaenderung (nur Teleport + Spectator, Zeit fest auf Mittag).
     */
    /**
     * Eis-/Entity-Selbsttest (-Dvulkanfish.iceTest, nur Test-Welt): zugefrorener Teich, Eis
     * wird angeschlagen (Risse) und gebrochen (wird zu Wasser); Tiere in der Sonne fuer Schatten.
     */
    /**
     * Hoehlen-Selbsttest (-Dvulkanfish.caveTest, nur Test-Welt): geschlossener Raum tief im Fels,
     * weit weg vom Spawn (die Oberflaeche darueber war nie sichtbar), dazu ein Schacht zum Himmel.
     * Mittagssonne: der Raum muss dunkel bleiben, nur unter dem Schacht darf Sonne fallen.
     */
    private void caveTest(long f) {
        var player = Minecraft.getInstance().player;
        if (f == 300) {
            selfTestCommand("gamerule minecraft:random_tick_speed 0");
            selfTestCommand("gamerule minecraft:advance_time false");
            selfTestCommand("gamerule minecraft:advance_weather false");
            selfTestCommand("weather clear");
            selfTestCommand("gamemode spectator @p");
            selfTestCommand("tp @p 3000.5 34 3000.5 -45 30");
            FrameDataCapture.testSunAngle = 5.0f;
        }
        if (f == 500) {
            selfTestCommand("fill 2990 30 2990 3010 38 3010 minecraft:air");
            // Schraeger Schlitz zum Himmel (Sonne um 30 Grad nach Sueden geneigt): hier muss sie auf den Boden fallen
            selfTestCommand("fill 3004 39 3004 3012 90 3040 minecraft:air");
            selfTestCommand("fill 3004 91 3004 3012 140 3070 minecraft:air");
            // Erze im dunklen Boden (neben dem Lichtschlitz): muessen leicht gluehen
            selfTestCommand("fill 3001 29 3007 3002 29 3008 minecraft:diamond_ore");
            selfTestCommand("fill 3007 29 3001 3008 29 3002 minecraft:gold_ore");
            selfTestCommand("fill 3000 29 3004 3001 29 3005 minecraft:redstone_ore");
            selfTestCommand("fill 3004 29 3000 3005 29 3001 minecraft:deepslate_lapis_ore");
            selfTestCommand("fill 2998 29 3007 2999 29 3008 minecraft:iron_ore");
            selfTestCommand("fill 3007 29 2998 3008 29 2999 minecraft:emerald_ore");
        }
        if (f >= 300 && player != null) {
            // ab 950 unter den Schacht blicken: dort muss die Sonne auf den Boden fallen
            boolean shaft = f >= 950;
            if (f > 320) player.setPos(shaft ? 3001.5 : 3000.5, 34, shaft ? 3001.5 : 3000.5);
            lockView(player, -45.0f, 30.0f);
        }
        if (f == 880) NativePassRunner.debugView = 1; // Schattenfaktor
        if (f == 910) NativePassRunner.debugView = 0;
        if (f == 850 || f == 900 || f == 1000) pendingScreenshot = f;
        if (f == 1000 && nativeRunner != null) LOG.info("[vulkanfish] Hoehlentest GPU-Zeit: {}", nativeRunner.passTimings());
    }

    /**
     * LOD-Rueckkehr-Selbsttest (-Dvulkanfish.lodTest -Dvulkanfish.lodReturn): Fernfeld am Start
     * aufbauen, weit wegspringen (-Dvulkanfish.lodReturnDist, Standard 1500 Bloecke), dort warten,
     * bis die alten Knoten verdraengt sind, zurueck. Screenshots und Rueckstand im selben Abstand
     * nach der Ankunft wie beim ersten Besuch -> Nachbau-Tempo im Vergleich.
     */
    private void lodReturnTest(long f) {
        var player = Minecraft.getInstance().player;
        Integer rd = Integer.getInteger("vulkanfish.testRenderDistance");
        if (f == 200 && rd != null) Minecraft.getInstance().options.renderDistance().set(rd);
        int away = Integer.getInteger("vulkanfish.lodReturnDist", 1500);
        if (f == 300) {
            selfTestCommand("gamemode spectator @p");
            selfTestCommand("tp @p 34 120 -69 -90 3");
            FrameDataCapture.testSunAngle = 25.0f;
        }
        int hops = Integer.getInteger("vulkanfish.lodHops", 0);
        if (hops > 0) {
            // Dimensionswechsel hin und zurueck (neue Welt fuers LOD, GPU-Generator wird neu eingerichtet)
            for (int i = 0; i < hops; i++) {
                if (f == 1400 + i * 1200L) selfTestCommand("execute in minecraft:the_nether run tp @p 0 90 0");
                if (f == 2000 + i * 1200L) selfTestCommand("execute in minecraft:overworld run tp @p 34 120 -69 -90 3");
            }
            if (f == 1400 + hops * 1200L + 600 && player != null) {
                LOG.info("[vulkanfish] Rueckkehrtest: {} Dimensionswechsel fertig", hops);
                pendingScreenshot = f;
            }
            if (f >= 300 && player != null && player.level().dimension() == net.minecraft.world.level.Level.OVERWORLD) lockView(player, -90.0f, 3.0f);
            return;
        }
        if (f == 1400) selfTestCommand("tp @p " + (34 + away) + " 120 -69 -90 3");
        if (f == 2600) selfTestCommand("tp @p 34 120 -69 -90 3");
        if (f >= 300 && player != null) lockView(player, -90.0f, 3.0f);
        if (f == 420 || f == 600 || f == 900 || f == 1390 || f == 2720 || f == 2900 || f == 3200 || f == 3690) {
            pendingScreenshot = f;
            if (player != null) LOG.info("[vulkanfish] Rueckkehrtest f{} bei x={}", f, (int) player.getX());
        }
    }

    /**
     * Nahfeld-Selbsttest (-Dvulkanfish.lodTest -Dvulkanfish.nearTest): nach dem Laden im Kreis umsehen,
     * je Richtung ein Bild normal und eins mit Debug-Ansicht 10 (Fernfeld magenta). Im Sichtradius
     * darf nach dem Laden nichts mehr vom Fernfeld kommen. Zweiter Ort nach einem Sprung.
     */
    private void nearFieldTest(long f) {
        var player = Minecraft.getInstance().player;
        Integer rd = Integer.getInteger("vulkanfish.testRenderDistance");
        if (f == 200 && rd != null) Minecraft.getInstance().options.renderDistance().set(rd);
        if (f == 300) {
            selfTestCommand("gamemode spectator @p");
            selfTestCommand("tp @p " + NEAR_X + " 100 " + NEAR_Z + " 0 10");
            FrameDataCapture.testSunAngle = 25.0f;
        }
        // Einschwingzeit vor dem Umsehen (-Dvulkanfish.nearSettle, Standard 1100 Frames; hohe Sichtweiten laden lange)
        int settle = Integer.getInteger("vulkanfish.nearSettle", 1100);
        long jump = settle + 800L;
        if (f == jump) selfTestCommand("tp @p " + (NEAR_X + 600) + " 100 " + (NEAR_Z + 400) + " 0 10");
        long phase = f >= jump ? f - jump : f;
        // ferne Startorte: nach dem Generieren knapp ueber die Oberflaeche setzen
        if (f == settle - 600 && (NEAR_X != 34 || NEAR_Z != -69))
            selfTestCommand("execute positioned " + NEAR_X + " 0 " + NEAR_Z + " positioned over motion_blocking run tp @p ~ ~12 ~ 0 10");
        // GPU-Zeiten im Stand (vor dem Umsehen), Nahfeld und Fernfeld eingeschwungen
        if (phase == settle - 300 && nativeRunner != null) nativeRunner.passTimings();
        if (phase == settle - 1 && nativeRunner != null) LOG.info("[vulkanfish] Nahfeldtest GPU-Zeit im Stand: {}", nativeRunner.passTimings());
        if (f >= 300 && player != null) {
            int dir = phase >= settle ? (int) Math.min(7, (phase - settle) / 80) : 0;
            lockView(player, dir * 45.0f, 10.0f);
            long in = (phase - settle) % 80;
            if (phase >= settle && phase < settle + 640) {
                if (in == 60) pendingScreenshot = f;
                if (in == 66) NativePassRunner.debugView = 10;
                if (in == 70) pendingScreenshot = f;
                if (in == 74) NativePassRunner.debugView = 0;
            }
        }
    }

    /**
     * Schatten-Flacker-Selbsttest (-Dvulkanfish.shadowTest): Kamera steht ueber Baeumen, je 8 Bilder
     * in Folge mit laufender Sonne (Vanilla-Zeit) und mit festem Sonnenwinkel; -Dvulkanfish.shadowMove
     * laesst die Kamera dabei langsam seitlich gleiten. Keine Weltaenderung.
     */
    private void shadowTest(long f) {
        var player = Minecraft.getInstance().player;
        Integer rd = Integer.getInteger("vulkanfish.testRenderDistance");
        if (f == 200 && rd != null) Minecraft.getInstance().options.renderDistance().set(rd);
        if (f == 300) {
            selfTestCommand("gamemode spectator @p");
            selfTestCommand("time set 2500");
            selfTestCommand("gamerule minecraft:advance_time true");
            selfTestCommand("tp @p " + NEAR_X + " 100 " + NEAR_Z + " 0 35");
        }
        if (f == 900 && (NEAR_X != 34 || NEAR_Z != -69))
            selfTestCommand("execute positioned " + NEAR_X + " 0 " + NEAR_Z + " positioned over motion_blocking run tp @p ~ ~10 ~ 0 35");
        if (f >= 300 && player != null) {
            lockView(player, 30.0f, 35.0f);
            if (Boolean.getBoolean("vulkanfish.shadowMove") && f > 1100) player.setPos(player.getX() + 0.02, player.getY(), player.getZ());
        }
        if (f == 1150 && Boolean.getBoolean("vulkanfish.shadowDebug")) NativePassRunner.debugView = 1; // nur Schattenfaktor
        if (f == 1300) FrameDataCapture.testSunAngle = FrameDataCapture.lastSunAngle; // Sonne einfrieren
        if ((f >= 1200 && f < 1208) || (f >= 1400 && f < 1408)) pendingScreenshot = f;
        if (f == 1500 && nativeRunner != null) LOG.info("[vulkanfish] Schattentest Sonne {} Grad", FrameDataCapture.lastSunAngle);
    }

    /**
     * DLSS-FG-Selbsttest (-Dvulkanfish.fgTest, dazu -Dvulkanfish.frameGen=N): Kamera ueber Baeumen
     * dreht gleichmaessig; zwei Pakete (erzeugte + echte Bilder) landen als PNG in run/, dazu die
     * Takt-Statistik des Present-Threads. -Dvulkanfish.fgYawStep = Grad je Frame. Keine Weltaenderung.
     */
    /**
     * Ray-Reconstruction-Selbsttest (-Dvulkanfish.rrTest, nur Test-Welt): Hoehlenraum mit Fackeln und
     * Saeulen (RT-Halbschatten = verrauschte Schattenstrahlen), je 8 Bilder in Folge mit stehender und
     * mit langsam gleitender Kamera. Mit/ohne -Dvulkanfish.rr=true laufen lassen -> Vergleich.
     */
    private void rrTest(long f) {
        var player = Minecraft.getInstance().player;
        if (f == 300) {
            selfTestCommand("gamerule minecraft:random_tick_speed 0");
            selfTestCommand("gamerule minecraft:advance_time false");
            selfTestCommand("time set 18000");
            selfTestCommand("gamemode spectator @p");
            selfTestCommand("tp @p 5000.5 24 5000.5 -45 25");
        }
        if (f == 500) {
            selfTestCommand("fill 4990 20 4990 5012 30 5012 minecraft:stone");
            selfTestCommand("fill 4991 21 4991 5011 29 5011 minecraft:air");
            for (int[] t : new int[][]{{5004, 5004}, {5008, 4996}, {4996, 5008}, {5006, 5009}})
                selfTestCommand("setblock " + t[0] + " 21 " + t[1] + " minecraft:torch");
            for (int[] c : new int[][]{{5003, 5006}, {5006, 5002}, {5002, 5002}, {5008, 5007}})
                selfTestCommand("fill " + c[0] + " 21 " + c[1] + " " + c[0] + " 25 " + c[1] + " minecraft:cobblestone");
            selfTestCommand("fill 4999 21 5005 5000 22 5006 minecraft:oak_planks");
        }
        if (f >= 300 && player != null) {
            double slide = f > 1550 ? (f - 1550) * Double.parseDouble(System.getProperty("vulkanfish.rrSlide", "0.01")) : 0.0; // vorher: Belichtung eingeschwungen
            if (f > 320) player.setPos(4995.5 + slide, 26.5, 4995.5);
            lockView(player, -45.0f, 30.0f);
        }
        if ((f >= 1500 && f < 1508) || (f >= 1600 && f < 1608)) pendingScreenshot = f;
        if (f == 1520 && Boolean.getBoolean("vulkanfish.rrGuideDump") && nativeRunner != null) nativeRunner.requestSnapshot(3);
        if (f == 1510 && nativeRunner != null)
            LOG.info("[vulkanfish] RR-Test: Ray Reconstruction {}, GPU {}", nativeRunner.rayReconstructionActive(), nativeRunner.passTimings());
    }

    /**
     * Bobby-Selbsttest (-Dvulkanfish.bobbyTest, mit Bobby per -Dfabric.addMods und
     * viewDistanceOverwrite in config/bobby.conf): an einer frischen Stelle Chunks laden, dann
     * 3000 Bloecke weiter – Bobby speichert die verlassenen Chunks; das Fernfeld soll sie danach als
     * Quelle "bobby" nutzen (LOD-Statistik im Log). Keine Weltaenderung.
     */
    private void bobbyTest(long f) {
        var player = Minecraft.getInstance().player;
        int x0 = Integer.getInteger("vulkanfish.bobbyX", 24000);
        if (f == 300) {
            selfTestCommand("gamemode spectator @p");
            selfTestCommand("tp @p " + x0 + " 180 " + x0 + " 0 30");
        }
        if (f == 1300) selfTestCommand("tp @p " + (x0 + 3000) + " 180 " + x0 + " 90 30");
        if (f >= 300 && player != null) lockView(player, f < 1300 ? 0.0f : 90.0f, 30.0f);
        if (f == 2400 || f == 3000) {
            var lod = simon.vulkanfish.client.lod.LodManager.instance();
            if (lod != null) lod.logStats();
            pendingScreenshot = f;
        }
    }

    /**
     * Entity-Selbsttest (-Dvulkanfish.entityTest, nur Test-Welt): Truhen, Ruestungsstaender,
     * Dorfbewohner und Schwein (ohne KI) auf Steinboden, halb im Schatten einer Mauer, eine Fackel
     * daneben. Je 8 Bilder in Folge bei Tag (1500) und Nacht (1800). -Dvulkanfish.entityVanilla
     * vergleicht mit Vanillas Bild (Renderer aus).
     */
    private void entityTest(long f) {
        var player = Minecraft.getInstance().player;
        int x = 6000, y = 120, z = 6000;
        if (f == 300) {
            selfTestCommand("gamerule minecraft:random_tick_speed 0");
            selfTestCommand("gamerule minecraft:advance_time false");
            selfTestCommand("gamerule minecraft:advance_weather false");
            selfTestCommand("weather clear");
            selfTestCommand("time set 3000");
            selfTestCommand("gamemode spectator @p");
            selfTestCommand("tp @p " + x + " " + (y + 4) + " " + (z - 7) + " 0 25");
        }
        if (f == 500) {
            selfTestCommand("fill " + (x - 8) + " " + (y - 1) + " " + (z - 8) + " " + (x + 8) + " " + (y - 1) + " " + (z + 8) + " minecraft:stone");
            selfTestCommand("fill " + (x - 8) + " " + y + " " + (z - 8) + " " + (x + 8) + " " + (y + 12) + " " + (z + 8) + " minecraft:air");
            selfTestCommand("fill " + (x + 2) + " " + y + " " + (z - 3) + " " + (x + 2) + " " + (y + 4) + " " + (z + 4) + " minecraft:stone_bricks");
            selfTestCommand("setblock " + (x - 2) + " " + y + " " + z + " minecraft:chest[facing=north]");
            selfTestCommand("setblock " + (x + 1) + " " + y + " " + z + " minecraft:chest[facing=north]");
            selfTestCommand("setblock " + x + " " + y + " " + (z + 2) + " minecraft:ender_chest[facing=north]");
            selfTestCommand("setblock " + (x - 1) + " " + y + " " + (z - 2) + " minecraft:torch");
            selfTestCommand("kill @e[type=!minecraft:player]"); // Reste frueherer Laeufe (die Testwelt bleibt bestehen)
            selfTestCommand("summon minecraft:armor_stand " + (x - 3.5) + " " + y + " " + (z + 2.5) + " {NoGravity:1b,Rotation:[180f,0f]}");
            String ai = Boolean.getBoolean("vulkanfish.entityAI") ? "" : "NoAI:1b,"; // mit KI: bewegte Entities
            selfTestCommand("summon minecraft:villager " + (x - 0.5) + " " + y + " " + (z + 4.5) + " {" + ai + "Silent:1b,Rotation:[180f,0f]}");
            selfTestCommand("summon minecraft:pig " + (x + 1.5) + " " + y + " " + (z + 2.5) + " {" + ai + "Silent:1b,Rotation:[180f,0f]}");
            selfTestCommand("summon minecraft:zombie " + (x - 4.5) + " " + y + " " + (z + 5.5) + " {" + ai + "Silent:1b,PersistenceRequired:1b,Rotation:[180f,0f]}");
        }
        if (f >= 300 && player != null) {
            if (f > 320) player.setPos(x + 0.5, y + 3.5, z - 6.5);
            lockView(player, 0.0f, 28.0f);
        }
        // Schnell fahrende Lore mit Goldblock (Antriebsschienen) fuer Bewegungsvektor/Frame-Generation
        if (f == 520) {
            selfTestCommand("fill " + (x - 8) + " " + (y - 1) + " " + (z + 8) + " " + (x + 8) + " " + (y - 1) + " " + (z + 8) + " minecraft:redstone_block");
            selfTestCommand("fill " + (x - 8) + " " + y + " " + (z + 8) + " " + (x + 8) + " " + y + " " + (z + 8) + " minecraft:powered_rail[shape=east_west,powered=true]");
        }
        if (f == 1300) selfTestCommand("summon minecraft:block_display " + (x - 7) + " " + (y + 1) + " " + (z + 8)
                + " {Tags:[\"mv\"],teleport_duration:2,block_state:{Name:\"minecraft:gold_block\"}}");
        String step = System.getProperty("vulkanfish.mvStep", "0.2");
        if (f > 1300 && f < 1450 && f % 2 == 0) selfTestCommand("execute as @e[tag=mv] at @s run tp @s ~" + step + " ~ ~");
        if (f >= 1318 && f < 1322) pendingScreenshot = f;
        if (f == 1336 && FgPresenter.instance() != null) FgPresenter.dumpPackets = 2;
        if (f == 1450) selfTestCommand("kill @e[tag=mv]");
        if (f == 1600) selfTestCommand("time set 18000");
        if (f == 1500 && player != null) {
            var cam = Minecraft.getInstance().gameRenderer.mainCamera();
            LOG.info("[vulkanfish] Entitytest Kamera: Spieler {} xRot {} yRot {}, Kamera {} xRot {} yRot {}", player.position(),
                    player.getXRot(), player.getYRot(), cam.position(), cam.xRot(), cam.yRot());
        }
        if ((f >= 1500 && f < 1508) || (f >= 1900 && f < 1908)) pendingScreenshot = f;
    }

    /**
     * Licht-Selbsttest (-Dvulkanfish.lightTest): frische Gegend (Chunks laden und leuchten gerade
     * erst), von oben schraeg; Licht-Debugansicht (4) und normales Bild nach 10 und 25 s. Chunk-
     * weise falsches Licht zeigt sich als Kacheln im Raster von 16 Bloecken. Keine Weltaenderung.
     */
    private void lightTest(long f) {
        var player = Minecraft.getInstance().player;
        int x0 = Integer.getInteger("vulkanfish.lightX", 52000), z0 = Integer.getInteger("vulkanfish.lightZ", 52000);
        if (f == 300) {
            selfTestCommand("gamemode spectator @p");
            selfTestCommand("time set " + Integer.getInteger("vulkanfish.lightTime", 6000));
            selfTestCommand("gamerule minecraft:advance_time false");
            selfTestCommand("tp @p " + x0 + " 150 " + z0 + " 0 55");
        }
        if (f >= 300 && player != null) lockView(player, 0.0f, 55.0f);
        if (Boolean.getBoolean("vulkanfish.torchField") && TEST_WORLD_EDITS && f == 500) {
            // viele Fackeln (dichter als ein Lichtgitter-Platz je Zelle fasst) auf einer Steinflaeche
            int x = x0, y = 100, z = z0 + 40;
            selfTestCommand("fill " + (x - 32) + " " + (y - 1) + " " + (z - 32) + " " + (x + 32) + " " + (y - 1) + " " + (z + 32) + " minecraft:stone");
            selfTestCommand("fill " + (x - 32) + " " + y + " " + (z - 32) + " " + (x + 32) + " " + (y + 20) + " " + (z + 32) + " minecraft:air");
            for (int tx = -30; tx <= 30; tx += 4)
                for (int tz = -30; tz <= 30; tz += 4)
                    selfTestCommand("setblock " + (x + tx) + " " + y + " " + (z + tz) + " minecraft:torch");
            for (int px = -28; px <= 28; px += 8)
                selfTestCommand("fill " + (x + px) + " " + y + " " + (z - 20) + " " + (x + px) + " " + (y + 3) + " " + (z - 18) + " minecraft:stone_bricks");
        }
        if (Boolean.getBoolean("vulkanfish.torchField") && player != null && f > 320) player.setPos(x0 + 0.5, 128, z0 + 5.5);
        if ((f >= 2000 && f < 2008)) pendingScreenshot = f;
        if (f == 1300 || f == 2800) NativePassRunner.debugView = 4;
        if (f == 1320 || f == 2820) pendingScreenshot = f;
        if (f == 1330 || f == 2830) NativePassRunner.debugView = 0;
        if (f == 1350 || f == 2850) pendingScreenshot = f;
    }

    private String fgTestMode;

    private void fgTest(long f) {
        var player = Minecraft.getInstance().player;
        var opts = Minecraft.getInstance().options;
        if (f == 300) {
            selfTestCommand("gamemode spectator @p");
            selfTestCommand("time set 2500");
            selfTestCommand("tp @p " + NEAR_X + " 100 " + NEAR_Z + " 0 20");
            opts.pauseOnLostFocus = false; // sonst liegt das Pausemenue (GUI) ueber den FG-Bildern
            if (Boolean.getBoolean("vulkanfish.fgNoVsync")) opts.enableVsync().set(false);
        }
        if (f == 1500) {
            opts.pauseOnLostFocus = true;
            if (Boolean.getBoolean("vulkanfish.fgNoVsync")) opts.enableVsync().set(true);
        }
        if (f == 900) selfTestCommand("execute positioned " + NEAR_X + " 0 " + NEAR_Z + " positioned over motion_blocking run tp @p ~ ~12 ~ 0 20");
        float step = Float.parseFloat(System.getProperty("vulkanfish.fgYawStep", "0.5"));
        if (f >= 300 && player != null) lockView(player, f > 1000 ? (f - 1000) * step : 0.0f, 20.0f);
        FgPresenter fp = FgPresenter.instance();
        if (fp == null) {
            if (f == 1400) LOG.info("[vulkanfish] FG-Test ohne FG: {} FPS, Oberflaeche {}", Minecraft.getInstance().getFps(),
                    Minecraft.getInstance().windowSurface().currentConfiguration());
            return;
        }
        if (f == 1100) fp.resetStats();
        // Umschalten im laufenden Spiel: aus (zurueck zu Mojangs Present) und wieder an
        if (f == 1450) {
            fgTestMode = System.getProperty("vulkanfish.frameGen", "");
            System.setProperty("vulkanfish.frameGen", "1");
        }
        if (f == 1480 && fgTestMode != null) System.setProperty("vulkanfish.frameGen", fgTestMode.isEmpty() ? "2" : fgTestMode);
        if (f == 1550) LOG.info("[vulkanfish] FG-Test nach Umschalten: {}", fp.stats());
        if (f == 1560) Minecraft.getInstance().debugEntries.setOverlayVisible(true); // F3 mit DLSS-FPS
        if (f == 1590) pendingGuiScreenshot = f;
        if (f == 1595) Minecraft.getInstance().debugEntries.setOverlayVisible(false);
        if (f == 1400) {
            LOG.info("[vulkanfish] FG-Test Takt: {} | {} FPS (angezeigt {}), Oberflaeche {}, Drossel {}", fp.stats(), Minecraft.getInstance().getFps(), fp.displayedFps(),
                    Minecraft.getInstance().windowSurface().currentConfiguration(),
                    Minecraft.getInstance().getFramerateLimitTracker().getThrottleReason());
            FgPresenter.dumpPackets = 2;
        }
    }

    private volatile net.minecraft.core.BlockPos biomeTestPos;
    private long biomeTestFrame;

    /**
     * Biom-A/B-Selbsttest (-Dvulkanfish.biomeTest=<biom>): naechstes Vorkommen suchen (Server), aus
     * 110 Bloecken Hoehe schraeg darauf blicken; mit -Dvulkanfish.testRenderDistance=32 bzw. 8 laufen
     * lassen -> gleiche Kamera einmal Nahfeld, einmal LOD. Keine Weltaenderung.
     */
    private void biomeTest(long f) {
        var player = Minecraft.getInstance().player;
        Integer rd = Integer.getInteger("vulkanfish.testRenderDistance");
        if (f == 200 && rd != null) Minecraft.getInstance().options.renderDistance().set(rd);
        if (f == 250) {
            var server = Minecraft.getInstance().getSingleplayerServer();
            if (server != null) server.execute(() -> {
                var lvl = server.overworld();
                var found = lvl.findClosestBiome3d(h -> h.unwrapKey().map(k -> k.identifier().getPath().equals(BIOME_TEST)).orElse(false),
                        new net.minecraft.core.BlockPos(Integer.getInteger("vulkanfish.biomeFromX", 0), 64,
                                Integer.getInteger("vulkanfish.biomeFromZ", 0)), 12800, 32, 64);
                if (found != null) {
                    biomeTestPos = found.getFirst();
                    LOG.info("[vulkanfish] Biomtest: {} bei {}", BIOME_TEST, biomeTestPos);
                } else {
                    LOG.warn("[vulkanfish] Biomtest: {} nicht gefunden", BIOME_TEST);
                }
            });
        }
        var pos = biomeTestPos;
        if (pos != null && biomeTestFrame == 0) { // Suche fertig (dauert je nach Entfernung Sekunden)
            biomeTestFrame = f;
            selfTestCommand("gamemode spectator @p");
            selfTestCommand("tp @p " + (pos.getX() - 150) + " 110 " + pos.getZ() + " -90 25");
            FrameDataCapture.testSunAngle = 25.0f;
        }
        if (biomeTestFrame > 0 && player != null) lockView(player, -90.0f, 25.0f);
        if (biomeTestFrame > 0 && (f == biomeTestFrame + 2400 || f == biomeTestFrame + 2401)) {
            pendingScreenshot = f;
            if (f == biomeTestFrame + 2400) {
                // Pruefung an echten Bloecken: Eis an der Oberflaeche (Server) gegen die LOD-Formel
                var server = Minecraft.getInstance().getSingleplayerServer();
                var at = pos;
                if (server != null) server.execute(() -> {
                    var lvl = server.overworld();
                    int n = 0, same = 0, iceV = 0, iceL = 0;
                    for (int dz = -128; dz < 128; dz += 2) {
                        for (int dx = -128; dx < 128; dx += 2) {
                            int x = at.getX() + dx, z = at.getZ() + dz;
                            if (!lvl.hasChunk(x >> 4, z >> 4)) continue;
                            var top = lvl.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, new net.minecraft.core.BlockPos(x, 0, z)).below();
                            var st = lvl.getBlockState(top);
                            boolean ice = st.is(net.minecraft.world.level.block.Blocks.ICE);
                            if (!ice && !st.is(net.minecraft.world.level.block.Blocks.WATER)) continue; // nur Wasserflaechen
                            var biome = lvl.getBiome(top);
                            if (!biome.unwrapKey().map(k -> k.identifier().getPath().contains("frozen_ocean")).orElse(false)) continue;
                            boolean ours = simon.vulkanfish.client.lod.gen.FreezeNoise.freezes(biome.value().getBaseTemperature(),
                                    simon.vulkanfish.client.lod.gen.LodBiomeTable.frozenModifierOf(biome.value()), x, top.getY(), z, lvl.getSeaLevel());
                            n++;
                            if (ice) iceV++;
                            if (ours) iceL++;
                            if (ours == ice) same++;
                        }
                    }
                    LOG.info("[vulkanfish] Biomtest Vereisung: {} Wasserflaechen, Vanilla Eis {}, Formel Eis {}, gleich {} %", n, iceV, iceL,
                            n > 0 ? String.format("%.1f", same * 100.0 / n) : "-");
                });
            }
            if (f == biomeTestFrame + 2401) {
                LOG.info("[vulkanfish] Biomtest fertig bei {}", player != null ? player.blockPosition() : null);
                Minecraft.getInstance().stop();
            }
        }
    }

    private void iceTest(long f) {
        BlockPos hit = new BlockPos(34, 62, -56), hit2 = new BlockPos(36, 62, -54);
        if (f == 300) {
            selfTestCommand("gamerule minecraft:random_tick_speed 0");
            selfTestCommand("gamerule minecraft:advance_time false");
            selfTestCommand("gamerule minecraft:advance_weather false");
            selfTestCommand("weather clear");
            selfTestCommand("kill @e[type=!player]");
            selfTestCommand("fill 18 63 -78 50 80 -44 minecraft:air");
            selfTestCommand("fill 18 58 -78 50 62 -44 minecraft:stone");
            selfTestCommand("fill 25 59 -62 43 61 -46 minecraft:water");
            selfTestCommand("fill 25 62 -62 43 62 -46 minecraft:ice");
            selfTestCommand("fill 39 63 -60 40 65 -60 minecraft:glass");
            selfTestCommand("fill 23 63 -70 23 67 -66 minecraft:stone_bricks"); // Wand: Kuh steht in ihrem Schatten
            selfTestCommand("summon minecraft:cow 27 63 -68 {NoAI:1b,Rotation:[90f,0f]}");
            selfTestCommand("summon minecraft:sheep 31 63 -67 {NoAI:1b,Rotation:[45f,0f]}");
            selfTestCommand("summon minecraft:villager 35 63 -68 {NoAI:1b,Rotation:[180f,0f]}");
            selfTestCommand("summon minecraft:armor_stand 39 63 -68 {ShowArms:1b,ArmorItems:[{},{},{id:\"minecraft:iron_chestplate\",count:1},{id:\"minecraft:iron_helmet\",count:1}]}");
        }
        var player = Minecraft.getInstance().player;
        if (f >= 320 && player != null) {
            if (f == 320) {
                selfTestCommand("gamemode spectator @p");
                selfTestCommand("tp @p 33 67 -76 0 30"); // Tiere + Schatten
            }
            if (f == 650) selfTestCommand("tp @p 34 68.5 -66 0 48"); // Teich
            if (f == 850) selfTestCommand("tp @p 35 64.6 -59.5 0 30"); // nah an die Bruchstellen
            lockView(player, 0.0f, f >= 850 ? 30.0f : f >= 650 ? 48.0f : 30.0f);
        }
        if (f == 330) FrameDataCapture.testSunAngle = 20.0f;
        if (f == 600) {
            var b = simon.vulkanfish.client.render.EntityShadowCapture.frame();
            LOG.info("[vulkanfish] Eistest: {} Entity-Schatten-Batches, {} Vertices", b.size(),
                    b.stream().mapToInt(simon.vulkanfish.client.render.EntityShadowCapture.Batch::vertexCount).sum());
        }
        var level = Minecraft.getInstance().level;
        if (level != null && f >= 710 && f < 790) level.destroyBlockProgress(4711, hit, (int) ((f - 710) / 8));
        if (level != null && f == 790) level.destroyBlockProgress(4711, hit, -1);
        if (f == 800) {
            selfTestCommand("setblock " + hit.getX() + " " + hit.getY() + " " + hit.getZ() + " minecraft:water"); // Spielerabbau: Eis -> Wasser
            selfTestCommand("setblock " + hit2.getX() + " " + hit2.getY() + " " + hit2.getZ() + " minecraft:air");
        }
        if (f == 600 || f == 700 || f == 750 || f == 785 || f == 801 || f == 803 || f == 806 || f == 812 || f == 840 || f == 900 || f == 940) pendingScreenshot = f;
    }

    private final java.util.List<Long> testFrameNs = new java.util.ArrayList<>();
    private long spikeLastNs, spikeLastOurs, spikeLastGc;
    private int spikeCount;

    /** Selbsttest: Frames ueber 12 ms zerlegen (unsere CPU-Teile, GC, Rest = Vanilla/Treiber). */
    private long spikeProbe(long f) {
        long now = System.nanoTime();
        long interval = spikeLastNs != 0L ? now - spikeLastNs : 0L;
        long ours = cpuNanosStart + cpuNanosTerrain + cpuNanosWater + cpuNanosTaa;
        long gc = 0;
        for (var b : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) gc += Math.max(0, b.getCollectionTime());
        if (spikeLastNs != 0L) {
            double frameMs = (now - spikeLastNs) / 1e6;
            double oursMs = Math.max(0, ours - spikeLastOurs) / 1e6; // Akkumulatoren werden alle 10 s geleert
            if (frameMs > 12.0 && spikeCount++ < 40) {
                LOG.info("[vulkanfish] Spitze f{}: {} ms, davon unsere CPU {} ms, GC {} ms", f, String.format("%.1f", frameMs),
                        String.format("%.1f", oursMs), gc - spikeLastGc);
            }
        }
        spikeLastNs = now;
        spikeLastOurs = ours;
        spikeLastGc = gc;
        return interval;
    }
    private long testLastNs;

    private void lodTest(long f) {
        var player = Minecraft.getInstance().player;
        String dim = System.getProperty("vulkanfish.lodDim");
        // Sichtweite des Nahfelds fuer den Test (-Dvulkanfish.testRenderDistance=12): LOD-Uebergang naeher
        Integer rd = Integer.getInteger("vulkanfish.testRenderDistance");
        if (f == 200 && rd != null) Minecraft.getInstance().options.renderDistance().set(rd);
        if (f == 300 && dim != null) {
            selfTestCommand("gamemode spectator @p");
            // Nether: unter der Decke; End: ueber den aeusseren Inseln
            selfTestCommand("execute in minecraft:" + dim + " run tp @p " + (dim.equals("the_nether") ? "0 90 0 0 5" : "1200 110 0 0 20"));
            FrameDataCapture.testSunAngle = 25.0f;
        }
        if (f == 300 && dim == null) {
            selfTestCommand("gamemode spectator @p");
            selfTestCommand("tp @p 34 190 -69 0 12");
            FrameDataCapture.testSunAngle = 25.0f;
        }
        if (f >= 300 && player != null && !(Boolean.getBoolean("vulkanfish.lodMove") && f >= 2400)) {
            float yaw = f < 1700 ? 0.0f : 90.0f;
            lockView(player, yaw, dim == null ? 12.0f : dim.equals("the_nether") ? 5.0f : 20.0f);
        }
        if (f == 1500 && nativeRunner != null) nativeRunner.passTimings();
        if (f == 1650 && nativeRunner != null) LOG.info("[vulkanfish] Selbsttest GPU-Zeit LOD-Blick: {}", nativeRunner.passTimings());
        // Bewegung auf Bodenhoehe (-Dvulkanfish.lodMove): erst Fernfeld am Start, dann 2500 Bloecke weiter
        // springen und langsam weiterfliegen -> muss das Fernfeld nachziehen (Stufen, Farben, Loecher)
        if (Boolean.getBoolean("vulkanfish.lodMove") && player != null) {
            if (f == 2400) selfTestCommand("tp @p 2534 120 -69 -90 3");
            // Debug-Ansicht fuer einen Screenshot: -Dvulkanfish.lodMoveDebug=<Ansicht> (-Dvulkanfish.lodMoveDebugFrame, Standard 2440)
            int dbgF = Integer.getInteger("vulkanfish.lodMoveDebugFrame", 2440);
            if (f == dbgF - 2) NativePassRunner.debugView = Integer.getInteger("vulkanfish.lodMoveDebug", 0);
            if (f == dbgF + 5) NativePassRunner.debugView = 0;
            if (f >= 2400) {
                // Blick nach Osten (+X), Flug vorwaerts: neues Fernfeld kommt von vorn
                if (f > 2700) player.setPos(player.getX() + 0.35, player.getY(), player.getZ()); // ~55 Bl./s
                lockView(player, -90.0f, 3.0f);
            }
            if (f == 2420 || f == 2440 || f == 2460 || f == 2500 || f == 2550 || f == 2700 || f == 3300 || f == 3900 || f == 4500) {
                pendingScreenshot = f;
                LOG.info("[vulkanfish] Bewegungstest f{} bei x={}", f, (int) player.getX());
            }
        }
        // Bobby-Cache fuellen: an entfernte Orte springen (Chunks laden, beim Verlassen cacht Bobby sie)
        if (Boolean.getBoolean("vulkanfish.bobbyWarm")) {
            if (f == 1700) selfTestCommand("tp @p 1634 190 -69");
            if (f == 2000) selfTestCommand("tp @p 1634 190 1531");
            if (f == 2300) selfTestCommand("tp @p 34 190 1531");
            if (f == 2600) selfTestCommand("tp @p 34 190 -69");
        }
        // Endzustand (Fernfeld fertig): GPU-Zeiten + FPS ueber 300 Frames
        if (f == 5000 && nativeRunner != null) {
            nativeRunner.passTimings();
            nativeRunner.slotWaitNs = 0;
            testFrameNs.clear();
        }
        if (f > 5000 && f <= 5300) {
            long now = System.nanoTime();
            if (testLastNs != 0L) testFrameNs.add(now - testLastNs);
        }
        testLastNs = System.nanoTime();
        if (f >= 4500 && f <= 5300) spikeProbe(f);
        if (f == 5300 && nativeRunner != null) {
            long[] a = testFrameNs.stream().mapToLong(Long::longValue).sorted().toArray();
            double avg = java.util.Arrays.stream(a).average().orElse(0) / 1e6;
            double p50 = a.length > 0 ? a[a.length / 2] / 1e6 : 0, p99 = a.length > 0 ? a[a.length * 99 / 100] / 1e6 : 0;
            LOG.info("[vulkanfish] Selbsttest GPU-Zeit Endzustand: {} | {} FPS; Frame-Intervall mittel {} ms, Median {} ms, p99 {} ms; Warten auf GPU {} ms/Frame",
                    nativeRunner.passTimings(), Minecraft.getInstance().getFps(), String.format("%.2f", avg), String.format("%.2f", p50),
                    String.format("%.2f", p99), String.format("%.2f", nativeRunner.slotWaitNs / 1e6 / Math.max(1, a.length)));
        }
        if (f == 900 || f == 1600 || f == 2300 || f == 5300) pendingScreenshot = f;
        // Flug: 0,15 Bloecke je Frame nach Osten (~25 Bloecke/s), Frame-Zeiten wie oben
        if (f > 5400 && f <= 6400 && player != null) {
            player.setPos(player.getX() + 0.15, player.getY(), player.getZ());
            if (f == 5401) {
                testFrameNs.clear();
                spikeCount = 0;
                if (nativeRunner != null) nativeRunner.slotWaitNs = 0;
            }
            long iv = spikeProbe(f);
            if (f > 5401 && iv > 0) testFrameNs.add(iv);
        }
        if (f == 6400) {
            long[] a = testFrameNs.stream().mapToLong(Long::longValue).sorted().toArray();
            LOG.info("[vulkanfish] Selbsttest Flug: Frame-Intervall mittel {} ms, Median {} ms, p99 {} ms, max {} ms",
                    String.format("%.2f", java.util.Arrays.stream(a).average().orElse(0) / 1e6),
                    String.format("%.2f", a.length > 0 ? a[a.length / 2] / 1e6 : 0),
                    String.format("%.2f", a.length > 0 ? a[a.length * 99 / 100] / 1e6 : 0),
                    String.format("%.2f", a.length > 0 ? a[a.length - 1] / 1e6 : 0));
            pendingScreenshot = f;
        }
    }

    /** Selbsttest: Beschriftungen aller Widgets (rekursiv) sammeln. */
    private static void collectWidgetText(net.minecraft.client.gui.components.events.GuiEventListener l, StringBuilder sb) {
        if (l instanceof net.minecraft.client.gui.components.AbstractWidget aw) sb.append(aw.getMessage().getString()).append(" | ");
        if (l instanceof net.minecraft.client.gui.components.events.ContainerEventHandler c) {
            for (net.minecraft.client.gui.components.events.GuiEventListener child : c.children()) collectWidgetText(child, sb);
        }
    }

    /** Selbsttest: Blickrichtung fest (auch die Vorwerte, sonst verschiebt echte Mausbewegung das Bild). */
    private static void lockView(net.minecraft.world.entity.player.Player player, float yaw, float pitch) {
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;
    }

    /** Selbsttest: Befehl im integrierten Server (nur mit -Dvulkanfish.testWorldEdits). */
    private static void selfTestCommand(String command) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
        LOG.info("[vulkanfish] Selbsttest-Befehl: /{}", command);
    }

    /** SectionCompilerMixin: darf Vanilla SOLID/CUTOUT/Fluids weglassen? (Worker-Threads) */
    public static boolean vanillaOpaqueDisabled() {
        return vanillaOpaqueDisabled;
    }

    /** Vanilla wieder alles meshen lassen und die Section-Geometrie neu aufbauen (Render-Thread). */
    private void restoreVanillaOpaque() {
        vanillaOpaqueDisabled = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            mc.levelRenderer.invalidateCompiledGeometry(mc.level, mc.options, mc.gameRenderer.mainCamera(), mc.getBlockColors());
        }
    }

    /** LevelRendererMixin: Overworld-Himmel kommt aus unserem Deferred-Pass. */
    public boolean replacesSky() {
        Minecraft mc = Minecraft.getInstance();
        return initialized && nativeRunner.isReady() && mc.level != null
                && mc.level.dimension() == net.minecraft.world.level.Level.OVERWORLD;
    }

    /** Vor Mojangs Device-Zerstoerung (RenderSystemMixin). */
    public synchronized void shutdown() {
        if (shutDown) return;
        shutDown = true;
        initialized = false;
        if (streamer != null) streamer.shutdown();
        if (nativeRunner != null) {
            try {
                nativeRunner.destroy();
            } catch (Throwable t) {
                LOG.warn("[vulkanfish] Freigabe der nativen Ressourcen fehlgeschlagen", t);
            }
        }
        RenderScale.destroy();
    }

    public boolean useGpuDrivenPath() {
        return initialized && config.enableMeshShaders();
    }

}
