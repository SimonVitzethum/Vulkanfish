package simon.vulkanfish.client.gpu;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.SectionPos;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hardware-Raytracing-Szene fuer die Blocklicht-Schatten (eigene Implementierung).
 *
 * <p>BLAS pro Section, gebaut DIREKT aus dem Terrain-Vertexpuffer: die Positionen liegen
 * dort als u16 (R16G16B16_UNORM, Stride 16) vor; Section-Ursprung und Skalierung stecken
 * in der Instanz-Transformation. Der Mesher sortiert massive Quads (opak, kein Any-Hit)
 * und Cutout-Quads (Alpha-Test im Strahl) in zwei zusammenhaengende Bereiche am Anfang
 * der Section -> keine Kopie, kein eigener Vertexpuffer. Pflanzen und leuchtende Bloecke
 * sind nicht enthalten.
 *
 * <p>Nur Sections im Fenster um die Kamera bekommen ein BLAS (Speicher, Bauzeit); die TLAS
 * wird jeden Frame aus den aktuellen BLAS neu gebaut (ein paar Hundert Instanzen, Mikro-
 * sekunden). Lichtquellen kommen ebenfalls vom Mesher und werden pro Frame in eine Liste
 * im Fenster geschrieben; die GPU sortiert sie in ein Zellgitter (rt_lights.slang).
 */
final class RtAccel {
    private static final Logger LOG = LoggerFactory.getLogger("vulkanfish");
    static final int WINDOW_SECTIONS = 5;           // BLAS-Fenster: +-5 Sections (Chebyshev)
    static final int GRID_CELLS = 16;               // Lichtgitter 16^3 Zellen
    static final int CELL_BLOCKS = 8;               // a 8 Bloecke = 128^3 um die Kamera
    static final int CELL_CAP = 24;                 // Lichter pro Zelle
    static final int MAX_LIGHTS = 8192;
    static final int LIGHT_BYTES = 16;
    private static final int MAX_INSTANCES = 4096;
    private static final int INSTANCE_BYTES = 64;
    private static final int GEOM_QUADS = 65536;    // Quads pro BLAS-Geometrie (= Index-Muster)
    private static final long POOL_UNIT = 256;      // Offset-Ausrichtung von Acceleration Structures
    private static final long MAX_POOL_BYTES = 256L << 20;  // NVIDIA: ~60 KiB pro Section-BLAS
    private static final long MIN_POOL_BYTES = 64L << 20;
    private static final long SCRATCH_BYTES = 64L << 20;
    private static final int MAX_BUILDS_PER_FRAME = 96;
    private static final float QUANT_SCALE = 65535f / 2048f; // unorm16 -> Bloecke (siehe tvPos)

    private final VkDevice dev;
    private final long vma;
    private final int frames;
    private final long vertsAddress;
    private final List<long[]> ownBuffers = new ArrayList<>(); // {buffer, allocation}
    private long indexBuffer;
    private long indexAddress;
    private long pool;
    private long poolAddress;
    private RangeAllocator poolAlloc;
    private long scratch;
    private long scratchAddress;
    private long scratchAlign = 128;
    private final long[] instanceBuf;
    private final long[] instanceMapped;
    private final long[] instanceAddr;
    private final long[] lightBuf;
    private final long[] lightMapped;
    private final int[] lightVersionInSlot;
    private long tlas;
    private long tlasBuffer;
    private long tlasScratchOffset;
    private int instanceCount;

    private final Long2ObjectOpenHashMap<Entry> entries = new Long2ObjectOpenHashMap<>();
    // Sections mit BLAS (klein, ~Fenstergroesse): TLAS und Freigabe ohne Lauf ueber alle Sections
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet withAs = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private final List<long[]> destroyQueue = new ArrayList<>(); // {as, poolOff, poolLen, timelineValue}
    private final int[] lightData = new int[MAX_LIGHTS * 4];
    private int lightCount;
    private int lightVersion;
    private boolean lightsDirty = true;
    private long lightCellKey = Long.MIN_VALUE;
    private int gridX, gridY, gridZ;
    private boolean poolFullWarned;
    private long builtTotal;
    private long reused;
    private long lastLogMs;

