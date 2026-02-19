package wueffi.taskmanager.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RenderPhaseProfiler {

    private static final RenderPhaseProfiler INSTANCE = new RenderPhaseProfiler();

    public static RenderPhaseProfiler getInstance() {
        return INSTANCE;
    }

    private final Map<String, Long> cpuNanos = new LinkedHashMap<>();
    private final Map<String, Long> cpuCalls = new LinkedHashMap<>();

    private final Map<String, Long> gpuNanos = new LinkedHashMap<>();
    private final Map<String, Long> gpuCalls = new LinkedHashMap<>();

    private final Map<String, Long> cpuStart = new ConcurrentHashMap<>();

    public void beginCpuPhase(String phase) {
        cpuStart.put(phase, System.nanoTime());
    }

    public void endCpuPhase(String phase) {
        Long start = cpuStart.remove(phase);
        if (start == null) return;
        long elapsed = System.nanoTime() - start;
        cpuNanos.merge(phase, elapsed, Long::sum);
        cpuCalls.merge(phase, 1L, Long::sum);
    }

    public void recordGpuResult(String phase, long nanoseconds) {
        gpuNanos.merge(phase, nanoseconds, Long::sum);
        gpuCalls.merge(phase, 1L, Long::sum);
    }

    public void reset() {
        cpuNanos.clear();
        cpuCalls.clear();
        gpuNanos.clear();
        gpuCalls.clear();
    }
}
