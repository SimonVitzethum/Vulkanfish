package simon.vulkanfish.client.lod;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import net.minecraft.SharedConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistenter Spalten-Cache: Einmal NBT-parsen, danach nie wieder. Grobe Fernknoten brauchen
 * pro Chunk nur dessen Zusammenfassung (Hoehenlaeufe, Material, Biom) – trotzdem parste bisher
 * jede Session jedes Bobby-Chunk-Tag vollstaendig neu (Paletten, Sections, MBs) um daraus ~2 KiB
 * zu extrahieren. Der Cache legt die gebauten Saeulen (Runs aller Stufen) pro Region ab und
 * liefert sie ohne NBT, ohne Future, ohne Worker-Parse.
 *
 * <p>Format je Region {@code r.&lt;x&gt;.&lt;z&gt;.lodsum} unter {@code vulkanfish-lod/&lt;hash(BobbyDir)&gt;/}:
 * Kopf (Magic, Version, DataVersion, Bobby-mtime/size als Anker) + 1024 Slots (key, crc, off,
 * len, minLevel) + Datensaetze ([minLevel, minY, height, source, Stufen-Mask, je Stufe colStart
 * + runs], CRC32 ueber alles). Anker-Ungleichheit (Bobby schreibt bei Chunk-Unload),
 * CRC-Fehler, Versionswechsel oder fehlende Datei = Miss -> heutiger Parse-Pfad (nie
 * schlechter als ohne Cache, nur Mikrosekunden aelter). Transiente Plattenfehler heilen von
 * selbst (naechster Treffer schreibt neu).
 *
 * <p>Nur Bobby-Quellen (Mehrspieler ohne Seed: die einzige persistente Quelle dort).
 * Spielstand/Generator/Live brauchen ihn nicht (RAM/Seed/deterministisch). Threads: get auf
 * dem Render-Thread (Datei im Page-Cache: Mikrosekunden; kalt bremst das Spawn-Budget, kein
 * Stillstand), put auf Workern. Alles unter einem groben Lock in konsistenter Reihenfolge.
 */
final class LodSummaryCache {
    private static final Logger LOG = LoggerFactory.getLogger("vulkanfish");
    private static final int MAGIC = 0x564C4F44; // "VLOD"
    private static final int VERSION = 1;
    private static final int SLOTS = 1024;
    private static final int SLOT_BYTES = 28; // key8 + crc4 + off4 + len4 + minLevel4
    private static final int HEADER_BYTES = 32 + SLOTS * SLOT_BYTES;
    private static final int MAX_REGIONS_OPEN = 64;
    private static final long REVALIDATE_MS = 60_000;

    private final Path root;
    private final Map<String, Path> bobbyDirs; // summaryId -> Bobby-Seed-Dir
    private final LinkedHashMap<RegionKey, Region> regions = new LinkedHashMap<>(16, 0.75f, true);
    private boolean logged;

    LodSummaryCache(Map<String, Path> bobbyDirs, Path gameDir) {
        this.bobbyDirs = Map.copyOf(bobbyDirs);
        this.root = gameDir.resolve("vulkanfish-lod");
    }