    private static final class Entry {
        long key;
        int quadStart;
        int solid;
        int cutout;
        int[] lights;
        long as;          // 0 = kein BLAS
        long asAddress;
        int poolOff = -1;
        int poolLen;
        long rtHash;
        boolean stale = true;
    }

    RtAccel(VkDevice dev, VkPhysicalDevice phys, long vma, int frames, long vertsBuffer) {
        this.dev = dev;
        this.vma = vma;
        this.frames = frames;
        this.vertsAddress = deviceAddress(vertsBuffer);
        instanceBuf = new long[frames];
        instanceMapped = new long[frames];
        instanceAddr = new long[frames];
        lightBuf = new long[frames];
        lightMapped = new long[frames];
        lightVersionInSlot = new int[frames];
        try {
            create(phys);
        } catch (RuntimeException e) {
            destroy(); // teilweise angelegte Puffer nicht auf Mojangs Device liegen lassen
            throw e;
        }
    }

    private void create(VkPhysicalDevice phys) {
        try (Arena arena = new Arena()) {
            VkPhysicalDeviceAccelerationStructurePropertiesKHR asProps =
                    VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(arena.stack()).sType$Default();
            VkPhysicalDeviceProperties2 p2 = VkPhysicalDeviceProperties2.calloc(arena.stack()).sType$Default().pNext(asProps.address());
            VK12.vkGetPhysicalDeviceProperties2(phys, p2);
            scratchAlign = Math.max(asProps.minAccelerationStructureScratchOffsetAlignment(), 1);
        }
        int bda = VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        int buildInput = KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
        int asStorage = KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR;
        // Gemeinsames Index-Muster: Quad q -> 4q+{0,1,2, 0,2,3} (wie die Meshlet-Dreiecke)
        long[] ib = buffer((long) GEOM_QUADS * 6 * 4, bda | buildInput, true);
        indexBuffer = ib[0];
        ByteBuffer idx = MemoryUtil.memByteBuffer(ib[2], GEOM_QUADS * 6 * 4);
        for (int q = 0; q < GEOM_QUADS; q++) {
            int v = q * 4;
            idx.putInt(v).putInt(v + 1).putInt(v + 2).putInt(v).putInt(v + 2).putInt(v + 3);
        }
        indexAddress = deviceAddress(indexBuffer);
        // BLAS-Pool (so gross wie moeglich, halbiert bei VRAM-Mangel)
        for (long bytes = MAX_POOL_BYTES; ; bytes /= 2) {
            try {
                pool = buffer(bytes, asStorage | bda, false)[0];
                poolAlloc = new RangeAllocator((int) (bytes / POOL_UNIT));
                LOG.info("[vulkanfish] RT: BLAS-Pool {} MiB", bytes >> 20);
                break;
            } catch (IllegalStateException e) {
                if (bytes / 2 < MIN_POOL_BYTES) throw e;
            }
        }
        poolAddress = deviceAddress(pool);
        scratch = buffer(SCRATCH_BYTES, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | bda, false)[0];
        scratchAddress = deviceAddress(scratch);
        for (int i = 0; i < frames; i++) {
            long[] inst = buffer((long) MAX_INSTANCES * INSTANCE_BYTES, bda | buildInput, true);
            instanceBuf[i] = inst[0];
            instanceMapped[i] = inst[2];
            instanceAddr[i] = deviceAddress(inst[0]);
            long[] lb = buffer((long) MAX_LIGHTS * LIGHT_BYTES, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
            lightBuf[i] = lb[0];
            lightMapped[i] = lb[2];
            lightVersionInSlot[i] = -1;
        }
        createTlas();
    }

    // ---------- Streamer-Seite (Render-Thread) ----------

    /** Neue Geometrie einer Section (nach dem Upload in diesem Frame). */
    void sectionApplied(long key, int quadStart, int solid, int cutout, long rtHash, int[] lights) {
        Entry e = entries.get(key);
        if (e == null) {
            e = new Entry();
            e.key = key;
            entries.put(key, e);
        }
        boolean hadLights = e.lights != null && e.lights.length > 0;
        // Gleiche RT-Geometrie (nur Wasser/Pflanzen/Licht geaendert): BLAS behalten, es ist
        // eigenstaendig; nur der Cutout-Index fuer den Alpha-Test zeigt auf den neuen Bereich
        boolean sameGeometry = e.as != 0L && !e.stale && e.rtHash == rtHash && e.solid == solid && e.cutout == cutout;
        e.quadStart = quadStart;
        e.solid = solid;
        e.cutout = cutout;
        e.rtHash = rtHash;
        e.lights = lights;
        if (!sameGeometry) e.stale = true;
        else reused++;
        if (hadLights || lights.length > 0) lightsDirty = true;
    }

    /** Section entfernt oder leer: BLAS nach Ablauf der laufenden Frames freigeben. */
    void sectionRemoved(long key, long retireValue) {
        Entry e = entries.remove(key);
        if (e == null) return;
        retire(e, retireValue);
        if (e.lights != null && e.lights.length > 0) lightsDirty = true;
    }

    void clear(long retireValue) {
        for (Entry e : entries.values()) retire(e, retireValue);
        entries.clear();
        withAs.clear();
        lightsDirty = true;
    }

    private void retire(Entry e, long retireValue) {
        if (e.as != 0L) destroyQueue.add(new long[]{e.as, e.poolOff, e.poolLen, retireValue});
        withAs.remove(e.key);
        e.as = 0L;
        e.poolOff = -1;
    }

    // ---------- Frame-Aufnahme ----------

    /**
     * BLAS fuer neue/geaenderte Sections im Fenster bauen, ausserhalb freigeben, TLAS neu bauen.
     * Muss NACH den Vertex-Uploads des Frames aufgenommen werden (Barriere davor: Transfer -> AS-Build).
     */
    void record(Arena arena, VkCommandBuffer cmd, int slot, double camX, double camY, double camZ,
                long completedValue, long retireValue) {
        // 1) Abgelaufene BLAS zerstoeren (GPU ist mit allen Frames, die sie nutzen konnten, fertig)
        for (int i = destroyQueue.size() - 1; i >= 0; i--) {
            long[] d = destroyQueue.get(i);
            if (d[3] > completedValue) continue;
            KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(dev, d[0], null);
            if (d[1] >= 0) poolAlloc.free((int) d[1], (int) d[2]);
            destroyQueue.remove(i);
        }
        int csx = SectionPos.blockToSectionCoord(camX);
        int csy = SectionPos.blockToSectionCoord(camY);
        int csz = SectionPos.blockToSectionCoord(camZ);

        // 2) Weit draussen: BLAS freigeben (nur ueber Sections mit BLAS)
        long[] asKeys = withAs.toLongArray();
        for (long k : asKeys) {
            if (cheb(k, csx, csy, csz) <= WINDOW_SECTIONS + 1) continue;
            Entry e = entries.get(k);
            if (e == null) {
                withAs.remove(k);
                continue;
            }
            retire(e, retireValue);
            e.stale = true;
        }
        // Kandidaten: im Fenster ohne aktuelles BLAS (direkt nachschlagen statt alle Sections)
        List<Entry> todo = new ArrayList<>();
        int w = WINDOW_SECTIONS + 1;
        for (int dy = -w; dy <= w; dy++) {
            for (int dz = -w; dz <= w; dz++) {
                for (int dx = -w; dx <= w; dx++) {
                    Entry e = entries.get(SectionPos.asLong(csx + dx, csy + dy, csz + dz));
                    if (e == null) continue;
                    int d = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
                    if (d <= WINDOW_SECTIONS && (e.stale || e.as == 0L) && e.solid + e.cutout > 0) todo.add(e);
                    else if (e.stale && e.solid + e.cutout == 0 && e.as != 0L) {
                        retire(e, retireValue); // Section hat keine RT-Geometrie mehr
                        e.stale = false;
                    } else if (e.solid + e.cutout == 0) {
                        e.stale = false;
                    }
                }
            }
        }
        todo.sort((a, b) -> Integer.compare(cheb(a.key, csx, csy, csz), cheb(b.key, csx, csy, csz)));
        buildBlas(arena, cmd, todo, retireValue);

        // 3) TLAS aus allen BLAS im Fenster
        long base = instanceMapped[slot];
        int n = 0;
        for (long k : withAs) {
            Entry e = entries.get(k);
            if (e == null || e.as == 0L || n >= MAX_INSTANCES || cheb(e.key, csx, csy, csz) > WINDOW_SECTIONS) continue;
            long o = base + (long) n * INSTANCE_BYTES;
            float ox = SectionPos.sectionToBlockCoord(SectionPos.x(e.key)) - 8f;
            float oy = SectionPos.sectionToBlockCoord(SectionPos.y(e.key)) - 8f;
            float oz = SectionPos.sectionToBlockCoord(SectionPos.z(e.key)) - 8f;
            // 3x4 zeilenweise: unorm16-Position * (65535/2048) + (Ursprung - 8)
            MemoryUtil.memPutFloat(o, QUANT_SCALE);
            MemoryUtil.memPutFloat(o + 4, 0f);
            MemoryUtil.memPutFloat(o + 8, 0f);
            MemoryUtil.memPutFloat(o + 12, ox);
            MemoryUtil.memPutFloat(o + 16, 0f);
            MemoryUtil.memPutFloat(o + 20, QUANT_SCALE);
            MemoryUtil.memPutFloat(o + 24, 0f);
            MemoryUtil.memPutFloat(o + 28, oy);
            MemoryUtil.memPutFloat(o + 32, 0f);
            MemoryUtil.memPutFloat(o + 36, 0f);
            MemoryUtil.memPutFloat(o + 40, QUANT_SCALE);
            MemoryUtil.memPutFloat(o + 44, oz);
            // Custom-Index = erstes Cutout-Quad (Alpha-Test), Maske 0xFF
            MemoryUtil.memPutInt(o + 48, ((e.quadStart + e.solid) & 0xFFFFFF) | (0xFF << 24));
            MemoryUtil.memPutInt(o + 52, KHRAccelerationStructure.VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR << 24);
            MemoryUtil.memPutLong(o + 56, e.asAddress);
            n++;
        }
        instanceCount = n;
        buildTlas(arena, cmd, slot);

        // 4) Lichtliste (nur bei Aenderung / neuer Kamerazelle neu zusammenstellen)
        int gx = gridOrigin(camX);
        int gy = gridOrigin(camY);
        int gz = gridOrigin(camZ);
        long cellKey = ((long) gx & 0x1FFFFF) | (((long) gy & 0x1FFFFF) << 21) | (((long) gz & 0x1FFFFF) << 42);
        if (lightsDirty || cellKey != lightCellKey) {
            gridX = gx;
            gridY = gy;
            gridZ = gz;
            lightCellKey = cellKey;
            lightsDirty = false;
            gatherLights(csx, csy, csz);
            lightVersion++;
        }
        if (lightVersionInSlot[slot] != lightVersion) {
            MemoryUtil.memIntBuffer(lightMapped[slot], lightCount * 4).put(lightData, 0, lightCount * 4);
            lightVersionInSlot[slot] = lightVersion;
        }
        log();
    }

    private void buildBlas(Arena arena, VkCommandBuffer cmd, List<Entry> todo, long retireValue) {
        int count = Math.min(todo.size(), MAX_BUILDS_PER_FRAME);
        if (count == 0) return;
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer infos =
                VkAccelerationStructureBuildGeometryInfoKHR.calloc(count, arena.stack());
        PointerBuffer ranges = arena.mallocPointer(count);
        VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(arena.stack()).sType$Default();
        long scratchCursor = 0;
        int built = 0;
        for (int i = 0; i < count; i++) {
            Entry e = todo.get(i);
            // Cutout-Geometrien ZUERST: der Shader rechnet Quad = CustomIndex + GeometryIndex*GEOM_QUADS + Prim/2
            int cutGeoms = (e.cutout + GEOM_QUADS - 1) / GEOM_QUADS;
            int solidGeoms = (e.solid + GEOM_QUADS - 1) / GEOM_QUADS;
            int geomCount = cutGeoms + solidGeoms;
            VkAccelerationStructureGeometryKHR.Buffer geoms = VkAccelerationStructureGeometryKHR.calloc(geomCount, arena.stack());
            VkAccelerationStructureBuildRangeInfoKHR.Buffer rangeInfo =
                    VkAccelerationStructureBuildRangeInfoKHR.calloc(geomCount, arena.stack());
            int[] maxPrims = new int[geomCount];
            int g = 0;
            for (int k = 0; k < cutGeoms; k++, g++) {
                int q0 = e.solid + k * GEOM_QUADS;
                int qn = Math.min(GEOM_QUADS, e.cutout - k * GEOM_QUADS);
                geometry(geoms.get(g), e.quadStart + q0, qn, false);
                rangeInfo.get(g).primitiveCount(qn * 2);
                maxPrims[g] = qn * 2;
            }
            for (int k = 0; k < solidGeoms; k++, g++) {
                int q0 = k * GEOM_QUADS;
                int qn = Math.min(GEOM_QUADS, e.solid - k * GEOM_QUADS);
                geometry(geoms.get(g), e.quadStart + q0, qn, true);
                rangeInfo.get(g).primitiveCount(qn * 2);
                maxPrims[g] = qn * 2;
            }
            VkAccelerationStructureBuildGeometryInfoKHR info = infos.get(built).sType$Default()
                    .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .pGeometries(geoms).geometryCount(geomCount); // LWJGL setzt den Zaehler hier nicht selbst
            KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(dev,
                    KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info, maxPrims, sizes);
            long scratchOff = align(scratchCursor, scratchAlign);
            if (scratchOff + sizes.buildScratchSize() > SCRATCH_BYTES) break; // Rest im naechsten Frame
            int units = (int) ((sizes.accelerationStructureSize() + POOL_UNIT - 1) / POOL_UNIT);
            int off = poolAlloc.alloc(units);
            if (off < 0) {
                if (!poolFullWarned) {
                    poolFullWarned = true;
                    LOG.warn("[vulkanfish] RT: BLAS-Pool voll ({} Sections) – fernere Sections ohne Licht-Schatten", entries.size());
                }
                break;
            }
            LongBuffer pAs = arena.mallocLong(1);
            VkAccelerationStructureCreateInfoKHR ci = VkAccelerationStructureCreateInfoKHR.calloc(arena.stack()).sType$Default()
                    .buffer(pool).offset(off * POOL_UNIT).size(sizes.accelerationStructureSize())
                    .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
            check(KHRAccelerationStructure.vkCreateAccelerationStructureKHR(dev, ci, null, pAs), "createBlas");
            info.dstAccelerationStructure(pAs.get(0));
            info.scratchData().deviceAddress(scratchAddress + scratchOff);
            scratchCursor = scratchOff + sizes.buildScratchSize();
            ranges.put(built, rangeInfo.address());
            // Altes BLAS (vorige Geometrie) nach Ablauf der laufenden Frames freigeben
            retire(e, retireValue);
            e.as = pAs.get(0);
            withAs.add(e.key);
            e.poolOff = off;
            e.poolLen = units;
            e.asAddress = KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR(dev,
                    VkAccelerationStructureDeviceAddressInfoKHR.calloc(arena.stack()).sType$Default().accelerationStructure(e.as));
            e.stale = false;
            built++;
        }
        if (built == 0) return;
        infos.limit(built);
        ranges.limit(built);
        KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(cmd, infos, ranges);
        builtTotal += built;
        asBarrier(arena, cmd);
    }

    private void geometry(VkAccelerationStructureGeometryKHR geom, int firstQuad, int quads, boolean opaque) {
        geom.sType$Default().geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                .flags(opaque ? KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR
                        : KHRAccelerationStructure.VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR);
        geom.geometry().triangles().sType$Default()
                .vertexFormat(VK10.VK_FORMAT_R16G16B16_UNORM)
                .vertexStride(SectionMesher.VERTEX_BYTES)
                .maxVertex(quads * 4 - 1)
                .indexType(VK10.VK_INDEX_TYPE_UINT32);
        geom.geometry().triangles().vertexData().deviceAddress(vertsAddress + (long) firstQuad * 4 * SectionMesher.VERTEX_BYTES);
        geom.geometry().triangles().indexData().deviceAddress(indexAddress);
    }

    private void createTlas() {
        try (Arena arena = new Arena()) {
            VkAccelerationStructureGeometryKHR.Buffer geom = VkAccelerationStructureGeometryKHR.calloc(1, arena.stack());
            geom.get(0).sType$Default().geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR);
            geom.get(0).geometry().instances().sType$Default().arrayOfPointers(false);
            VkAccelerationStructureBuildGeometryInfoKHR info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(arena.stack())
                    .sType$Default().type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                    .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).pGeometries(geom).geometryCount(1);
            VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(arena.stack()).sType$Default();
            KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(dev,
                    KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info, new int[]{MAX_INSTANCES}, sizes);
            tlasBuffer = buffer(sizes.accelerationStructureSize(),
                    KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                            | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false)[0];
            LongBuffer p = arena.mallocLong(1);
            check(KHRAccelerationStructure.vkCreateAccelerationStructureKHR(dev,
                    VkAccelerationStructureCreateInfoKHR.calloc(arena.stack()).sType$Default().buffer(tlasBuffer)
                            .size(sizes.accelerationStructureSize())
                            .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR), null, p), "createTlas");
            tlas = p.get(0);
            // TLAS-Scratch am Ende des Scratch-Puffers (BLAS-Builds nutzen den Anfang, Barriere dazwischen)
            tlasScratchOffset = align(SCRATCH_BYTES - sizes.buildScratchSize() - scratchAlign, scratchAlign);
        }
    }

    private void buildTlas(Arena arena, VkCommandBuffer cmd, int slot) {
        VkAccelerationStructureGeometryKHR.Buffer geom = VkAccelerationStructureGeometryKHR.calloc(1, arena.stack());
        geom.get(0).sType$Default().geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR);
        geom.get(0).geometry().instances().sType$Default().arrayOfPointers(false).data().deviceAddress(instanceAddr[slot]);
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, arena.stack());
        info.get(0).sType$Default().type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .dstAccelerationStructure(tlas).pGeometries(geom).geometryCount(1);
        info.get(0).scratchData().deviceAddress(scratchAddress + tlasScratchOffset);
        VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, arena.stack());
        range.get(0).primitiveCount(instanceCount);
        KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(cmd, info, arena.pointers(range.address()));
        asBarrier(arena, cmd);
    }

    private static void asBarrier(Arena arena, VkCommandBuffer cmd) {
        VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, arena.stack());
        b.get(0).sType$Default()
                .srcAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                .dstAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR
                        | KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
        VK10.vkCmdPipelineBarrier(cmd, KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR | VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, b, null, null);
    }

    /** Alle Lichter der Sections im Fenster, die das Gitter (+Reichweite) beruehren. Naechste zuerst bei Ueberlauf. */
    private void gatherLights(int csx, int csy, int csz) {
        float minX = gridX * CELL_BLOCKS - 15f, maxX = (gridX + GRID_CELLS) * CELL_BLOCKS + 15f;
        float minY = gridY * CELL_BLOCKS - 15f, maxY = (gridY + GRID_CELLS) * CELL_BLOCKS + 15f;
        float minZ = gridZ * CELL_BLOCKS - 15f, maxZ = (gridZ + GRID_CELLS) * CELL_BLOCKS + 15f;
        List<Entry> near = new ArrayList<>();
        int w = WINDOW_SECTIONS;
        for (int dy = -w; dy <= w; dy++) {
            for (int dz = -w; dz <= w; dz++) {
                for (int dx = -w; dx <= w; dx++) {
                    Entry e = entries.get(SectionPos.asLong(csx + dx, csy + dy, csz + dz));
                    if (e != null && e.lights != null && e.lights.length > 0) near.add(e);
                }
            }
        }
        near.sort((a, b) -> Integer.compare(cheb(a.key, csx, csy, csz), cheb(b.key, csx, csy, csz)));
        int n = 0;
        outer:
        for (Entry e : near) {
            int[] l = e.lights;
            for (int i = 0; i + 3 < l.length; i += SectionMesher.LIGHT_INTS) {
                float x = Float.intBitsToFloat(l[i]), y = Float.intBitsToFloat(l[i + 1]), z = Float.intBitsToFloat(l[i + 2]);
                if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) continue;
                if (n >= MAX_LIGHTS) break outer;
                System.arraycopy(l, i, lightData, n * 4, 4);
                n++;
            }
        }
        lightCount = n;
    }

    private void log() {
        long now = System.currentTimeMillis();
        if (now - lastLogMs < 10_000) return;
        lastLogMs = now;
        LOG.info("[vulkanfish] RT: {} BLAS (Pool {}/{} MiB), TLAS {} Instanzen, {} Lichter, {} Builds gesamt, {} unveraendert behalten",
                withAs.size(),
                poolAlloc.top() * POOL_UNIT >> 20, poolAlloc.capacity() * POOL_UNIT >> 20, instanceCount, lightCount, builtTotal, reused);
    }

    // ---------- Zugriff fuer die Passes ----------

    long tlas() {
        return tlas;
    }

    long lightBuffer(int slot) {
        return lightBuf[slot];
    }

    int lightCount() {
        return lightCount;
    }

    int gridX() {
        return gridX;
    }

    int gridY() {
        return gridY;
    }

    int gridZ() {
        return gridZ;
    }

    // ---------- Hilfen ----------

    /** Ursprung des Lichtgitters (in Zellen) fuer eine Kamerakoordinate – identisch in Uniforms und Binning. */
    static int gridOrigin(double cam) {
        return Math.floorDiv((int) Math.floor(cam), CELL_BLOCKS) - GRID_CELLS / 2;
    }

    private static int cheb(long key, int cx, int cy, int cz) {
        return Math.max(Math.abs(SectionPos.x(key) - cx), Math.max(Math.abs(SectionPos.y(key) - cy), Math.abs(SectionPos.z(key) - cz)));
    }

    private static long align(long v, long a) {
        return (v + a - 1) / a * a;
    }

    /** VMA-Puffer; host = persistent gemappt (schreibkombiniert). @return {buffer, allocation, mapped} */
    private long[] buffer(long size, int usage, boolean host) {
        try (Arena arena = new Arena()) {
            VkBufferCreateInfo bi = VkBufferCreateInfo.calloc(arena.stack()).sType$Default()
                    .size(size).usage(usage).sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo aci = VmaAllocationCreateInfo.calloc(arena.stack());
            if (host) {
                aci.usage(Vma.VMA_MEMORY_USAGE_AUTO)
                        .flags(Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT | Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT)
                        .requiredFlags(VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            } else {
                aci.usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            }
            LongBuffer pb = arena.mallocLong(1);
            PointerBuffer pa = arena.mallocPointer(1);
            VmaAllocationInfo info = VmaAllocationInfo.calloc(arena.stack());
            check(Vma.vmaCreateBuffer(vma, bi, aci, pb, pa, info), "RT-Puffer " + (size >> 20) + " MiB");
            long[] r = {pb.get(0), pa.get(0), host ? info.pMappedData() : 0L};
            ownBuffers.add(new long[]{r[0], r[1]});
            return r;
        }
    }

    private long deviceAddress(long buffer) {
        try (Arena arena = new Arena()) {
            return VK12.vkGetBufferDeviceAddress(dev, VkBufferDeviceAddressInfo.calloc(arena.stack()).sType$Default().buffer(buffer));
        }
    }

    private static void check(int res, String what) {
        if (res != VK10.VK_SUCCESS) throw new IllegalStateException(what + " -> VkResult " + res);
    }

    /** Nach vkDeviceWaitIdle (Shutdown). */
    void destroy() {
        for (long[] d : destroyQueue) KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(dev, d[0], null);
        destroyQueue.clear();
        for (Entry e : entries.values()) {
            if (e.as != 0L) KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(dev, e.as, null);
        }
        entries.clear();
        if (tlas != 0L) KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(dev, tlas, null);
        tlas = 0L;
        for (long[] b : ownBuffers) Vma.vmaDestroyBuffer(vma, b[0], b[1]);
        ownBuffers.clear();
    }
}
