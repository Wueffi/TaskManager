package wueffi.taskmanager.client;

import wueffi.taskmanager.client.util.ModTimingSnapshot;

import java.util.*;

public class ModTimingProfiler {

    private static final ModTimingProfiler INSTANCE = new ModTimingProfiler();
    public static ModTimingProfiler getInstance() { return INSTANCE; }

    private static final long WINDOW_NS = 5_000_000_000L;
    private record TimedEntry(String modId, String method, long nanos, long timestamp) {}

    private final List<TimedEntry> entries = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void record(String modId, String methodName, long nanoseconds) {
        entries.add(new TimedEntry(modId, methodName, nanoseconds, System.nanoTime()));
    }

    private void evict() {
        long cutoff = System.nanoTime() - WINDOW_NS;
        entries.removeIf(e -> e.timestamp() < cutoff);
    }

    public Map<String, ModTimingSnapshot> getSnapshot() {
        evict();
        Map<String, Long> nanos = new LinkedHashMap<>();
        Map<String, Long> calls = new LinkedHashMap<>();
        for (TimedEntry e : entries) {
            nanos.merge(e.modId(), e.nanos(), Long::sum);
            calls.merge(e.modId(), 1L, Long::sum);
        }
        Map<String, ModTimingSnapshot> result = new LinkedHashMap<>();
        nanos.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> result.put(e.getKey(),
                        new ModTimingSnapshot(e.getValue(), calls.getOrDefault(e.getKey(), 1L))));
        return result;
    }

    public void reset() {
        entries.clear();
    }
}