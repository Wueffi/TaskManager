package wueffi.taskmanager.client;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ThreadSnapshotCollector {

    public record ThreadStackSnapshot(long threadId, String threadName, StackTraceElement[] stack) {}
    public record Snapshot(long capturedAtEpochMillis, Map<Long, ThreadStackSnapshot> threadsById) {
        static Snapshot empty() {
            return new Snapshot(0L, Map.of());
        }
    }

    private static final ThreadSnapshotCollector INSTANCE = new ThreadSnapshotCollector();
    private static final int MAX_HISTORY = 12;
    private static final int MAX_STACK_DEPTH = 32;

    public static ThreadSnapshotCollector getInstance() {
        return INSTANCE;
    }

    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private final Object historyLock = new Object();
    private final Deque<Snapshot> history = new ArrayDeque<>();
    private volatile Snapshot latestSnapshot = Snapshot.empty();
    private volatile boolean running;
    private Thread collectorThread;

    private ThreadSnapshotCollector() {
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        collectorThread = new Thread(this::runCollector, "TaskManager-Thread-Snapshots");
        collectorThread.setDaemon(true);
        collectorThread.start();
    }

    public synchronized void stop() {
        running = false;
    }

    public Snapshot getLatestSnapshot() {
        return latestSnapshot;
    }

    public List<ThreadStackSnapshot> getRecentThreadSnapshots(long threadId, long sinceEpochMillis, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<ThreadStackSnapshot> result = new ArrayList<>(limit);
        synchronized (historyLock) {
            var iterator = history.descendingIterator();
            while (iterator.hasNext() && result.size() < limit) {
                Snapshot snapshot = iterator.next();
                if (snapshot.capturedAtEpochMillis() < sinceEpochMillis) {
                    break;
                }
                ThreadStackSnapshot threadSnapshot = snapshot.threadsById().get(threadId);
                if (threadSnapshot != null) {
                    result.add(0, threadSnapshot);
                }
            }
        }
        if (result.isEmpty()) {
            ThreadStackSnapshot latest = latestSnapshot.threadsById().get(threadId);
            if (latest != null) {
                return List.of(latest);
            }
        }
        return List.copyOf(result);
    }

    public List<ThreadStackSnapshot> getLatestNamedSnapshots(String... threadNames) {
        if (threadNames == null || threadNames.length == 0) {
            return List.of();
        }
        Map<String, ThreadStackSnapshot> byName = new LinkedHashMap<>();
        Snapshot snapshot = latestSnapshot;
        for (ThreadStackSnapshot threadSnapshot : snapshot.threadsById().values()) {
            for (String threadName : threadNames) {
                if (threadName != null && threadName.equals(threadSnapshot.threadName())) {
                    byName.putIfAbsent(threadName, threadSnapshot);
                }
            }
        }
        return List.copyOf(byName.values());
    }

    private void runCollector() {
        while (running) {
            try {
                int intervalMillis = getTargetIntervalMillis();
                if (intervalMillis >= 0) {
                    collectSnapshot();
                }
                Thread.sleep(intervalMillis < 0 ? 250L : intervalMillis);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private int getTargetIntervalMillis() {
        ProfilerManager profilerManager = ProfilerManager.getInstance();
        boolean flamegraphRunning = FlamegraphProfiler.getInstance().isRunning();
        if (flamegraphRunning) {
            return 5;
        }
        String governorMode = profilerManager.getCollectorGovernorMode();
        if (profilerManager.shouldCollectDetailedMetrics()) {
            return switch (governorMode) {
                case "self-protect" -> 50;
                case "burst" -> 10;
                case "tight" -> 15;
                case "light" -> 40;
                default -> 20;
            };
        }
        if (profilerManager.isCaptureActive()) {
            return switch (governorMode) {
                case "self-protect" -> 100;
                case "burst" -> 20;
                case "tight" -> 30;
                case "light" -> 60;
                default -> 40;
            };
        }
        return -1;
    }

    private void collectSnapshot() {
        long[] threadIds = threadBean.getAllThreadIds();
        ThreadInfo[] infos = threadBean.getThreadInfo(threadIds, MAX_STACK_DEPTH);
        Map<Long, ThreadStackSnapshot> threadsById = new LinkedHashMap<>();
        for (int i = 0; i < threadIds.length; i++) {
            ThreadInfo info = infos == null || i >= infos.length ? null : infos[i];
            if (info == null) {
                continue;
            }
            StackTraceElement[] stack = info.getStackTrace();
            if (stack == null || stack.length == 0) {
                continue;
            }
            threadsById.put(threadIds[i], new ThreadStackSnapshot(threadIds[i], info.getThreadName(), stack));
        }

        Snapshot snapshot = new Snapshot(System.currentTimeMillis(), Map.copyOf(threadsById));
        latestSnapshot = snapshot;
        synchronized (historyLock) {
            history.addLast(snapshot);
            while (history.size() > MAX_HISTORY) {
                history.removeFirst();
            }
        }
    }
}
