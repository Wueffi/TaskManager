package wueffi.taskmanager.client;

import wueffi.taskmanager.client.util.ModClassIndex;
import wueffi.taskmanager.client.util.BoundedMaps;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class CpuSamplingProfiler {

    private static final CpuSamplingProfiler INSTANCE = new CpuSamplingProfiler();
    public static CpuSamplingProfiler getInstance() { return INSTANCE; }

    private static final int SAMPLE_INTERVAL_MS = 2;
    private static final int MAX_ATTRIBUTION_SNAPSHOTS = 4;
    private static final int READY_CPU_SAMPLES = 300;
    private static final int READY_RENDER_SAMPLES = 200;
    private static final long THREAD_REFRESH_INTERVAL_MS = 250L;
    private static final int GPU_STALL_FRAME_SCAN_DEPTH = 4;
    private static final int GPU_STALL_MAX_CPU_UTILIZATION_PERCENT = 35;
    private static final int MAX_CLASS_MOD_CACHE_ENTRIES = 8_192;

    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, String> classModCache = BoundedMaps.synchronizedLru(MAX_CLASS_MOD_CACHE_ENTRIES);
    private final Map<Long, Long> lastThreadCpuTimes = new ConcurrentHashMap<>();
    private final AtomicLong lastSampleAtMillis = new AtomicLong(0);
    private final Map<String, Map<String, LongAdder>> threadReasonsByMod = new ConcurrentHashMap<>();
    private final Map<String, Map<String, LongAdder>> frameReasonsByMod = new ConcurrentHashMap<>();
    private final Map<String, Map<String, LongAdder>> renderReasonsByMod = new ConcurrentHashMap<>();
    private final AtomicLong lastThreadRefreshAtMillis = new AtomicLong(0L);
    private volatile long[] cachedThreadIds = new long[0];

    private volatile boolean running = false;
    private Thread samplerThread;

    private static class Counter {
        final LongAdder totalSamples = new LongAdder();
        final LongAdder clientSamples = new LongAdder();
        final LongAdder renderSamples = new LongAdder();
        final LongAdder totalCpuNanos = new LongAdder();
        final LongAdder clientCpuNanos = new LongAdder();
        final LongAdder renderCpuNanos = new LongAdder();
    }

    private record SampleAttribution(String modId, String threadName, String frameReason) {}

    public record Snapshot(long totalSamples, long clientSamples, long renderSamples, long totalCpuNanos, long clientCpuNanos, long renderCpuNanos) {
        public Snapshot(long totalSamples, long clientSamples, long renderSamples) {
            this(totalSamples, clientSamples, renderSamples, 0L, 0L, 0L);
        }
    }
    public record DetailSnapshot(Map<String, Long> topThreads, Map<String, Long> topFrames, int sampledThreadCount) {}
    public record WindowSnapshot(Map<String, Snapshot> samples, Map<String, DetailSnapshot> detailsByMod, long lastSampleAgeMillis) {}

    private CpuSamplingProfiler() {
        if (threadBean.isThreadCpuTimeSupported() && !threadBean.isThreadCpuTimeEnabled()) {
            try {
                threadBean.setThreadCpuTimeEnabled(true);
            } catch (UnsupportedOperationException ignored) {
            }
        }
    }

    public synchronized void start() {
        if (running) return;

        running = true;
        samplerThread = new Thread(this::runSampler, "TaskManager-CPU-Sampler");
        samplerThread.setDaemon(true);
        samplerThread.start();
    }

    public synchronized void stop() {
        running = false;
    }

    public void reset() {
        counters.clear();
        threadReasonsByMod.clear();
        frameReasonsByMod.clear();
        renderReasonsByMod.clear();
        lastThreadCpuTimes.clear();
        lastSampleAtMillis.set(0);
    }

    public WindowSnapshot drainWindow() {
        long ageMillis = getLastSampleAgeMillis();
        Map<String, Snapshot> result = new LinkedHashMap<>();
        counters.forEach((mod, counter) -> result.put(mod, new Snapshot(
                counter.totalSamples.sum(),
                counter.clientSamples.sum(),
                counter.renderSamples.sum(),
                counter.totalCpuNanos.sum(),
                counter.clientCpuNanos.sum(),
                counter.renderCpuNanos.sum()
        )));
        Map<String, DetailSnapshot> details = new LinkedHashMap<>();
        for (String mod : result.keySet()) {
            Map<String, Long> topThreads = snapshotReasonMap(threadReasonsByMod.get(mod));
            Map<String, Long> topFrames = snapshotReasonMap(mergeReasonMaps(frameReasonsByMod.get(mod), renderReasonsByMod.get(mod)));
            int sampledThreadCount = threadReasonsByMod.get(mod) == null ? 0 : threadReasonsByMod.get(mod).size();
            details.put(mod, new DetailSnapshot(topThreads, topFrames, sampledThreadCount));
        }
        reset();
        return new WindowSnapshot(result, details, ageMillis);
    }

    public long getLastSampleAgeMillis() {
        long last = lastSampleAtMillis.get();
        if (last == 0) return Long.MAX_VALUE;
        return Math.max(0, System.currentTimeMillis() - last);
    }

    public boolean hasEnoughCpuSamples(long samples) {
        return samples >= READY_CPU_SAMPLES;
    }

    public boolean hasEnoughRenderSamples(long samples) {
        return samples >= READY_RENDER_SAMPLES;
    }

    private void runSampler() {
        while (running) {
            try {
                if (ProfilerManager.getInstance().isCaptureActive()) {
                    sampleBusyThreads();
                }
                Thread.sleep(SAMPLE_INTERVAL_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private void sampleBusyThreads() {
        if (!threadBean.isThreadCpuTimeSupported() || !threadBean.isThreadCpuTimeEnabled()) {
            return;
        }
        boolean sampledAnyThread = false;
        long sampleStartedAtMillis = System.currentTimeMillis();
        long previousSampleAtMillis = lastSampleAtMillis.get();
        long wallIntervalNanos = Math.max(1L, (previousSampleAtMillis <= 0L ? SAMPLE_INTERVAL_MS : Math.max(1L, sampleStartedAtMillis - previousSampleAtMillis)) * 1_000_000L);
        ThreadSnapshotCollector collector = ThreadSnapshotCollector.getInstance();
        long[] threadIds = getCachedThreadIds(sampleStartedAtMillis);
        for (long threadId : threadIds) {
            long cpuTimeNs = threadBean.getThreadCpuTime(threadId);
            if (cpuTimeNs < 0L) {
                continue;
            }
            Long previousCpuTime = lastThreadCpuTimes.put(threadId, cpuTimeNs);
            long deltaCpuNs = previousCpuTime == null ? 0L : Math.max(0L, cpuTimeNs - previousCpuTime);
            if (deltaCpuNs <= 0L) {
                continue;
            }
            List<ThreadSnapshotCollector.ThreadStackSnapshot> threadSnapshots = collector.getRecentThreadSnapshots(
                    threadId,
                    Math.max(0L, lastSampleAtMillis.get()),
                    MAX_ATTRIBUTION_SNAPSHOTS
            );
            sampleThread(threadId, threadSnapshots, deltaCpuNs, wallIntervalNanos);
            sampledAnyThread = true;
        }
        if (sampledAnyThread) {
            lastSampleAtMillis.set(sampleStartedAtMillis);
        }
    }

    private long[] getCachedThreadIds(long nowMillis) {
        long refreshedAt = lastThreadRefreshAtMillis.get();
        long[] threadIds = cachedThreadIds;
        if (threadIds.length == 0 || refreshedAt == 0L || nowMillis - refreshedAt >= THREAD_REFRESH_INTERVAL_MS) {
            threadIds = threadBean.getAllThreadIds();
            cachedThreadIds = threadIds;
            lastThreadRefreshAtMillis.set(nowMillis);
        }
        return threadIds;
    }

    private void sampleThread(long threadId, List<ThreadSnapshotCollector.ThreadStackSnapshot> threadSnapshots, long cpuBudgetNanos, long wallIntervalNanos) {
        if (threadSnapshots == null || threadSnapshots.isEmpty() || cpuBudgetNanos <= 0L) {
            return;
        }

        ModExecutionContext.ActiveContext activeContext = ModExecutionContext.getActiveContext(threadId);
        if (activeContext != null && activeContext.modId() != null && !activeContext.modId().isBlank()) {
            String threadName = threadSnapshots.getLast().threadName();
            SampleAttribution attribution = new SampleAttribution(activeContext.modId(), threadName, activeContext.reason());
            recordAttribution(attribution, cpuBudgetNanos);
            if (isRenderThread(threadName)) {
                RenderPhaseProfiler.getInstance().recordLikelyOwnerSample(threadId, activeContext.modId(), activeContext.reason());
            }
            return;
        }

        List<SampleAttribution> attributions = new ArrayList<>(threadSnapshots.size());
        for (ThreadSnapshotCollector.ThreadStackSnapshot threadSnapshot : threadSnapshots) {
            StackTraceElement[] stack = threadSnapshot.stack();
            if (stack == null || stack.length == 0) {
                continue;
            }
            attributions.add(attributeStack(stack, threadSnapshot.threadName(), cpuBudgetNanos, wallIntervalNanos));
        }
        if (attributions.isEmpty()) {
            return;
        }

        long[] shares = CollectorMath.splitBudget(cpuBudgetNanos, attributions.size());
        for (int i = 0; i < attributions.size(); i++) {
            SampleAttribution attribution = attributions.get(i);
            recordAttribution(attribution, shares[i]);
            if (isRenderThread(attribution.threadName()) && !isSharedAttributionMod(attribution.modId())) {
                RenderPhaseProfiler.getInstance().recordLikelyOwnerSample(threadId, attribution.modId(), attribution.frameReason());
            }
        }
    }

    private void recordAttribution(SampleAttribution attribution, long cpuBudgetNanos) {
        Counter counter = counters.computeIfAbsent(attribution.modId(), ignored -> new Counter());
        counter.totalSamples.increment();
        counter.totalCpuNanos.add(cpuBudgetNanos);
        boolean render = isRenderThread(attribution.threadName());
        boolean client = isClientThread(attribution.threadName());
        if (render) {
            counter.renderSamples.increment();
            counter.renderCpuNanos.add(cpuBudgetNanos);
        }
        if (client) {
            counter.clientSamples.increment();
            counter.clientCpuNanos.add(cpuBudgetNanos);
        }
        incrementReason(threadReasonsByMod, attribution.modId(), attribution.threadName());
        incrementReason(render ? renderReasonsByMod : frameReasonsByMod, attribution.modId(), attribution.frameReason());
    }

    private SampleAttribution attributeStack(StackTraceElement[] stack, String threadName, long cpuBudgetNanos, long wallIntervalNanos) {
        String gpuStallReason = findGpuStallReason(stack);
        if (gpuStallReason != null && isRenderThread(threadName) && isGpuStallBudget(cpuBudgetNanos, wallIntervalNanos)) {
            return new SampleAttribution("shared/gpu-stall", threadName, gpuStallReason);
        }

        String firstConcrete = null;
        String firstConcreteReason = null;
        String firstFramework = null;
        String firstFrameworkReason = null;
        String firstKnown = null;
        String firstKnownReason = null;

        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            if (className.startsWith("wueffi.taskmanager.")) {
                continue;
            }

            String mod = BoundedMaps.getOrCompute(classModCache, className, this::resolveModForClassName);
            if (mod == null || "unknown".equals(mod)) {
                continue;
            }

            if (firstKnown == null) {
                firstKnown = mod;
                firstKnownReason = formatFrameReason(frame);
            }

            if (isFrameworkMod(mod, className)) {
                if (firstFramework == null) {
                    firstFramework = mod;
                    firstFrameworkReason = formatFrameReason(frame);
                }
                continue;
            }

            if (firstConcrete == null) {
                firstConcrete = mod;
                firstConcreteReason = formatFrameReason(frame);
            }
        }

        if (firstConcrete != null) {
            return new SampleAttribution(firstConcrete, threadName, firstConcreteReason == null ? findFallbackFrame(stack) : firstConcreteReason);
        }
        if (firstFramework != null) {
            return new SampleAttribution(firstFramework, threadName, firstFrameworkReason == null ? findFallbackFrame(stack) : firstFrameworkReason);
        }
        return new SampleAttribution(firstKnown == null ? "minecraft" : firstKnown, threadName, firstKnownReason == null ? findFallbackFrame(stack) : firstKnownReason);
    }

    private boolean isGpuStallBudget(long cpuBudgetNanos, long wallIntervalNanos) {
        if (cpuBudgetNanos <= 0L || wallIntervalNanos <= 0L) {
            return false;
        }
        return cpuBudgetNanos * 100L <= wallIntervalNanos * GPU_STALL_MAX_CPU_UTILIZATION_PERCENT;
    }

    private String findGpuStallReason(StackTraceElement[] stack) {
        if (stack == null || stack.length == 0) {
            return null;
        }
        int inspected = 0;
        int gpuFrames = 0;
        StackTraceElement firstGpuFrame = null;
        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            if (className.startsWith("wueffi.taskmanager.")) {
                continue;
            }
            if (inspected >= GPU_STALL_FRAME_SCAN_DEPTH) {
                break;
            }
            inspected++;
            if (!isGpuDriverFrame(className)) {
                break;
            }
            gpuFrames++;
            if (firstGpuFrame == null) {
                firstGpuFrame = frame;
            }
        }
        if (gpuFrames >= 2 && firstGpuFrame != null) {
            return formatFrameReason(firstGpuFrame);
        }
        return null;
    }

    private boolean isGpuDriverFrame(String className) {
        return className.startsWith("org.lwjgl.opengl.")
                || className.startsWith("org.lwjgl.system.JNI")
                || className.startsWith("com.mojang.blaze3d.systems.")
                || className.startsWith("com.mojang.blaze3d.platform.")
                || className.startsWith("com.mojang.blaze3d.opengl.");
    }

    private String findFallbackFrame(StackTraceElement[] stack) {
        StackTraceElement runtimeFallback = null;
        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            if (!className.startsWith("wueffi.taskmanager.")) {
                if (!isOpaqueRuntimeFrame(className)) {
                    return formatFrameReason(frame);
                }
                if (runtimeFallback == null) {
                    runtimeFallback = frame;
                }
            }
        }
        if (runtimeFallback != null) {
            return formatFrameReason(runtimeFallback);
        }
        return "unknown-frame";
    }

    private boolean isOpaqueRuntimeFrame(String className) {
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("org.lwjgl.")
                || className.startsWith("com.mojang.blaze3d.");
    }

    private String formatFrameReason(StackTraceElement frame) {
        String className = frame.getClassName();
        int lastDot = className.lastIndexOf('.');
        String simpleName = lastDot >= 0 ? className.substring(lastDot + 1) : className;
        return simpleName + "#" + frame.getMethodName();
    }

    private void incrementReason(Map<String, Map<String, LongAdder>> target, String mod, String reason) {
        if (reason == null || reason.isBlank()) {
            return;
        }
        target.computeIfAbsent(mod, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(reason, ignored -> new LongAdder())
                .increment();
    }

    private Map<String, LongAdder> mergeReasonMaps(Map<String, LongAdder> first, Map<String, LongAdder> second) {
        Map<String, LongAdder> merged = new ConcurrentHashMap<>();
        if (first != null) {
            first.forEach((key, value) -> merged.computeIfAbsent(key, ignored -> new LongAdder()).add(value.sum()));
        }
        if (second != null) {
            second.forEach((key, value) -> merged.computeIfAbsent(key, ignored -> new LongAdder()).add(value.sum()));
        }
        return merged;
    }

    private Map<String, Long> snapshotReasonMap(Map<String, LongAdder> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> result = new LinkedHashMap<>();
        source.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
                .limit(5)
                .forEach(entry -> result.put(entry.getKey(), entry.getValue().sum()));
        return result;
    }

    private String resolveModForClassName(String className) {
        String mod = ModClassIndex.getModForClassName(className);
        if (mod != null) {
            return mod;
        }

        if (className.startsWith("net.minecraft.") || className.startsWith("com.mojang.")) {
            return "minecraft";
        }

        if (className.startsWith("java.") || className.startsWith("javax.") || className.startsWith("jdk.") || className.startsWith("sun.") || className.startsWith("org.lwjgl.")) {
            return "shared/jvm";
        }

        if (className.startsWith("net.fabricmc.") || className.startsWith("org.spongepowered.asm.")) {
            return "shared/framework";
        }

        return "unknown";
    }

    private boolean isFrameworkMod(String mod, String className) {
        return "shared/framework".equals(mod)
                || "fabricloader".equals(mod)
                || mod.startsWith("fabric-")
                || mod.startsWith("fabric_api")
                || className.startsWith("net.fabricmc.")
                || className.startsWith("org.spongepowered.asm.");
    }

    private boolean isRenderThread(String threadName) {
        return threadName != null && threadName.toLowerCase().contains("render");
    }

    private boolean isClientThread(String threadName) {
        return "Client thread".equals(threadName);
    }

    private boolean isSharedAttributionMod(String modId) {
        return modId == null || modId.isBlank() || modId.startsWith("shared/");
    }
}
