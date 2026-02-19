package wueffi.taskmanager.client;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

public class ModTimingProfiler {

    private static final ModTimingProfiler INSTANCE = new ModTimingProfiler();
    public static ModTimingProfiler getInstance() { return INSTANCE; }

    private final Map<String, Long> modNanos = new HashMap<>();
    private final Map<String, Long> modCalls = new HashMap<>();

    private final Map<String, Long> detailNanos = new HashMap<>();
    private final Map<String, Long> detailCalls = new HashMap<>();

    public void record(String modId, String methodName, long nanoseconds) {
        modNanos.merge(modId, nanoseconds, Long::sum);
        modCalls.merge(modId, 1L, Long::sum);

        String detailKey = modId + "::" + methodName;
        detailNanos.merge(detailKey, nanoseconds, Long::sum);
        detailCalls.merge(detailKey, 1L, Long::sum);
    }

    public void reset() {
        modNanos.clear();
        modCalls.clear();
        detailNanos.clear();
        detailCalls.clear();
    }
}