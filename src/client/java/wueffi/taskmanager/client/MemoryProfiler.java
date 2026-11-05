package wueffi.taskmanager.client;

import com.sun.management.ThreadMXBean;
import wueffi.taskmanager.client.util.ModClassIndex;
import wueffi.taskmanager.client.util.ConfigManager;
import wueffi.taskmanager.client.util.BoundedMaps;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class MemoryProfiler {

    private static final MemoryProfiler INSTANCE = new MemoryProfiler();
    public static MemoryProfiler getInstance() { return INSTANCE; }

    private static final ObjectName DIAGNOSTIC_COMMAND_NAME;
    private static final int MAX_SHARED_FAMILIES = 8;
    private static final int MAX_CLASSES_PER_FAMILY = 8;
    private static final long MIN_MOD_SAMPLE_INTERVAL_MS = 10_000L;

    static {
        ObjectName name = null;
        try {
            name = new ObjectName("com.sun.management:type=DiagnosticCommand");
        } catch (Exception ignored) {
        }
        DIAGNOSTIC_COMMAND_NAME = name;
    }

    private volatile Snapshot snapshot = Snapshot.empty();
    private volatile Map<String, Long> modMemoryBytes = Map.of();
    private volatile Map<String, Long> sharedClassFamilies = Map.of();
    private volatile Map<String, Map<String, Long>> sharedFamilyClasses = Map.of();
    private volatile Map<String, Map<String, Long>> topClassesByMod = Map.of();
    private final Map<String, String> classModCache = BoundedMaps.synchronizedLru(8_192);
    private final Map<String, Long> lastGcCountsByName = new ConcurrentHashMap<>();
    private final Map<String, Long> lastGcTimesByName = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastAllocatedBytesByThread = new ConcurrentHashMap<>();
    private final AtomicLong lastJvmSampleAtMillis = new AtomicLong(0);
    private final AtomicLong lastModSampleAtMillis = new AtomicLong(0);
    private final AtomicLong lastAllocationSampleAtMillis = new AtomicLong(0);
    private final AtomicLong lastModSampleDurationMillis = new AtomicLong(0);
    private final ExecutorService modHistogramExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "taskmanager-memory-histogram");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean modSampleInFlight = new AtomicBoolean(false);
    private final ThreadMXBean threadBean;
    private volatile Map<String, Long> modAllocationRateBytesPerSecond = Map.of();
    private volatile Map<Long, Long> threadAllocationRateBytesPerSecond = Map.of();

    private MemoryProfiler() {
        ThreadMXBean detected = null;
        try {
            java.lang.management.ThreadMXBean platformBean = ManagementFactory.getThreadMXBean();
            if (platformBean instanceof ThreadMXBean sunBean) {
                detected = sunBean;
                if (sunBean.isThreadAllocatedMemorySupported() && !sunBean.isThreadAllocatedMemoryEnabled()) {
                    sunBean.setThreadAllocatedMemoryEnabled(true);
                }
            }
        } catch (Throwable ignored) {
        }
        this.threadBean = detected;
    }

    public void sampleJvm() {
        long now = System.currentTimeMillis();
        if (!ProfilerManager.shouldPublishSnapshot(false, now, lastJvmSampleAtMillis.get(), ConfigManager.getMetricsUpdateIntervalMs())) {
            return;
        }
        try {
            MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
            List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
            List<BufferPoolMXBean> bufferPools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
            List<MemoryPoolMXBean> memoryPools = ManagementFactory.getMemoryPoolMXBeans();

            long gcCount = 0;
            long gcTime = 0;
            long youngGcCount = 0;
            long oldGcCount = 0;
            long gcPauseDurationMs = 0;
            String gcType = "none";
            for (GarbageCollectorMXBean gcBean : gcBeans) {
                long count = gcBean.getCollectionCount();
                long time = gcBean.getCollectionTime();
                if (count > 0) gcCount += count;
                if (time > 0) gcTime += time;

                String beanName = gcBean.getName();
                long previousCount = lastGcCountsByName.getOrDefault(beanName, count);
                long previousTime = lastGcTimesByName.getOrDefault(beanName, time);
                long deltaCount = Math.max(0L, count - previousCount);
                long deltaTime = Math.max(0L, time - previousTime);
                lastGcCountsByName.put(beanName, count);
                lastGcTimesByName.put(beanName, time);

                if (deltaCount > 0 || deltaTime > 0) {
                    if (isOldGenCollector(beanName)) {
                        oldGcCount += deltaCount;
                    } else {
                        youngGcCount += deltaCount;
                    }
                    if (deltaTime >= gcPauseDurationMs) {
                        gcPauseDurationMs = deltaTime;
                        gcType = beanName;
                    }
                }
            }

            long directBufferBytes = 0;
            long mappedBufferBytes = 0;
            long directBufferCount = 0;
            long mappedBufferCount = 0;
            long directBufferCapacityBytes = 0;
            long mappedBufferCapacityBytes = 0;
            for (BufferPoolMXBean pool : bufferPools) {
                String name = pool.getName().toLowerCase();
                if (name.contains("direct")) {
                    directBufferBytes += Math.max(0, pool.getMemoryUsed());
                    directBufferCount += Math.max(0, pool.getCount());
                    directBufferCapacityBytes += Math.max(0, pool.getTotalCapacity());
                } else if (name.contains("mapped")) {
                    mappedBufferBytes += Math.max(0, pool.getMemoryUsed());
                    mappedBufferCount += Math.max(0, pool.getCount());
                    mappedBufferCapacityBytes += Math.max(0, pool.getTotalCapacity());
                }
            }

            long metaspaceBytes = 0;
            long codeCacheBytes = 0;
            long classSpaceBytes = 0;
            for (MemoryPoolMXBean pool : memoryPools) {
                long used = Math.max(0, pool.getUsage() == null ? 0 : pool.getUsage().getUsed());
                String name = pool.getName().toLowerCase();
                if (name.contains("metaspace")) {
                    metaspaceBytes += used;
                } else if (name.contains("code") || name.contains("codeheap")) {
                    codeCacheBytes += used;
                } else if (name.contains("class")) {
                    classSpaceBytes += used;
                }
            }

            long heapUsed = bean.getHeapMemoryUsage().getUsed();
            long heapCommitted = bean.getHeapMemoryUsage().getCommitted();

            snapshot = new Snapshot(
                    heapUsed,
                    heapCommitted,
                    bean.getHeapMemoryUsage().getMax(),
                    bean.getNonHeapMemoryUsage().getUsed(),
                    gcCount,
                    gcTime,
                    youngGcCount,
                    oldGcCount,
                    gcPauseDurationMs,
                    gcType,
                    directBufferBytes,
                    mappedBufferBytes,
                    directBufferCount,
                    mappedBufferCount,
                    directBufferCapacityBytes,
                    mappedBufferCapacityBytes,
                    SystemMetricsProfiler.getInstance().getSnapshot().directMemoryMaxBytes(),
                    metaspaceBytes,
                    codeCacheBytes,
                    classSpaceBytes,
                    Math.max(0, heapCommitted - heapUsed)
            );
            lastJvmSampleAtMillis.set(System.currentTimeMillis());
            sampleAllocationRates();
        } catch (Throwable ignored) {
        }
    }

    private volatile long lastModAttemptAtMillis;

    public void requestPerModSample() {
        if (getLastModSampleAgeMillis() < MIN_MOD_SAMPLE_INTERVAL_MS || System.currentTimeMillis() - lastModAttemptAtMillis < MIN_MOD_SAMPLE_INTERVAL_MS) {
            return;
        }
        if (!modSampleInFlight.compareAndSet(false, true)) {
            return;
        }
        lastModAttemptAtMillis = System.currentTimeMillis();
        modHistogramExecutor.execute(() -> {
            try {
                samplePerMod();
            } finally {
                modSampleInFlight.set(false);
            }
        });
    }

    private void samplePerMod() {
        if (DIAGNOSTIC_COMMAND_NAME == null) {
            return;
        }

        long startedAtMillis = System.currentTimeMillis();
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            Object result = server.invoke(
                    DIAGNOSTIC_COMMAND_NAME,
                    "gcClassHistogram",
                    // Include unreachable objects rather than requesting a full GC for profiling.
                    new Object[]{new String[]{"-all"}},
                    new String[]{String[].class.getName()}
            );

            if (!(result instanceof String histogram)) {
                return;
            }

            Map<String, Long> bytesByMod = new LinkedHashMap<>();
            Map<String, Long> familyTotals = new LinkedHashMap<>();
            Map<String, Map<String, Long>> familyClasses = new LinkedHashMap<>();
            Map<String, Map<String, Long>> classesByMod = new LinkedHashMap<>();
            String[] lines = histogram.split("\\R");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || !Character.isDigit(trimmed.charAt(0))) {
                    continue;
                }

                String[] parts = trimmed.split("\\s+", 4);
                if (parts.length < 4 || !parts[0].endsWith(":")) {
                    continue;
                }

                long bytes;
                try {
                    bytes = Long.parseLong(parts[2]);
                } catch (NumberFormatException ignored) {
                    continue;
                }

                String className = normalizeHistogramClassName(parts[3]);
                String mod = classModCache.computeIfAbsent(className, this::resolveHistogramMod);
                bytesByMod.merge(mod, bytes, Long::sum);
                classesByMod.computeIfAbsent(mod, ignored -> new LinkedHashMap<>()).merge(className, bytes, Long::sum);

                if ("shared/jvm".equals(mod) || "shared/framework".equals(mod)) {
                    String family = classFamily(className);
                    familyTotals.merge(family, bytes, Long::sum);
                    familyClasses.computeIfAbsent(family, ignored -> new LinkedHashMap<>()).merge(className, bytes, Long::sum);
                }
            }

            modMemoryBytes = bytesByMod.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                    .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), LinkedHashMap::putAll);

            sharedClassFamilies = familyTotals.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                    .limit(MAX_SHARED_FAMILIES)
                    .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), LinkedHashMap::putAll);

            LinkedHashMap<String, Map<String, Long>> trimmedFamilyClasses = new LinkedHashMap<>();
            for (String family : sharedClassFamilies.keySet()) {
                Map<String, Long> topClasses = familyClasses.getOrDefault(family, Map.of()).entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                        .limit(MAX_CLASSES_PER_FAMILY)
                        .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), LinkedHashMap::putAll);
                trimmedFamilyClasses.put(family, topClasses);
            }
            sharedFamilyClasses = trimmedFamilyClasses;

            LinkedHashMap<String, Map<String, Long>> trimmedClassesByMod = new LinkedHashMap<>();
            for (String mod : modMemoryBytes.keySet()) {
                Map<String, Long> topClasses = classesByMod.getOrDefault(mod, Map.of()).entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                        .limit(MAX_CLASSES_PER_FAMILY)
                        .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), LinkedHashMap::putAll);
                trimmedClassesByMod.put(mod, topClasses);
            }
            topClassesByMod = trimmedClassesByMod;

            lastModSampleAtMillis.set(System.currentTimeMillis());
            lastModSampleDurationMillis.set(Math.max(0L, System.currentTimeMillis() - startedAtMillis));
        } catch (Throwable ignored) {
        }
    }

    public Snapshot getDetailedSnapshot() {
        return snapshot;
    }

    public Map<String, Long> getModMemoryBytes() {
        return modMemoryBytes;
    }

    public Map<String, Long> getSharedClassFamilies() {
        return sharedClassFamilies;
    }

    public Map<String, Map<String, Long>> getSharedFamilyClasses() {
        return sharedFamilyClasses;
    }

    public Map<String, Map<String, Long>> getTopClassesByMod() {
        return topClassesByMod;
    }

    public Map<String, Long> getModAllocationRateBytesPerSecond() {
        return modAllocationRateBytesPerSecond;
    }

    public Map<Long, Long> getThreadAllocationRateBytesPerSecond() {
        return threadAllocationRateBytesPerSecond;
    }

    public long getLastModSampleDurationMillis() {
        return lastModSampleDurationMillis.get();
    }

    public long getLastModSampleAgeMillis() {
        long last = lastModSampleAtMillis.get();
        if (last == 0) return Long.MAX_VALUE;
        return Math.max(0, System.currentTimeMillis() - last);
    }

    public void reset() {
        snapshot = Snapshot.empty();
        modMemoryBytes = Map.of();
        sharedClassFamilies = Map.of();
        sharedFamilyClasses = Map.of();
        topClassesByMod = Map.of();
        modAllocationRateBytesPerSecond = Map.of();
        threadAllocationRateBytesPerSecond = Map.of();
        lastGcCountsByName.clear();
        lastGcTimesByName.clear();
        lastAllocatedBytesByThread.clear();
        lastJvmSampleAtMillis.set(0);
        lastModSampleAtMillis.set(0);
        lastAllocationSampleAtMillis.set(0);
        lastModSampleDurationMillis.set(0);
    }

    private void sampleAllocationRates() {
        ThreadMXBean bean = threadBean;
        if (bean == null || !bean.isThreadAllocatedMemorySupported() || !bean.isThreadAllocatedMemoryEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        long previous = lastAllocationSampleAtMillis.getAndSet(now);
        ThreadSnapshotCollector collector = ThreadSnapshotCollector.getInstance();
        ThreadSnapshotCollector.Snapshot latestSnapshot = collector.getLatestSnapshot();
        if (previous <= 0L) {
            primeAllocatedBytes(bean, latestSnapshot.threadsById().keySet());
            return;
        }
        long elapsedMillis = Math.max(1L, now - previous);
        Map<String, Long> bytesByMod = new LinkedHashMap<>();
        Map<Long, Long> bytesByThread = new LinkedHashMap<>();
        Map<Long, Long> nextAllocatedBytes = new ConcurrentHashMap<>();
        for (long threadId : latestSnapshot.threadsById().keySet()) {
            long allocatedBytes = bean.getThreadAllocatedBytes(threadId);
            if (allocatedBytes < 0L) {
                continue;
            }
            nextAllocatedBytes.put(threadId, allocatedBytes);
            long previousBytes = lastAllocatedBytesByThread.getOrDefault(threadId, allocatedBytes);
            long delta = Math.max(0L, allocatedBytes - previousBytes);
            if (delta <= 0L) {
                continue;
            }
            long bytesPerSecond = Math.round(delta * 1000.0 / elapsedMillis);
            bytesByThread.put(threadId, bytesPerSecond);
            distributeAllocationDelta(bytesByMod, collector.getRecentThreadSnapshots(threadId, previous, 4), bytesPerSecond);
        }
        lastAllocatedBytesByThread.clear();
        lastAllocatedBytesByThread.putAll(nextAllocatedBytes);
        modAllocationRateBytesPerSecond = bytesByMod.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), LinkedHashMap::putAll);
        threadAllocationRateBytesPerSecond = bytesByThread.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue(Comparator.reverseOrder()))
                .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), LinkedHashMap::putAll);
    }

    private void distributeAllocationDelta(Map<String, Long> bytesByMod, List<ThreadSnapshotCollector.ThreadStackSnapshot> threadSnapshots, long bytesPerSecond) {
        if (threadSnapshots == null || threadSnapshots.isEmpty() || bytesPerSecond <= 0L) {
            return;
        }
        long[] shares = CollectorMath.splitBudget(bytesPerSecond, threadSnapshots.size());
        for (int i = 0; i < threadSnapshots.size(); i++) {
            ThreadSnapshotCollector.ThreadStackSnapshot threadSnapshot = threadSnapshots.get(i);
            String mod = resolveAllocatingMod(threadSnapshot.threadName(), threadSnapshot.stack());
            bytesByMod.merge(mod, shares[i], Long::sum);
        }
    }

    private void primeAllocatedBytes(ThreadMXBean bean, Iterable<Long> threadIds) {
        lastAllocatedBytesByThread.clear();
        for (Long threadId : threadIds) {
            if (threadId == null) {
                continue;
            }
            long allocatedBytes = bean.getThreadAllocatedBytes(threadId);
            if (allocatedBytes >= 0L) {
                lastAllocatedBytesByThread.put(threadId, allocatedBytes);
            }
        }
    }

    private String resolveAllocatingMod(String threadName, StackTraceElement[] stack) {
        if (stack != null) {
            for (StackTraceElement element : stack) {
                String mod = ModClassIndex.getModForClassName(element.getClassName());
                if (mod != null) {
                    if ("fabricloader".equals(mod) || mod.startsWith("fabric-") || mod.startsWith("fabric_api")) {
                        return "shared/framework";
                    }
                    return mod;
                }
                String className = element.getClassName();
                if (className.startsWith("net.minecraft.") || className.startsWith("com.mojang.")) {
                    return "minecraft";
                }
                if (className.startsWith("java.") || className.startsWith("javax.") || className.startsWith("jdk.") || className.startsWith("sun.") || className.startsWith("org.lwjgl.")) {
                    continue;
                }
            }
        }
        String normalizedThreadName = threadName == null ? "" : threadName.toLowerCase();
        if (normalizedThreadName.contains("render")) {
            return "shared/render";
        }
        if (normalizedThreadName.contains("server")) {
            return "minecraft";
        }
        return "shared/jvm";
    }

    private boolean isOldGenCollector(String name) {
        String lower = name.toLowerCase();
        return lower.contains("old") || lower.contains("mark") || lower.contains("mixed") || lower.contains("full") || lower.contains("tenured");
    }

    private String normalizeHistogramClassName(String rawClassName) {
        String className = rawClassName.split("\\s+", 2)[0];
        if (className.startsWith("class ")) {
            className = className.substring(6);
        }
        if (className.startsWith("[L") && className.endsWith(";")) {
            return className.substring(2, className.length() - 1).replace('/', '.');
        }
        return className.replace('/', '.');
    }

    private String classFamily(String className) {
        if (className.startsWith("[")) {
            return "primitive arrays";
        }
        if (className.startsWith("java.util.")) {
            String[] parts = className.split("\\.");
            return parts.length >= 3 ? "java.util." + parts[2] : "java.util";
        }
        if (className.startsWith("java.lang.")) {
            String[] parts = className.split("\\.");
            return parts.length >= 3 ? "java.lang." + parts[2] : "java.lang";
        }
        if (className.startsWith("java.")) {
            int idx = className.indexOf('.', 5);
            return idx > 0 ? className.substring(0, idx) : className;
        }
        if (className.startsWith("javax.")) {
            int idx = className.indexOf('.', 6);
            return idx > 0 ? className.substring(0, idx) : className;
        }
        if (className.startsWith("jdk.")) {
            int idx = className.indexOf('.', 4);
            return idx > 0 ? className.substring(0, idx) : className;
        }
        if (className.startsWith("sun.")) {
            int idx = className.indexOf('.', 4);
            return idx > 0 ? className.substring(0, idx) : className;
        }
        if (className.startsWith("net.fabricmc.")) {
            return "net.fabricmc";
        }
        if (className.startsWith("org.spongepowered.")) {
            return "org.spongepowered";
        }
        if (className.startsWith("org.lwjgl.")) {
            return "org.lwjgl";
        }
        return className;
    }

    private String resolveHistogramMod(String className) {
        String mod = ModClassIndex.getModForClassName(className);
        if (mod != null) {
            if ("fabricloader".equals(mod) || mod.startsWith("fabric-") || mod.startsWith("fabric_api")) {
                return "shared/framework";
            }
            return mod;
        }

        String sanitized = className
                .replaceAll("\\$\\$Lambda.*", "")
                .replaceAll("\\$\\d+$", "")
                .replaceAll("\\$Subclass\\d+", "")
                .replaceAll("\\$MixinProxy.*", "");
        if (!sanitized.equals(className)) {
            mod = ModClassIndex.getModForClassName(sanitized);
            if (mod != null) {
                return mod;
            }
        }

        if (sanitized.startsWith("net.minecraft.") || sanitized.startsWith("com.mojang.")) {
            return "minecraft";
        }

        if (sanitized.startsWith("net.fabricmc.") || sanitized.startsWith("org.spongepowered.asm.")) {
            return "shared/framework";
        }

        if (sanitized.startsWith("java.") || sanitized.startsWith("javax.") || sanitized.startsWith("jdk.") || sanitized.startsWith("sun.") || sanitized.startsWith("org.lwjgl.")) {
            return "shared/jvm";
        }

        if (sanitized.startsWith("[")) {
            return "shared/jvm";
        }

        return "unknown";
    }

    public record Snapshot(
            long heapUsedBytes,
            long heapCommittedBytes,
            long heapMaxBytes,
            long nonHeapUsedBytes,
            long gcCount,
            long gcTimeMillis,
            long youngGcCount,
            long oldGcCount,
            long gcPauseDurationMs,
            String gcType,
            long directBufferBytes,
            long mappedBufferBytes,
            long directBufferCount,
            long mappedBufferCount,
            long directBufferCapacityBytes,
            long mappedBufferCapacityBytes,
            long directMemoryMaxBytes,
            long metaspaceBytes,
            long codeCacheBytes,
            long classSpaceBytes,
            long gcHeadroomBytes
    ) {
        static Snapshot empty() {
            return new Snapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, "none", 0, 0, 0, 0, 0, 0, -1, 0, 0, 0, 0);
        }
    }
}
