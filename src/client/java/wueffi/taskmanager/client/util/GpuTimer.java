package wueffi.taskmanager.client.util;

import org.lwjgl.opengl.ARBTimerQuery;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import wueffi.taskmanager.client.RenderPhaseProfiler;
import java.util.*;
import java.util.function.Consumer;

/** Render-thread-only, nonblocking timestamp pool. */
public class GpuTimer {
    static final int MAX_INTERVALS = 2048;
    interface Backend {
        boolean supported();
        int create();
        void stamp(int id);
        boolean available(int id);
        long read(int id);
        void delete(int id);
    }
    private static Backend backend = new Backend() {
        public boolean supported() { return GL.getCapabilities().GL_ARB_timer_query || GL.getCapabilities().OpenGL33; }
        public int create() { return GL15.glGenQueries(); }
        public void stamp(int id) { ARBTimerQuery.glQueryCounter(id, ARBTimerQuery.GL_TIMESTAMP); }
        public boolean available(int id) { return GL15.glGetQueryObjecti(id, GL15.GL_QUERY_RESULT_AVAILABLE) != 0; }
        public long read(int id) { return ARBTimerQuery.glGetQueryObjectui64(id, GL15.GL_QUERY_RESULT); }
        public void delete(int id) {
            com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThread();
            GL15.glDeleteQueries(id);
        }
    };
    private static final Deque<Integer> freeQueries = new ArrayDeque<>();
    private static final Set<Integer> allocated = new HashSet<>();
    private static final Deque<ActiveQuery> pending = new ArrayDeque<>();
    private static final Deque<ActiveQuery> activeStack = new ArrayDeque<>();
    private static long frame, session = System.currentTimeMillis(), drops;
    private static int outstanding;

