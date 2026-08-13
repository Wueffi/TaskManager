package wueffi.taskmanager.client;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ThreadLoadProfiler {

    public record ThreadSnapshot(double loadPercent, String state, long blockedCountDelta, long waitedCountDelta, long blockedTimeDeltaMs, long waitedTimeDeltaMs, String lockName, String lockOwnerName, long lockOwnerThreadId) {}
    public record RawThreadSnapshot(long threadId, String threadName, String canonicalThreadName, ThreadSnapshot snapshot) {}
    public record ThreadLoadSample(long capturedAtEpochMillis, Map<String, ThreadSnapshot> threadsByName) {}

    private static final ThreadLoadProfiler INSTANCE = new ThreadLoadProfiler();
    private static final int MAX_HISTORY = 180;

    public static ThreadLoadProfiler getInstance() {
        return INSTANCE;
    }

    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private final Map<Long, Long> lastThreadCpuTimes = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastBlockedCounts = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastWaitedCounts = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastBlockedTimes = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastWaitedTimes = new ConcurrentHashMap<>();
    private final Deque<ThreadLoadSample> history = new ArrayDeque<>();
    private volatile Map<String, ThreadSnapshot> latestThreadSnapshots = Map.of();
    private volatile Map<Long, RawThreadSnapshot> latestRawThreadSnapshots = Map.of();
    private volatile long lastSampleTimeNs;
    private boolean collectionActive;
    private boolean contentionMonitoringOwned;

    private ThreadLoadProfiler() {
        if (threadBean.isThreadCpuTimeSupported() && !threadBean.isThreadCpuTimeEnabled()) {
            try {
                threadBean.setThreadCpuTimeEnabled(true);
            } catch (UnsupportedOperationException ignored) {
            }
        }
    }

    public void setCollectionActive(boolean active, boolean collectContention) {
        if (collectionActive != active) {
            collectionActive = active;
            reset();
        }
        setContentionMonitoringActive(active && collectContention);
    }

    private void setContentionMonitoringActive(boolean active) {
        if (!threadBean.isThreadContentionMonitoringSupported()) {
            return;
        }
        try {
            if (active && !threadBean.isThreadContentionMonitoringEnabled()) {
                threadBean.setThreadContentionMonitoringEnabled(true);
                contentionMonitoringOwned = true;
            } else if (!active && contentionMonitoringOwned) {
                threadBean.setThreadContentionMonitoringEnabled(false);
                contentionMonitoringOwned = false;
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
        }
    }

    public void sample() {
        if (!collectionActive || !threadBean.isThreadCpuTimeSupported() || !threadBean.isThreadCpuTimeEnabled()) {
            latestThreadSnapshots = Map.of();
            return;
        }

        long now = System.nanoTime();
        if (lastSampleTimeNs == 0L) {
            prime(now);
            return;
        }

        long elapsedNs = now - lastSampleTimeNs;
        if (elapsedNs <= 0L) {
            return;
        }

        long[] threadIds = threadBean.getAllThreadIds();
        ThreadInfo[] threadInfos = threadBean.getThreadInfo(threadIds);
        Map<String, ThreadSnapshot> loads = new LinkedHashMap<>();
        Map<Long, RawThreadSnapshot> rawSnapshots = new LinkedHashMap<>();
        Map<Long, Long> nextCpuTimes = new ConcurrentHashMap<>();
        Map<Long, Long> nextBlocked = new ConcurrentHashMap<>();
        Map<Long, Long> nextWaited = new ConcurrentHashMap<>();
        Map<Long, Long> nextBlockedTimes = new ConcurrentHashMap<>();
        Map<Long, Long> nextWaitedTimes = new ConcurrentHashMap<>();
        for (int i = 0; i < threadIds.length; i++) {
            long threadId = threadIds[i];
            long cpuTimeNs = threadBean.getThreadCpuTime(threadId);
            if (cpuTimeNs < 0L) {
                continue;
            }

            ThreadInfo info = threadInfos == null || i >= threadInfos.length ? null : threadInfos[i];
            if (info == null) {
                continue;
            }

            nextCpuTimes.put(threadId, cpuTimeNs);
            nextBlocked.put(threadId, Math.max(0L, info.getBlockedCount()));
            nextWaited.put(threadId, Math.max(0L, info.getWaitedCount()));
            long blockedTime = Math.max(0L, info.getBlockedTime());
            long waitedTime = Math.max(0L, info.getWaitedTime());
            nextBlockedTimes.put(threadId, blockedTime);
            nextWaitedTimes.put(threadId, waitedTime);

            Long previousCpuNs = lastThreadCpuTimes.get(threadId);
            long deltaCpuNs = previousCpuNs == null ? 0L : Math.max(0L, cpuTimeNs - previousCpuNs);
            double loadPercent = deltaCpuNs * 100.0 / elapsedNs;

            long blockedDelta = Math.max(0L, info.getBlockedCount() - lastBlockedCounts.getOrDefault(threadId, info.getBlockedCount()));
            long waitedDelta = Math.max(0L, info.getWaitedCount() - lastWaitedCounts.getOrDefault(threadId, info.getWaitedCount()));
            long blockedTimeDeltaMs = Math.max(0L, blockedTime - lastBlockedTimes.getOrDefault(threadId, blockedTime));
            long waitedTimeDeltaMs = Math.max(0L, waitedTime - lastWaitedTimes.getOrDefault(threadId, waitedTime));
            if (loadPercent <= 0.05 && blockedDelta == 0L && waitedDelta == 0L && blockedTimeDeltaMs == 0L && waitedTimeDeltaMs == 0L) {
                continue;
            }

            String threadName = canonicalThreadName(info.getThreadName());
            ThreadSnapshot snapshot = new ThreadSnapshot(
                    loadPercent,
                    info.getThreadState().name(),
                    blockedDelta,
                    waitedDelta,
                    blockedTimeDeltaMs,
                    waitedTimeDeltaMs,
                    info.getLockName(),
                    info.getLockOwnerName(),
                    info.getLockOwnerId()
            );
            rawSnapshots.put(threadId, new RawThreadSnapshot(threadId, info.getThreadName(), threadName, snapshot));
            loads.merge(threadName, snapshot, this::mergeSnapshots);
        }

        latestThreadSnapshots = loads.entrySet().stream()
                .sorted((a, b) -> Double.compare(scoreSnapshot(b.getValue()), scoreSnapshot(a.getValue())))
                .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), LinkedHashMap::putAll);
        latestRawThreadSnapshots = rawSnapshots.entrySet().stream()
                .sorted((a, b) -> Double.compare(scoreSnapshot(b.getValue().snapshot()), scoreSnapshot(a.getValue().snapshot())))
                .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), LinkedHashMap::putAll);
        history.addLast(new ThreadLoadSample(System.currentTimeMillis(), new LinkedHashMap<>(latestThreadSnapshots)));
        while (history.size() > MAX_HISTORY) {
            history.removeFirst();
        }
        lastThreadCpuTimes.clear();
        lastThreadCpuTimes.putAll(nextCpuTimes);
        lastBlockedCounts.clear();
        lastBlockedCounts.putAll(nextBlocked);
        lastWaitedCounts.clear();
        lastWaitedCounts.putAll(nextWaited);
        lastBlockedTimes.clear();
        lastBlockedTimes.putAll(nextBlockedTimes);
        lastWaitedTimes.clear();
        lastWaitedTimes.putAll(nextWaitedTimes);
        lastSampleTimeNs = now;
    }

    public Map<String, Double> getLatestThreadLoads() {
        Map<String, Double> result = new LinkedHashMap<>();
        latestThreadSnapshots.forEach((name, snapshot) -> result.put(name, snapshot.loadPercent()));
        return result;
    }

    public Map<String, ThreadSnapshot> getLatestThreadSnapshots() {
        return latestThreadSnapshots;
    }

    public Map<Long, RawThreadSnapshot> getLatestRawThreadSnapshots() {
        return latestRawThreadSnapshots;
    }

    public java.util.List<ThreadLoadSample> getHistory() {
        return java.util.List.copyOf(history);
    }

    public void reset() {
        lastThreadCpuTimes.clear();
        lastBlockedCounts.clear();
        lastWaitedCounts.clear();
        lastBlockedTimes.clear();
        lastWaitedTimes.clear();
        latestThreadSnapshots = Map.of();
        latestRawThreadSnapshots = Map.of();
        history.clear();
        lastSampleTimeNs = 0L;
    }

    private ThreadSnapshot mergeSnapshots(ThreadSnapshot left, ThreadSnapshot right) {
        String state = left.loadPercent() >= right.loadPercent() ? left.state() : right.state();
        String lockName = left.lockName() != null ? left.lockName() : right.lockName();
        String lockOwnerName = left.lockOwnerName() != null ? left.lockOwnerName() : right.lockOwnerName();
        long lockOwnerThreadId = left.lockOwnerThreadId() > 0L ? left.lockOwnerThreadId() : right.lockOwnerThreadId();
        return new ThreadSnapshot(
                left.loadPercent() + right.loadPercent(),
                state,
                left.blockedCountDelta() + right.blockedCountDelta(),
                left.waitedCountDelta() + right.waitedCountDelta(),
                left.blockedTimeDeltaMs() + right.blockedTimeDeltaMs(),
                left.waitedTimeDeltaMs() + right.waitedTimeDeltaMs(),
                lockName,
                lockOwnerName,
                lockOwnerThreadId
        );
    }

    private double scoreSnapshot(ThreadSnapshot snapshot) {
        return snapshot.loadPercent() + (snapshot.blockedCountDelta() * 5.0) + (snapshot.waitedCountDelta() * 2.0) + snapshot.blockedTimeDeltaMs() + (snapshot.waitedTimeDeltaMs() * 0.5);
    }

    private void prime(long now) {
        lastThreadCpuTimes.clear();
        lastBlockedCounts.clear();
        lastWaitedCounts.clear();
        for (long threadId : threadBean.getAllThreadIds()) {
            long cpuTimeNs = threadBean.getThreadCpuTime(threadId);
            if (cpuTimeNs >= 0L) {
                lastThreadCpuTimes.put(threadId, cpuTimeNs);
            }
            ThreadInfo info = threadBean.getThreadInfo(threadId);
            if (info != null) {
                lastBlockedCounts.put(threadId, Math.max(0L, info.getBlockedCount()));
                lastWaitedCounts.put(threadId, Math.max(0L, info.getWaitedCount()));
                lastBlockedTimes.put(threadId, Math.max(0L, info.getBlockedTime()));
                lastWaitedTimes.put(threadId, Math.max(0L, info.getWaitedTime()));
            }
        }
        lastSampleTimeNs = now;
    }

    private String canonicalThreadName(String rawName) {
        String lower = rawName.toLowerCase(Locale.ROOT);
        if (lower.startsWith("worker-main-")) {
            return rawName;
        }
        if (lower.contains("render")) {
            return "Render Thread";
        }
        if (lower.contains("server")) {
            return "Server Thread";
        }
        if (lower.contains("g1") || lower.contains("gc")) {
            if (lower.contains("young")) {
                return "G1 Young Gen";
            }
            if (lower.contains("old") || lower.contains("mark") || lower.contains("mixed") || lower.contains("remark")) {
                return "G1 Old Gen";
            }
            return "GC Threads";
        }
        return rawName;
    }
}




