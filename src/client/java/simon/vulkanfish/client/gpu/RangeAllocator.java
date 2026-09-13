package simon.vulkanfish.client.gpu;

import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * First-Fit-Bereichsallokator (in Elementen) fuer die persistenten GPU-Puffer.
 * Freie Bloecke werden verschmolzen; {@link #top()} ist die obere Belegungs-
 * grenze (= Dispatch-Umfang fuer den Meshlet-Cull).
 */
public final class RangeAllocator {
    private final int capacity;
    private final TreeMap<Integer, Integer> free = new TreeMap<>();
    private int top;
    private long used; // belegte Elemente (ohne Luecken)

    public RangeAllocator(int capacity) {
        this.capacity = capacity;
    }

    /** @return Startindex oder -1, wenn kein Platz. */
    public int alloc(int n) {
        if (n <= 0) return 0;
        Iterator<Map.Entry<Integer, Integer>> it = free.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Integer> e = it.next();
            int len = e.getValue();
            if (len >= n) {
                int start = e.getKey();
                it.remove();
                if (len > n) free.put(start + n, len - n);
                used += n;
                return start;
            }
        }
        if ((long) top + n > capacity) return -1;
        int start = top;
        top += n;
        used += n;
        return start;
    }

    public void free(int start, int n) {
        if (n <= 0) return;
        used -= n;
        Map.Entry<Integer, Integer> prev = free.floorEntry(start);
        if (prev != null && prev.getKey() + prev.getValue() == start) {
            free.remove(prev.getKey());
            start = prev.getKey();
            n += prev.getValue();
        }
        Integer nextLen = free.remove(start + n);
        if (nextLen != null) n += nextLen;
        if (start + n == top) {
            top = start;
        } else {
            free.put(start, n);
        }
    }

    public void reset() {
        free.clear();
        top = 0;
        used = 0;
    }

    /** Belegte Elemente (ohne freie Luecken unterhalb von top). */
    public long used() {
        return used;
    }

    public int top() {
        return top;
    }

    public int capacity() {
        return capacity;
    }
}
