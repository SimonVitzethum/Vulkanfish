package simon.vulkanfish.client.gpu;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gemeinsamer CPU-Worker-Pool fuer alles Bauen (Section-Meshing, LOD-Saeulen, LOD-Meshing,
 * Parsen gespeicherter Chunks): alle Kerne bis auf zwei (Render- und Server-Thread),
 * Prioritaets-Warteschlange – kleinere Zahl = frueher. Nahfeld-Sections laufen vor dem
 * Fernfeld, innerhalb einer Klasse das Naechste zuerst.
 */
public final class WorkerPool {
    public static final long PRIO_SECTION = 0L;          // + Abstand^2 in Sections
    public static final long PRIO_LOD = 1L << 40;        // + Abstand in Bloecken

    private static final AtomicLong SEQ = new AtomicLong();
    private static final int THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
    private static final ThreadPoolExecutor POOL;

    static {
        AtomicInteger n = new AtomicInteger();
        POOL = new ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS, new PriorityBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "vulkanfish-worker-" + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
    }

    private WorkerPool() {
    }

    public static int threads() {
        return THREADS;
    }

    public static void submit(long priority, Runnable task) {
        POOL.execute(new Task(priority, SEQ.getAndIncrement(), task));
    }

    public static int queued() {
        return POOL.getQueue().size();
    }

    private record Task(long priority, long seq, Runnable task) implements Runnable, Comparable<Task> {
        @Override
        public void run() {
            task.run();
        }

        @Override
        public int compareTo(Task o) {
            int c = Long.compare(priority, o.priority);
            return c != 0 ? c : Long.compare(seq, o.seq);
        }
    }
}
