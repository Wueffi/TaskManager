package wueffi.taskmanager.client;

import java.util.*;

public class StartupTimingProfiler {

    private static final StartupTimingProfiler INSTANCE = new StartupTimingProfiler();
    public static StartupTimingProfiler getInstance() { return INSTANCE; }

    private final Map<String, Long> firstSeen = new LinkedHashMap<>();
    private final Map<String, Long> lastSeen = new LinkedHashMap<>();
    private final Map<String, Integer> registrationCount = new LinkedHashMap<>();
    private boolean closed = false;

    public void close() {
        closed = true;
    }

    public void recordRegistration(String modId) {
        if (closed) return;
        long now = System.nanoTime();
        firstSeen.putIfAbsent(modId, now);
        lastSeen.put(modId, now);
        registrationCount.merge(modId, 1, Integer::sum);
    }

    public boolean hasData() {
        return !firstSeen.isEmpty();
    }
}