    public record Result(long session, long frame, long inclusiveNanos, long exclusiveNanos, String status) {}
    public interface Scope extends AutoCloseable {
        @Override void close();
        default void abort() { close(); }
    }
    private static final Scope EMPTY = () -> {};
    static final class ActiveQuery {
        final String phase;
        final int startQueryId, endQueryId;
        final List<ActiveQuery> children = new ArrayList<>();
        final long frameId = frame, sessionId = session;
        Consumer<Result> sink;
        long inclusiveNanos = -1L;
        boolean closed;
        ActiveQuery(String phase, int startQueryId, int endQueryId) {
            this.phase = phase; this.startQueryId = startQueryId; this.endQueryId = endQueryId;
        }
    }
    public static boolean isSupported() {
        try { return backend.supported(); } catch (RuntimeException e) { return false; }
    }
    public static long frameId() { return frame; }
    public static long sessionId() { return session; }
    public static long droppedSamples() { return drops; }
    public static void nextFrame() {
        // A renderer exception must not leave an open query attached to the next frame.
        while (!activeStack.isEmpty()) abandon(activeStack.removeLast(), "aborted");
        frame++;
        collectResults();
    }
    public static void begin(String phase) { start(phase, null); }
    public static Scope beginScope(String phase, Consumer<Result> sink) {
        ActiveQuery q = start(phase, sink);
        return q == null ? EMPTY : new Scope() {
            public void close() { GpuTimer.close(q); }
            public void abort() {
                if (q.closed || !activeStack.contains(q)) return;
                while (activeStack.peekLast() != q) abandon(activeStack.removeLast(), "aborted");
                activeStack.removeLast(); abandon(q, "aborted");
            }
        };
    }
    private static ActiveQuery start(String phase, Consumer<Result> sink) {
        String failure = !isSupported() ? "unsupported" : outstanding >= MAX_INTERVALS ? "dropped" : null;
        if (failure != null) {
            if (failure.equals("dropped")) drops++;
            if (sink != null) sink.accept(new Result(session, frame, 0, 0, failure));
            // Legacy callers pair by name; a skipped nested begin must not close its parent.
            return skipped(phase, sink);
        }
        int start = 0, end = 0;
        try {
            start = acquireQuery(); end = acquireQuery();
            backend.stamp(start);
            ActiveQuery q = new ActiveQuery(phase, start, end);
            q.sink = sink;
            // Measured shader intervals must not subtract time from the independent mod-attribution tree.
            for (Iterator<ActiveQuery> it = activeStack.descendingIterator(); it.hasNext();) {
                ActiveQuery parent = it.next();
                if ((parent.sink == null) == (sink == null) && parent.startQueryId != 0) {
                    parent.children.add(q); break;
                }
            }
            activeStack.addLast(q);
            outstanding++;
            return q;
        } catch (RuntimeException e) {
            releaseQuery(start); releaseQuery(end);
            if (sink != null) sink.accept(new Result(session, frame, 0, 0, "unavailable"));
            return skipped(phase, sink);
        }
    }
    private static ActiveQuery skipped(String phase, Consumer<Result> sink) {
        if (sink != null) return null;
        ActiveQuery skipped = new ActiveQuery(phase, 0, 0);
        activeStack.addLast(skipped);
        return skipped;
    }
    public static void end(String phase) {
        ActiveQuery match = null;
        for (Iterator<ActiveQuery> it = activeStack.descendingIterator(); it.hasNext();) {
            ActiveQuery q = it.next();
            if (q.phase.equals(phase)) { match = q; break; }
        }
        if (match != null) close(match);
    }
    private static void close(ActiveQuery q) {
        if (q.closed || !activeStack.contains(q)) return;
        while (activeStack.peekLast() != q) abandon(activeStack.removeLast(), "aborted");
        activeStack.removeLast();
        q.closed = true;
        if (q.startQueryId == 0) return;
        try {
            backend.stamp(q.endQueryId);
            pending.addLast(q);
        } catch (RuntimeException e) { abandon(q, "unavailable"); }
    }
    private static void abandon(ActiveQuery q, String status) {
        q.closed = true;
        q.inclusiveNanos = 0;
        if (q.startQueryId == 0) return;
        releaseQuery(q.startQueryId); releaseQuery(q.endQueryId); outstanding--;
        if (q.sink != null) q.sink.accept(new Result(q.sessionId, q.frameId, 0, 0, status));
    }
    public static void collectResults() {
        // Children are enqueued before parents; never read GL_QUERY_RESULT until both are ready.
        while (!pending.isEmpty()) {
            ActiveQuery q = pending.peekFirst();
            try {
                if (!backend.available(q.startQueryId) || !backend.available(q.endQueryId)) return;
                long start = backend.read(q.startQueryId), end = backend.read(q.endQueryId);
                pending.removeFirst();
                releaseQuery(q.startQueryId); releaseQuery(q.endQueryId); outstanding--;
                q.inclusiveNanos = Math.max(0, end - start);
                if (q.sink != null) {
                    q.sink.accept(new Result(q.sessionId, q.frameId, q.inclusiveNanos, exclusiveNanos(q), end >= start ? "completed" : "unavailable"));
                } else if (end >= start && q.sessionId == session) {
                    RenderPhaseProfiler profiler = RenderPhaseProfiler.getInstance();
                    if (q.phase.equals("frame.total")) profiler.recordGpuFrameResult(q.inclusiveNanos);
                    else profiler.recordGpuResult(q.phase, exclusiveNanos(q));
                }
            } catch (RuntimeException e) {
                if (pending.peekFirst() == q) { pending.removeFirst(); abandon(q, "unavailable"); }
                else throw e;
            }
        }
    }
    public static long readQuery64(int id) { return backend.available(id) ? backend.read(id) : -1; }
    private static int acquireQuery() {
        Integer id = freeQueries.pollFirst();
        if (id != null) return id;
        int created = backend.create(); allocated.add(created); return created;
    }
    private static void releaseQuery(int id) { if (id != 0) freeQueries.addLast(id); }
    static long exclusiveNanos(ActiveQuery q) {
        return Math.max(0L, q.inclusiveNanos - q.children.stream().mapToLong(c -> Math.max(0, c.inclusiveNanos)).sum());
    }
    /** Must run before the GL context is destroyed, on its owning thread. */
    public static void reset() {
        while (!activeStack.isEmpty()) abandon(activeStack.removeLast(), "cancelled");
        while (!pending.isEmpty()) abandon(pending.removeFirst(), "cancelled");
        for (int id : allocated) backend.delete(id);
        allocated.clear(); freeQueries.clear(); outstanding = 0;
        session++;
    }
    static Backend backendForTests(Backend value) { reset(); Backend previous = backend; backend = value; return previous; }
}
