package wueffi.taskmanager.client;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class RenderPhaseProfiler {

    private static final RenderPhaseProfiler INSTANCE = new RenderPhaseProfiler();
    public static RenderPhaseProfiler getInstance() { return INSTANCE; }

    private static final long WINDOW_NS = 20_000_000_000L;

    private record TimedEntry(String phase, long cpuNs, long gpuNs, long cpuCalls, long gpuCalls, long timestamp) {}

    private final List<TimedEntry> entries = new CopyOnWriteArrayList<>();
    private final Map<String, Long> cpuStart = new ConcurrentHashMap<>();

    public void beginCpuPhase(String phase) {
        if (!TaskManagerScreen.isProfilingActive()) return;
        cpuStart.put(phase, System.nanoTime());
    }

    public void endCpuPhase(String phase) {
        Long start = cpuStart.remove(phase);
        if (start == null) return;
        entries.add(new TimedEntry(phase, System.nanoTime() - start, 0L, 1L, 0L, System.nanoTime()));
    }

    public void recordGpuResult(String phase, long nanoseconds) {
        entries.add(new TimedEntry(phase, 0L, nanoseconds, 0L, 1L, System.nanoTime()));
    }

    private void evict() {
        long cutoff = System.nanoTime() - WINDOW_NS;
        entries.removeIf(e -> e.timestamp() < cutoff);
    }

    public Map<String, Long> getCpuNanos() { return aggregate(TimedEntry::cpuNs); }
    public Map<String, Long> getCpuCalls()  { return aggregate(TimedEntry::cpuCalls); }
    public Map<String, Long> getGpuNanos() { return aggregate(TimedEntry::gpuNs); }
    public Map<String, Long> getGpuCalls()  { return aggregate(TimedEntry::gpuCalls); }

    private Map<String, Long> aggregate(java.util.function.ToLongFunction<TimedEntry> field) {
        evict();
        Map<String, Long> result = new LinkedHashMap<>();
        for (TimedEntry e : entries) {
            result.merge(e.phase(), field.applyAsLong(e), Long::sum);
        }
        return result;
    }

    public void reset() {
        entries.clear();
    }
}