    static String summaryId(Path bobbyDir) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(bobbyDir.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 8; i++) b.append(String.format("%02x", digest[i]));
            return b.toString();
        } catch (Throwable t) {
            return Integer.toHexString(bobbyDir.toString().hashCode());
        }
    }

    private record RegionKey(String id, int rx, int rz) {
    }

    private static final class Region {
        RandomAccessFile raf;
        Path path;
        Path bobbyMca;
        long[] slotKey = new long[SLOTS];
        int[] slotCrc = new int[SLOTS];
        int[] slotOff = new int[SLOTS];
        int[] slotLen = new int[SLOTS];
        int[] slotMinLevel = new int[SLOTS];
        long anchorMtime;
        long anchorSize;
        long lastValidateMs;
        boolean valid;
    }

    synchronized void close() {
        for (Region r : regions.values()) {
            try {
                r.raf.close();
            } catch (Throwable ignored) {
            }
        }
        regions.clear();
    }

    /** Gebaute Saeule aus dem Cache (frisch gegen Bobby-mtime/size + DataVersion) oder null. */
    synchronized LodColumn get(int cx, int cz) {
        Region r = open(cx >> 5, cz >> 5, false);
        if (r == null || !r.valid) return null;
        int slot = (cx & 31) + (cz & 31) * 32;
        if (r.slotLen[slot] <= 0 || r.slotKey[slot] != LodManager.chunkKey(cx, cz)) return null;
        try {
            byte[] data = new byte[r.slotLen[slot]];
            synchronized (r) {
                r.raf.seek(r.slotOff[slot]);
                r.raf.readFully(data);
            }
            CRC32 crc = new CRC32();
            crc.update(data);
            if ((int) crc.getValue() != r.slotCrc[slot]) return null;
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            int minLevel = b.getInt();
            int minY = b.getInt();
            int height = b.getInt();
            int source = b.getInt();
            int dataVersion = b.getInt();
            if (dataVersion != SharedConstants.getCurrentVersion().dataVersion().version()) return null;
            if (source < 1 || source > 4 || minLevel < 0 || minLevel >= LodColumn.LEVELS) return null;
            int mask = b.get() & 0xFF;
            int[][] colStart = new int[LodColumn.LEVELS][];
            long[][] runs = new long[LodColumn.LEVELS][];
            for (int l = 0; l < LodColumn.LEVELS; l++) {
                if ((mask & (1 << l)) == 0) continue;
                int cn = b.getInt();
                int[] cs = new int[cn];
                for (int i = 0; i < cn; i++) cs[i] = b.getInt();
                int rn = b.getInt();
                long[] rs = new long[rn];
                for (int i = 0; i < rn; i++) rs[i] = b.getLong();
                colStart[l] = cs;
                runs[l] = rs;
            }
            return new LodColumn(cx, cz, source, minLevel, minY, height, colStart, runs);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Gebaute Bobby-Saeule ablegen (Worker-Thread). Feinere Staende ueberschreiben nie groebere. */
    synchronized void put(int cx, int cz, LodColumn col, int dataVersion) {
        if (col == null) return;
        Region r = open(cx >> 5, cz >> 5, true);
        if (r == null || !r.valid) return;
        int slot = (cx & 31) + (cz & 31) * 32;
        if (r.slotLen[slot] > 0 && r.slotKey[slot] == LodManager.chunkKey(cx, cz) && r.slotMinLevel[slot] <= col.minLevel) {
            return; // feinerer Stand bleibt (kein Datenverlust beim Rauszoomen)
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeInt(col.minLevel);
            out.writeInt(col.minY);
            out.writeInt(col.height);
            out.writeInt(col.source);
            out.writeInt(dataVersion);
            int mask = 0;
            for (int l = 0; l < LodColumn.LEVELS; l++) if (col.colStart[l] != null) mask |= 1 << l;
            out.writeByte(mask);
            for (int l = 0; l < LodColumn.LEVELS; l++) {
                if (col.colStart[l] == null) continue;
                out.writeInt(col.colStart[l].length);
                for (int v : col.colStart[l]) out.writeInt(v);
                long[] rs = col.runs[l] != null ? col.runs[l] : new long[0];
                out.writeInt(rs.length);
                for (long v : rs) out.writeLong(v);
            }
            out.flush();
            byte[] data = bos.toByteArray();
            CRC32 crc = new CRC32();
            crc.update(data);
            synchronized (r) {
                long off = r.raf.length();
                r.raf.seek(off);
                r.raf.write(data);
                r.slotKey[slot] = LodManager.chunkKey(cx, cz);
                r.slotCrc[slot] = (int) crc.getValue();
                r.slotOff[slot] = (int) off;
                r.slotLen[slot] = data.length;
                r.slotMinLevel[slot] = col.minLevel;
                writeSlot(r, slot);
            }
        } catch (Throwable t) {
            if (!logged) {
                logged = true;
                LOG.info("[vulkanfish] LOD-Summary-Cache schreibt nicht ({})", t.toString());
            }
        }
    }

    private Region open(int rx, int rz, boolean create) {
        // Bobby-Dir waehlen: erste mit vorhandener .mca (exakter Seed zuerst, wie BobbySource)
        Path bobbyDir = null;
        String id = null;
        for (var e : bobbyDirs.entrySet()) {
            Path mca = e.getValue().resolve("r." + rx + "." + rz + ".mca");
            if (Files.isRegularFile(mca)) {
                bobbyDir = e.getValue();
                id = e.getKey();
                break;
            }
        }
        if (bobbyDir == null) {
            if (!create || bobbyDirs.isEmpty()) return null;
            var e = bobbyDirs.entrySet().iterator().next();
            bobbyDir = e.getValue();
            id = e.getKey();
        }
        final Path dir = bobbyDir;
        final String sid = id;
        RegionKey key = new RegionKey(sid, rx, rz);
        Region r = regions.get(key);
        long now = System.currentTimeMillis();
        if (r != null && now - r.lastValidateMs < REVALIDATE_MS) return r.valid ? r : null;
        try {
            Path mca = dir.resolve("r." + rx + "." + rz + ".mca");
            boolean mcaExists = Files.isRegularFile(mca);
            long mtime = mcaExists ? Files.getLastModifiedTime(mca).toMillis() : 0L;
            long size = mcaExists ? Files.size(mca) : 0L;
            Path file = root.resolve(sid).resolve("r." + rx + "." + rz + ".lodsum");
            if (r == null) {
                r = new Region();
                r.bobbyMca = mca;
            }
            boolean anchorChanged = r.raf == null || r.anchorMtime != mtime || r.anchorSize != size;
            if (anchorChanged && r.raf != null) {
                try {
                    r.raf.close();
                } catch (Throwable ignored) {
                }
                r.raf = null;
            }
            if (r.raf == null) {
                if (!Files.isRegularFile(file)) {
                    if (!create || !mcaExists) {
                        regions.remove(key);
                        return null;
                    }
                    Files.createDirectories(file.getParent());
                }
                r.raf = new RandomAccessFile(file.toFile(), "rw");
                r.path = file;
                if (!readHeader(r, mtime, size)) {
                    // Neu oder Ankerwechsel: frisch aufsetzen (stumpfe Saetze sterben per Miss).
                    // Ohne create: Zustand merken (60-s-Backoff statt Stat-Sturm), kein Entfernen.
                    if (create) {
                        try {
                            r.raf.setLength(0);
                        } catch (Throwable ignored) {
                        }
                        clearSlots(r);
                        writeHeader(r, mtime, size);
                    } else {
                        try {
                            r.raf.close();
                        } catch (Throwable ignored) {
                        }
                        r.raf = null;
                        r.valid = false;
                        r.lastValidateMs = now;
                        regions.put(key, r);
                        return null;
                    }
                }
                r.anchorMtime = mtime;
                r.anchorSize = size;
                regions.put(key, r);
                while (regions.size() > MAX_REGIONS_OPEN) {
                    var it = regions.entrySet().iterator();
                    var eldest = it.next();
                    try {
                        eldest.getValue().raf.close();
                    } catch (Throwable ignored) {
                    }
                    it.remove();
                }
            }
            r.lastValidateMs = now;
            r.valid = true;
            return r;
        } catch (Throwable t) {
            // IO-Fehler: Backoff statt Sturm (Zustand merken, kein Entfernen)
            if (r != null) {
                try {
                    if (r.raf != null) r.raf.close();
                } catch (Throwable ignored) {
                }
                r.raf = null;
                r.valid = false;
                r.lastValidateMs = now;
                regions.put(key, r);
            }
            return null;
        }
    }

    private boolean readHeader(Region r, long mtime, long size) throws IOException {
        if (r.raf.length() < HEADER_BYTES) return false;
        byte[] head = new byte[HEADER_BYTES];
        r.raf.seek(0);
        r.raf.readFully(head);
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN);
        if (b.getInt() != MAGIC || b.getInt() != VERSION) return false;
        b.getInt(); // DataVersion (je Satz geprueft)
        if (b.getLong() != mtime || b.getLong() != size) return false;
        b.getInt(); // reserviert
        clearSlots(r);
        for (int i = 0; i < SLOTS; i++) {
            r.slotKey[i] = b.getLong();
            r.slotCrc[i] = b.getInt();
            r.slotOff[i] = b.getInt();
            r.slotLen[i] = b.getInt();
            r.slotMinLevel[i] = b.getInt();
            if (r.slotLen[i] < 0 || r.slotOff[i] < 0) return false;
        }
        return true;
    }

    private void clearSlots(Region r) {
        java.util.Arrays.fill(r.slotKey, 0L);
        java.util.Arrays.fill(r.slotCrc, 0);
        java.util.Arrays.fill(r.slotOff, 0);
        java.util.Arrays.fill(r.slotLen, 0);
        java.util.Arrays.fill(r.slotMinLevel, Integer.MAX_VALUE);
    }

    private void writeHeader(Region r, long mtime, long size) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
        b.putInt(MAGIC);
        b.putInt(VERSION);
        b.putInt(SharedConstants.getCurrentVersion().dataVersion().version());
        b.putLong(mtime);
        b.putLong(size);
        b.putInt(0);
        for (int i = 0; i < SLOTS; i++) {
            b.putLong(r.slotKey[i]);
            b.putInt(r.slotCrc[i]);
            b.putInt(r.slotOff[i]);
            b.putInt(r.slotLen[i]);
            b.putInt(r.slotMinLevel[i]);
        }
        r.raf.seek(0);
        r.raf.write(b.array());
    }

    private void writeSlot(Region r, int slot) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(SLOT_BYTES).order(ByteOrder.BIG_ENDIAN);
        b.putLong(r.slotKey[slot]);
        b.putInt(r.slotCrc[slot]);
        b.putInt(r.slotOff[slot]);
        b.putInt(r.slotLen[slot]);
        b.putInt(r.slotMinLevel[slot]);
        r.raf.seek(32 + (long) slot * SLOT_BYTES);
        r.raf.write(b.array());
    }
}
