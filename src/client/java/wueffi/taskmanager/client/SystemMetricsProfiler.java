package wueffi.taskmanager.client;

import com.sun.management.OperatingSystemMXBean;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import wueffi.taskmanager.client.util.ConfigManager;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;

public class SystemMetricsProfiler {

    public record ThreadDrilldown(
            long threadId,
            String threadName,
            String canonicalThreadName,
            double cpuLoadPercent,
            long allocationRateBytesPerSecond,
            String state,
            long blockedTimeDeltaMs,
            long waitedTimeDeltaMs,
            String ownerMod,
            String confidence,
            String reasonFrame,
            List<String> topFrames,
            String threadRole,
            String roleSource,
            List<String> ownerCandidates
    ) {}

    public record ContentionSample(
            long waiterThreadId,
            String waiterThreadName,
            String waiterMod,
            String waiterConfidence,
            String waiterRole,
            long ownerThreadId,
            String ownerThreadName,
            String ownerMod,
            String ownerConfidence,
            String ownerRole,
            String lockName,
            long blockedTimeDeltaMs,
            long waitedTimeDeltaMs,
            List<String> waiterCandidates,
            List<String> ownerCandidates,
            String confidence
    ) {}

    public record Snapshot(
            String gpuVendor,
            String gpuRenderer,
            long vramUsedBytes,
            long vramTotalBytes,
            long vramPagingBytes,
            boolean vramPagingActive,
            long committedVirtualMemoryBytes,
            long directMemoryUsedBytes,
            long directMemoryMaxBytes,
            boolean windowsBridgeActive,
            String counterSource,
            String sensorSource,
            String sensorErrorCode,
            String cpuTemperatureUnavailableReason,
            double cpuCoreLoadPercent,
            double gpuCoreLoadPercent,
            double gpuTemperatureC,
            double gpuHotSpotTemperatureC,
            double cpuTemperatureC,
            double cpuLoadChangePerSecond,
            double gpuLoadChangePerSecond,
            double cpuTemperatureChangePerSecond,
            double gpuTemperatureChangePerSecond,
            double mouseInputLatencyMs,
            long bytesReceivedPerSecond,
            long bytesSentPerSecond,
            long diskReadBytesPerSecond,
            long diskWriteBytesPerSecond,
            Map<String, Double> threadLoadPercentByName,
            Map<String, ThreadLoadProfiler.ThreadSnapshot> threadDetailsByName,
            String schedulingConflictSummary,
            String cpuParallelismFlag,
            String cpuSensorStatus,
            int activeHighLoadThreads,
            int estimatedPhysicalCores,
            String mainLogicSummary,
            String backgroundSummary,
            double totalThreadLoadPercent,
            String parallelismEfficiency,
            long serverThreadBlockedMs,
            long serverThreadWaitMs,
            int activeWorkers,
            int idleWorkers,
            double activeToIdleWorkerRatio,
            long offHeapAllocationRateBytesPerSecond,
            double packetProcessingLatencyMs,
            String networkBufferSaturation,
            double bytesPerEntity,
            String currentBiome,
            String lightUpdateQueue,
            int maxEntitiesInHotChunk,
            int chunksGenerating,
            int chunksMeshing,
            int chunksUploading,
            int lightsUpdatePending,
            long chunkMeshesRebuilt,
            long chunkMeshesUploaded,
            long textureUploadRate,
            double playerSpeedBlocksPerSecond,
            int chunksEnteredLastSecond,
            double distanceTravelledBlocks,
            Map<String, String> metricProvenance,
            String telemetryHelperStatus,
            long telemetrySampleAgeMillis,
            String gpuTemperatureProvider,
            String gpuHotSpotProvider,
            double profilerCpuLoadPercent,
            long worldScanCostMillis,
            long memoryHistogramCostMillis,
            long telemetryHelperCostMillis,
            String collectorGovernorMode,
            String gpuCoverageSummary,
            List<ThreadDrilldown> threadDrilldown,
            List<ContentionSample> contentionSamples
    ) {
        public static Snapshot empty() {
            return new Snapshot(
                    "",
                    "",
                    -1L,
                    -1L,
                    0L,
                    false,
                    -1L,
                    0L,
                    -1L,
                    false,
                    "Unavailable",
                    "Unavailable",
                    "No bridge data",
                    "No provider exposed a readable CPU package temperature",
                    -1.0,
                    -1.0,
                    -1.0,
                    -1.0,
                    -1.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    -1L,
                    -1L,
                    -1L,
                    -1L,
                    Map.of(),
                    Map.of(),
                    "No scheduling conflict detected",
                    "Parallelism unknown",
                    "CPU sensor unavailable",
                    0,
                    Math.max(1, Runtime.getRuntime().availableProcessors() / 2),
                    "Main Logic: unknown",
                    "Background: unknown",
                    0.0,
                    "Parallelism unknown",
                    0L,
                    0L,
                    0,
                    0,
                    0.0,
                    0L,
                    -1.0,
                    "Unknown",
                    -1.0,
                    "unknown",
                    "unavailable",
                    0,
                    -1,
                    -1,
                    -1,
                    -1,
                    -1L,
                    -1L,
                    -1L,
                    -1.0,
                    -1,
                    0.0,
                    Map.of(),
                    "stopped",
                    Long.MAX_VALUE,
                    "Unavailable",
                    "Unavailable",
                    0.0,
                    0L,
                    0L,
                    0L,
                    "idle",
                    "No tagged phases yet",
                    List.of(),
                    List.of()
            );
        }
    }

    private static final SystemMetricsProfiler INSTANCE = new SystemMetricsProfiler();
    public static SystemMetricsProfiler getInstance() { return INSTANCE; }

    private record ThreadRoleAnalysis(
            String label,
            String source,
            boolean mainLogic,
            boolean workerPool,
            boolean ioPool,
            boolean chunkGeneration,
            boolean chunkMeshing,
            boolean chunkUpload
    ) {
        boolean countsAsWorker() {
            return workerPool || ioPool || chunkGeneration || chunkMeshing || chunkUpload;
        }
    }

    private record ThreadObservation(
            ThreadLoadProfiler.RawThreadSnapshot raw,
            ThreadSnapshotCollector.ThreadStackSnapshot stackSnapshot,
            AttributionInsights.ThreadAttribution attribution,
            ThreadRoleAnalysis role,
            long allocationRateBytesPerSecond
    ) {}

    private static final int GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX = 0x9048;
    private static final int GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX = 0x9049;
    private static final int HISTORY_SIZE = 180;
    private static final long SENSOR_RETAIN_MILLIS = 10_000L;

    private final long[] networkInHistory = new long[HISTORY_SIZE];
    private final long[] networkOutHistory = new long[HISTORY_SIZE];
    private final long[] diskReadHistory = new long[HISTORY_SIZE];
    private final long[] diskWriteHistory = new long[HISTORY_SIZE];
    private final double[] cpuLoadHistory = new double[HISTORY_SIZE];
    private final double[] gpuLoadHistory = new double[HISTORY_SIZE];
    private final double[] cpuTemperatureHistory = new double[HISTORY_SIZE];
    private final double[] gpuTemperatureHistory = new double[HISTORY_SIZE];
    private final double[] vramUsedHistory = new double[HISTORY_SIZE];
    private final double[] memoryUsedHistory = new double[HISTORY_SIZE];
    private final double[] memoryCommittedHistory = new double[HISTORY_SIZE];
    private final double[] entityCountHistory = new double[HISTORY_SIZE];
    private final double[] loadedChunkHistory = new double[HISTORY_SIZE];
    private final double[] renderedChunkHistory = new double[HISTORY_SIZE];
    private final WindowsTelemetryBridge windowsBridge = new WindowsTelemetryBridge();
    private final Sensors sensors = new Sensors();
    private int historyIndex;
    private int historyCount;

    private volatile Snapshot snapshot = Snapshot.empty();
    private long lastSampleAtMillis;
    private int lastSampleIntervalMillis = ConfigManager.getMetricsUpdateIntervalMs();
    private long lastDirectMemoryUsedBytes = -1L;
    private final TrendTracker cpuLoadTrendTracker = new TrendTracker(1_000L, 100.0);
    private final TrendTracker gpuLoadTrendTracker = new TrendTracker(1_000L, 100.0);
    private final TrendTracker cpuTemperatureTrendTracker = new TrendTracker(2_000L, 30.0);
    private final TrendTracker gpuTemperatureTrendTracker = new TrendTracker(2_000L, 30.0);
    private final TemperatureRetention gpuTemperatureRetention = new TemperatureRetention();
    private final TemperatureRetention gpuHotSpotTemperatureRetention = new TemperatureRetention();
    private final TemperatureRetention cpuTemperatureRetention = new TemperatureRetention();
    private double lastPlayerX;
    private double lastPlayerY;
    private double lastPlayerZ;
    private boolean lastPlayerPosValid;
    private long lastPlayerSampleAtMillis;
    private int lastPlayerChunkX;
    private int lastPlayerChunkZ;
    private boolean lastPlayerChunkValid;
    private double distanceTravelledBlocks;
    private final Deque<Long> chunkEntryTimes = new ArrayDeque<>();
    private String cachedGpuVendor = "";
    private String cachedGpuRenderer = "";

    public void sample(MemoryProfiler.Snapshot memorySnapshot, ProfilerManager.EntityCounts entityCounts, ProfilerManager.ChunkCounts chunkCounts) {
        long now = System.currentTimeMillis();
        String collectorGovernorMode = ProfilerManager.getInstance().getCollectorGovernorMode();
        int sampleIntervalMillis = switch (collectorGovernorMode) {
            case "self-protect" -> Math.max(300, ConfigManager.getMetricsUpdateIntervalMs() * 2);
            case "burst" -> 50;
            case "tight" -> Math.max(100, ConfigManager.getMetricsUpdateIntervalMs());
            case "light" -> Math.max(200, ConfigManager.getMetricsUpdateIntervalMs());
            default -> ProfilerManager.getInstance().shouldCollectFrameMetrics() ? 50 : ConfigManager.getMetricsUpdateIntervalMs();
        };
        if (now - lastSampleAtMillis < sampleIntervalMillis) {
            return;
        }
        long previousSampleAtMillis = lastSampleAtMillis;
        long elapsedMillis = previousSampleAtMillis <= 0L ? sampleIntervalMillis : Math.max(1L, now - previousSampleAtMillis);
        lastSampleAtMillis = now;
        lastSampleIntervalMillis = sampleIntervalMillis;

        String vendor = resolveGpuVendor();
        String renderer = resolveGpuRenderer();

        long vramUsedBytes = -1;
        long vramTotalBytes = -1;
        try {
            if (GL.getCapabilities().GL_NVX_gpu_memory_info) {
                long totalKb = Integer.toUnsignedLong(GL11.glGetInteger(GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX));
                long availableKb = Integer.toUnsignedLong(GL11.glGetInteger(GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX));
                if (totalKb > 0) {
                    vramTotalBytes = totalKb * 1024L;
                    vramUsedBytes = Math.max(0L, (totalKb - availableKb) * 1024L);
                }
            }
        } catch (Throwable ignored) {
        }

        long committedVirtualMemoryBytes = lookupCommittedVirtualMemoryBytes();
        long vramPagingBytes = vramTotalBytes > 0 && vramUsedBytes > vramTotalBytes ? vramUsedBytes - vramTotalBytes : 0L;
        boolean vramPagingActive = vramPagingBytes > 0L;
        long directMemoryUsedBytes = memorySnapshot.directBufferBytes() + memorySnapshot.mappedBufferBytes();
        long directMemoryMaxBytes = lookupDirectMemoryMaxBytes();

        Sensors.Sample nativeSample = sensors.sample(renderer, vendor);
        windowsBridge.requestRefreshIfNeeded(nativeSample.active());
        WindowsTelemetryBridge.Sample bridgeSample = windowsBridge.getLatest();
        WindowsTelemetryBridge.Sample mergedSample = mergeTelemetrySamples(bridgeSample, nativeSample);
        WindowsTelemetryBridge.Health telemetryHealth = windowsBridge.getHealth();
        TemperatureReading gpuTemperatureReading = gpuTemperatureRetention.resolve(
                mergedSample.gpuTemperatureC(),
                mergedSample.gpuTemperatureProvider(),
                now,
                SENSOR_RETAIN_MILLIS
        );
        TemperatureReading gpuHotSpotTemperatureReading = gpuHotSpotTemperatureRetention.resolve(
                mergedSample.gpuHotSpotTemperatureC(),
                mergedSample.gpuHotSpotTemperatureProvider(),
                now,
                SENSOR_RETAIN_MILLIS
        );
        TemperatureReading cpuTemperatureReading = cpuTemperatureRetention.resolve(
                mergedSample.cpuTemperatureC(),
                mergedSample.cpuTemperatureProvider(),
                now,
                SENSOR_RETAIN_MILLIS
        );
        Map<String, ThreadLoadProfiler.ThreadSnapshot> threadDetails = new LinkedHashMap<>(ThreadLoadProfiler.getInstance().getLatestThreadSnapshots());
        ThreadSnapshotCollector.Snapshot latestStacks = ThreadSnapshotCollector.getInstance().getLatestSnapshot();
        List<ThreadObservation> threadObservations = buildThreadObservations(latestStacks);
        List<ThreadDrilldown> threadDrilldown = buildThreadDrilldown(threadObservations);
        List<ContentionSample> contentionSamples = buildContentionSamples(threadObservations);
        Map<String, Double> threadLoads = new LinkedHashMap<>();
        threadDetails.forEach((name, details) -> threadLoads.put(name, details.loadPercent()));
        double totalThreadLoad = threadObservations.stream()
                .map(ThreadObservation::raw)
                .map(ThreadLoadProfiler.RawThreadSnapshot::snapshot)
                .mapToDouble(ThreadLoadProfiler.ThreadSnapshot::loadPercent)
                .sum();
        double profilerCpuLoad = sumProfilerThreadLoad();
        long offHeapAllocationRate = 0L;
        if (lastDirectMemoryUsedBytes >= 0L) {
            offHeapAllocationRate = Math.max(0L, Math.round((directMemoryUsedBytes - lastDirectMemoryUsedBytes) * 1000.0 / elapsedMillis));
        }
        lastDirectMemoryUsedBytes = directMemoryUsedBytes;
        ThreadObservation serverThread = threadObservations.stream()
                .filter(observation -> "Server Thread".equals(observation.raw().canonicalThreadName()))
                .findFirst()
                .orElse(null);
        int activeWorkers = countWorkers(threadObservations, true);
        int idleWorkers = countWorkers(threadObservations, false);
        double workerRatio = idleWorkers > 0 ? activeWorkers / (double) idleWorkers : activeWorkers;
        int totalEntities = entityCounts.totalEntities();
        double bytesPerEntity = totalEntities > 0 && mergedSample.bytesReceivedPerSecond() >= 0 ? mergedSample.bytesReceivedPerSecond() / (double) totalEntities : -1.0;
        NetworkPacketProfiler.Snapshot latestPacketSnapshot = NetworkPacketProfiler.getInstance().getLatestSnapshot();
        long packetVolume = latestPacketSnapshot.inboundPackets() + latestPacketSnapshot.outboundPackets();
        double packetProcessingLatencyMs = packetVolume > 0 ? Math.min(250.0, (packetVolume / 20.0) + (TickProfiler.getInstance().getAverageClientTickNs() / 1_000_000.0 * 0.25)) : -1.0;
        String networkBufferSaturation = packetVolume > 400 ? "High packet burst pressure" : packetVolume > 120 ? "Moderate packet burst pressure" : "Low packet burst pressure";
        String biome = sampleBiome();
        String lightUpdateQueue = sampleLightQueue();
        int lightsUpdatePending = parseLeadingInt(lightUpdateQueue);
        int chunksGenerating = countRoleMatches(threadObservations, ThreadRoleAnalysis::chunkGeneration);
        int chunksMeshing = countRoleMatches(threadObservations, ThreadRoleAnalysis::chunkMeshing);
        int chunksUploading = countRoleMatches(threadObservations, ThreadRoleAnalysis::chunkUpload);
        Map<String, RenderPhaseProfiler.PhaseSnapshot> renderPhases = RenderPhaseProfiler.getInstance().getSnapshot();
        long chunkMeshesRebuilt = sumPhaseCalls(renderPhases, "chunk", "mesh", "build", "rebuild");
        long chunkMeshesUploaded = sumPhaseCalls(renderPhases, "upload");
        long textureUploadRate = sumPhaseCalls(renderPhases, "texture", "upload");
        String gpuCoverageSummary = buildGpuCoverageSummary(renderPhases);
        PlayerMotionSnapshot motion = samplePlayerMotion(now);
        List<ProfilerManager.HotChunkSnapshot> hotChunks = ProfilerManager.getInstance().getLatestHotChunks();
        int maxEntitiesInHotChunk = hotChunks.isEmpty() ? 0 : hotChunks.getFirst().entityCount();
        double cpuLoadChangePerSecond = cpuLoadTrendTracker.update(mergedSample.cpuCoreLoadPercent(), now);
        double gpuLoadChangePerSecond = gpuLoadTrendTracker.update(mergedSample.gpuCoreLoadPercent(), now);
        double cpuTemperatureChangePerSecond = cpuTemperatureTrendTracker.update(cpuTemperatureReading.value(), now);
        double gpuTemperatureChangePerSecond = gpuTemperatureTrendTracker.update(gpuTemperatureReading.value(), now);

        snapshot = new Snapshot(
                vendor,
                renderer,
                vramUsedBytes,
                vramTotalBytes,
                vramPagingBytes,
                vramPagingActive,
                committedVirtualMemoryBytes,
                directMemoryUsedBytes,
                directMemoryMaxBytes,
                mergedSample.bridgeActive(),
                mergedSample.counterSource(),
                mergedSample.sensorSource(),
                mergedSample.sensorErrorCode(),
                buildCpuTemperatureUnavailableReason(cpuTemperatureReading, mergedSample),
                mergedSample.cpuCoreLoadPercent(),
                mergedSample.gpuCoreLoadPercent(),
                gpuTemperatureReading.value(),
                gpuHotSpotTemperatureReading.value(),
                cpuTemperatureReading.value(),
                cpuLoadChangePerSecond,
                gpuLoadChangePerSecond,
                cpuTemperatureChangePerSecond,
                gpuTemperatureChangePerSecond,
                InputLatencyProfiler.getInstance().getLastPresentedLatencyMs(),
                mergedSample.bytesReceivedPerSecond(),
                mergedSample.bytesSentPerSecond(),
                mergedSample.diskReadBytesPerSecond(),
                mergedSample.diskWriteBytesPerSecond(),
                threadLoads,
                threadDetails,
                buildSchedulingConflictSummary(threadObservations),
                buildParallelismFlag(threadObservations),
                buildCpuSensorStatus(mergedSample.sensorSource()),
                countHighLoadThreads(threadObservations),
                estimatePhysicalCores(),
                buildMainLogicSummary(threadObservations),
                buildBackgroundSummary(threadObservations),
                totalThreadLoad,
                buildParallelismEfficiency(totalThreadLoad, activeWorkers, idleWorkers),
                serverThread == null ? 0L : serverThread.raw().snapshot().blockedTimeDeltaMs(),
                serverThread == null ? 0L : serverThread.raw().snapshot().waitedTimeDeltaMs(),
                activeWorkers,
                idleWorkers,
                workerRatio,
                offHeapAllocationRate,
                packetProcessingLatencyMs,
                networkBufferSaturation,
                bytesPerEntity,
                biome,
                lightUpdateQueue,
                maxEntitiesInHotChunk,
                chunksGenerating,
                chunksMeshing,
                chunksUploading,
                lightsUpdatePending,
                chunkMeshesRebuilt,
                chunkMeshesUploaded,
                textureUploadRate,
                motion.speedBlocksPerSecond(),
                motion.chunksEnteredLastSecond(),
                motion.distanceTravelledBlocks(),
                buildMetricProvenance(),
                telemetryHealth.helperStatus(),
                telemetryHealth.latestSampleAgeMillis(),
                gpuTemperatureReading.provider(),
                gpuHotSpotTemperatureReading.provider(),
                profilerCpuLoad,
                ProfilerManager.getInstance().getLastWorldScanDurationMillis(),
                MemoryProfiler.getInstance().getLastModSampleDurationMillis(),
                Math.max(telemetryHealth.latestSampleDurationMillis(), mergedSample.sampleDurationMillis()),
                collectorGovernorMode,
                gpuCoverageSummary,
                threadDrilldown,
                contentionSamples
        );

        pushHistory(networkInHistory, Math.max(0L, snapshot.bytesReceivedPerSecond()));
        pushHistory(networkOutHistory, Math.max(0L, snapshot.bytesSentPerSecond()));
        pushHistory(diskReadHistory, Math.max(0L, snapshot.diskReadBytesPerSecond()));
        pushHistory(diskWriteHistory, Math.max(0L, snapshot.diskWriteBytesPerSecond()));
        pushHistory(cpuLoadHistory, Math.max(0.0, snapshot.cpuCoreLoadPercent()));
        pushHistory(gpuLoadHistory, Math.max(0.0, snapshot.gpuCoreLoadPercent()));
        pushHistory(cpuTemperatureHistory, cpuTemperatureReading.value());
        pushHistory(gpuTemperatureHistory, gpuTemperatureReading.value());
        pushHistory(vramUsedHistory, snapshot.vramUsedBytes() >= 0L ? Math.max(0.0, snapshot.vramUsedBytes() / (1024.0 * 1024.0)) : -1.0);
        pushHistory(memoryUsedHistory, Math.max(0.0, memorySnapshot.heapUsedBytes() / (1024.0 * 1024.0)));
        pushHistory(memoryCommittedHistory, Math.max(0.0, memorySnapshot.heapCommittedBytes() / (1024.0 * 1024.0)));
        pushHistory(entityCountHistory, Math.max(0.0, entityCounts.totalEntities()));
        pushHistory(loadedChunkHistory, Math.max(0.0, chunkCounts.loadedChunks()));
        pushHistory(renderedChunkHistory, Math.max(0.0, chunkCounts.renderedChunks()));
        advanceHistory();
    }

    public Snapshot getSnapshot() {
        return snapshot;
    }

    private String resolveGpuVendor() {
        if (!cachedGpuVendor.isBlank()) {
            return cachedGpuVendor;
        }
        cachedGpuVendor = stringOrEmpty(GL11.glGetString(GL11.GL_VENDOR));
        return cachedGpuVendor;
    }

    private String resolveGpuRenderer() {
        if (!cachedGpuRenderer.isBlank()) {
            return cachedGpuRenderer;
        }
        cachedGpuRenderer = stringOrEmpty(GL11.glGetString(GL11.GL_RENDERER));
        return cachedGpuRenderer;
    }

    public long[] getNetworkInHistory() { return networkInHistory; }
    public long[] getNetworkOutHistory() { return networkOutHistory; }
    public long[] getDiskReadHistory() { return diskReadHistory; }
    public long[] getDiskWriteHistory() { return diskWriteHistory; }
    public int getHistoryIndex() { return historyIndex; }
    public int getHistoryCount() { return historyCount; }
    public long[] getOrderedNetworkInHistory() { return orderedHistory(networkInHistory); }
    public long[] getOrderedNetworkOutHistory() { return orderedHistory(networkOutHistory); }
    public long[] getOrderedDiskReadHistory() { return orderedHistory(diskReadHistory); }
    public long[] getOrderedDiskWriteHistory() { return orderedHistory(diskWriteHistory); }
    public double[] getOrderedCpuLoadHistory() { return orderedHistory(cpuLoadHistory); }
    public double[] getOrderedGpuLoadHistory() { return orderedHistory(gpuLoadHistory); }
    public double[] getOrderedCpuTemperatureHistory() { return orderedHistory(cpuTemperatureHistory); }
    public double[] getOrderedGpuTemperatureHistory() { return orderedHistory(gpuTemperatureHistory); }
    public double[] getOrderedVramUsedHistory() { return orderedHistory(vramUsedHistory); }
    public double[] getOrderedMemoryUsedHistory() { return orderedHistory(memoryUsedHistory); }
    public double[] getOrderedMemoryCommittedHistory() { return orderedHistory(memoryCommittedHistory); }
    public double[] getOrderedEntityCountHistory() { return orderedHistory(entityCountHistory); }
    public double[] getOrderedLoadedChunkHistory() { return orderedHistory(loadedChunkHistory); }
    public double[] getOrderedRenderedChunkHistory() { return orderedHistory(renderedChunkHistory); }
    public double getHistorySpanSeconds() { return historyCount <= 1 ? 0.0 : (historyCount - 1) * (lastSampleIntervalMillis / 1000.0); }

    private void pushHistory(long[] history, long value) {
        history[historyIndex] = value;
    }

    private void pushHistory(double[] history, double value) {
        history[historyIndex] = value;
    }

    private static final class TrendTracker {
        private final long windowMillis;
        private final double clampMagnitude;
        private long baselineAtMillis;
        private double baselineValue = Double.NaN;
        private double lastRate;

        private TrendTracker(long windowMillis, double clampMagnitude) {
            this.windowMillis = windowMillis;
            this.clampMagnitude = clampMagnitude;
        }

        private double update(double currentValue, long now) {
            if (!Double.isFinite(currentValue) || currentValue < 0.0) {
                return 0.0;
            }
            if (!Double.isFinite(baselineValue) || baselineAtMillis <= 0L) {
                baselineValue = currentValue;
                baselineAtMillis = now;
                lastRate = 0.0;
                return 0.0;
            }
            long elapsedMillis = Math.max(1L, now - baselineAtMillis);
            if (elapsedMillis < windowMillis) {
                return lastRate;
            }
            double rawRate = (currentValue - baselineValue) * 1000.0 / elapsedMillis;
            baselineValue = currentValue;
            baselineAtMillis = now;
            if (!Double.isFinite(rawRate)) {
                lastRate = 0.0;
                return 0.0;
            }
            double smoothedRate = (lastRate * 0.6) + (rawRate * 0.4);
            lastRate = Math.max(-clampMagnitude, Math.min(clampMagnitude, smoothedRate));
            if (Math.abs(lastRate) < 0.05) {
                lastRate = 0.0;
            }
            return lastRate;
        }
    }

    private record TemperatureReading(double value, String provider) {}

    private static final class TemperatureRetention {
        private double lastValue = -1.0;
        private long lastCapturedAtMillis;
        private String lastProvider = "Unavailable";

        private synchronized TemperatureReading resolve(double currentValue, String currentProvider, long now, long retainMillis) {
            if (Double.isFinite(currentValue) && currentValue >= 0.0) {
                lastValue = currentValue;
                lastCapturedAtMillis = now;
                lastProvider = currentProvider == null || currentProvider.isBlank() ? "Unavailable" : currentProvider;
                return new TemperatureReading(currentValue, lastProvider);
            }
            if (lastCapturedAtMillis > 0L && now - lastCapturedAtMillis <= retainMillis && Double.isFinite(lastValue) && lastValue >= 0.0) {
                return new TemperatureReading(lastValue, lastProvider);
            }
            return new TemperatureReading(-1.0, "Unavailable");
        }
    }

    private void advanceHistory() {
        historyIndex = (historyIndex + 1) % HISTORY_SIZE;
        if (historyCount < HISTORY_SIZE) {
            historyCount++;
        }
    }

    private long[] orderedHistory(long[] history) {
        long[] ordered = new long[historyCount];
        for (int i = 0; i < historyCount; i++) {
            int sourceIndex = (historyIndex - historyCount + i + HISTORY_SIZE) % HISTORY_SIZE;
            ordered[i] = history[sourceIndex];
        }
        return ordered;
    }

    private double[] orderedHistory(double[] history) {
        double[] ordered = new double[historyCount];
        for (int i = 0; i < historyCount; i++) {
            int sourceIndex = (historyIndex - historyCount + i + HISTORY_SIZE) % HISTORY_SIZE;
            ordered[i] = history[sourceIndex];
        }
        return ordered;
    }

    private long lookupCommittedVirtualMemoryBytes() {
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof OperatingSystemMXBean sunBean) {
                return Math.max(0L, sunBean.getCommittedVirtualMemorySize());
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }

    private long lookupDirectMemoryMaxBytes() {
        try {
            Class<?> vmClass = Class.forName("jdk.internal.misc.VM");
            Method method = vmClass.getDeclaredMethod("maxDirectMemory");
            method.setAccessible(true);
            Object value = method.invoke(null);
            if (value instanceof Number number) {
                return number.longValue();
            }
        } catch (Throwable ignored) {
        }

        try {
            Class<?> vmClass = Class.forName("sun.misc.VM");
            Method method = vmClass.getDeclaredMethod("maxDirectMemory");
            method.setAccessible(true);
            Object value = method.invoke(null);
            if (value instanceof Number number) {
                return number.longValue();
            }
        } catch (Throwable ignored) {
        }

        String maxDirectMemorySize = System.getProperty("sun.nio.MaxDirectMemorySize");
        if (maxDirectMemorySize != null && !maxDirectMemorySize.isBlank()) {
            try {
                String trimmed = maxDirectMemorySize.trim().toUpperCase(Locale.ROOT);
                long multiplier = 1L;
                if (trimmed.endsWith("K")) {
                    multiplier = 1024L;
                    trimmed = trimmed.substring(0, trimmed.length() - 1);
                } else if (trimmed.endsWith("M")) {
                    multiplier = 1024L * 1024L;
                    trimmed = trimmed.substring(0, trimmed.length() - 1);
                } else if (trimmed.endsWith("G")) {
                    multiplier = 1024L * 1024L * 1024L;
                    trimmed = trimmed.substring(0, trimmed.length() - 1);
                }
                return Long.parseLong(trimmed) * multiplier;
            } catch (NumberFormatException ignored) {
            }
        }

        long runtimeMaxMemory = Runtime.getRuntime().maxMemory();
        if (runtimeMaxMemory > 0) {
            return runtimeMaxMemory;
        }

        return -1;
    }



    private WindowsTelemetryBridge.Sample mergeTelemetrySamples(WindowsTelemetryBridge.Sample bridgeSample, Sensors.Sample nativeSample) {
        if (bridgeSample == null) {
            bridgeSample = WindowsTelemetryBridge.Sample.empty();
        }
        if (bridgeSample.capturedAtEpochMillis() > 0L && System.currentTimeMillis() - bridgeSample.capturedAtEpochMillis() > 3_000L) {
            bridgeSample = WindowsTelemetryBridge.Sample.empty();
        }
        if (nativeSample == null) {
            nativeSample = Sensors.Sample.empty();
        }
        return new WindowsTelemetryBridge.Sample(
                bridgeSample.capturedAtEpochMillis(),
                bridgeSample.bridgeActive() || nativeSample.active(),
                chooseTelemetryText(nativeSample.counterSource(), bridgeSample.counterSource(), "Unavailable"),
                chooseTelemetryText(nativeSample.sensorSource(), bridgeSample.sensorSource(), "Unavailable"),
                mergeTelemetryErrors(nativeSample.sensorErrorCode(), bridgeSample.sensorErrorCode()),
                chooseTelemetryProvider(nativeSample.cpuTemperatureC(), nativeSample.cpuTemperatureProvider(), bridgeSample.cpuTemperatureC(), bridgeSample.cpuTemperatureProvider()),
                chooseTelemetryProvider(nativeSample.gpuTemperatureC(), nativeSample.gpuTemperatureProvider(), bridgeSample.gpuTemperatureC(), bridgeSample.gpuTemperatureProvider()),
                chooseTelemetryProvider(nativeSample.gpuHotSpotTemperatureC(), nativeSample.gpuHotSpotTemperatureProvider(), bridgeSample.gpuHotSpotTemperatureC(), bridgeSample.gpuHotSpotTemperatureProvider()),
                chooseTelemetryDouble(nativeSample.cpuCoreLoadPercent(), bridgeSample.cpuCoreLoadPercent()),
                chooseTelemetryDouble(nativeSample.gpuCoreLoadPercent(), bridgeSample.gpuCoreLoadPercent()),
                chooseTelemetryDouble(nativeSample.gpuTemperatureC(), bridgeSample.gpuTemperatureC()),
                chooseTelemetryDouble(nativeSample.gpuHotSpotTemperatureC(), bridgeSample.gpuHotSpotTemperatureC()),
                chooseTelemetryDouble(nativeSample.cpuTemperatureC(), bridgeSample.cpuTemperatureC()),
                bridgeSample.bytesReceivedPerSecond(),
                bridgeSample.bytesSentPerSecond(),
                bridgeSample.diskReadBytesPerSecond(),
                bridgeSample.diskWriteBytesPerSecond(),
                bridgeSample.sampleDurationMillis()
        );
    }

    private String chooseTelemetryText(String preferred, String fallback, String defaultValue) {
        if (preferred != null && !preferred.isBlank() && !preferred.equalsIgnoreCase("Unavailable") && !preferred.equalsIgnoreCase("No bridge data")) {
            return preferred;
        }
        if (fallback != null && !fallback.isBlank() && !fallback.equalsIgnoreCase("Unavailable") && !fallback.equalsIgnoreCase("No bridge data")) {
            return fallback;
        }
        return defaultValue;
    }

    private String mergeTelemetryErrors(String preferred, String fallback) {
        boolean hasPreferred = preferred != null && !preferred.isBlank() && !preferred.equalsIgnoreCase("none") && !preferred.equalsIgnoreCase("No bridge data");
        boolean hasFallback = fallback != null && !fallback.isBlank() && !fallback.equalsIgnoreCase("none") && !fallback.equalsIgnoreCase("No bridge data");
        if (hasPreferred && hasFallback) {
            return preferred + " | fallback: " + fallback;
        }
        if (hasPreferred) {
            return preferred;
        }
        if (hasFallback) {
            return fallback;
        }
        return "none";
    }

    private double chooseTelemetryDouble(double preferred, double fallback) {
        return Double.isFinite(preferred) && preferred >= 0.0 ? preferred : fallback;
    }

    private String chooseTelemetryProvider(double preferredValue, String preferredProvider, double fallbackValue, String fallbackProvider) {
        if (Double.isFinite(preferredValue) && preferredValue >= 0.0) {
            return normalizeTemperatureProvider(preferredProvider);
        }
        if (Double.isFinite(fallbackValue) && fallbackValue >= 0.0) {
            return normalizeTemperatureProvider(fallbackProvider);
        }
        return "Unavailable";
    }

    private Map<String, String> buildMetricProvenance() {
        Map<String, String> provenance = new LinkedHashMap<>();
        provenance.put("packetProcessingLatencyMs", "inferred from packet volume and client tick pressure");
        provenance.put("networkBufferSaturation", "inferred from packet burst thresholds");
        provenance.put("bytesPerEntity", "derived from inbound bytes divided by loaded entity count");
        provenance.put("chunkPipeline", "heuristic from thread names and render phase call counts");
        provenance.put("estimatedPhysicalCores", "heuristic estimate from logical processor count");
        provenance.put("parallelismEfficiency", "heuristic based on aggregate thread load");
        provenance.put("lightQueue", "best-effort parsed from chunk debug text");
        provenance.put("chunkCounts", "best-effort parsed from chunk debug text");
        return provenance;
    }

    private double sumProfilerThreadLoad() {
        return ThreadLoadProfiler.getInstance().getLatestRawThreadSnapshots().values().stream()
                .filter(raw -> raw.threadName() != null && raw.threadName().toLowerCase(Locale.ROOT).contains("taskmanager"))
                .mapToDouble(raw -> raw.snapshot().loadPercent())
                .sum();
    }

    private String extractTelemetryProvider(String source, String devicePrefix) {
        if (source == null || source.isBlank()) {
            return "Unavailable";
        }
        String prefix = devicePrefix == null ? "" : devicePrefix + ": ";
        for (String part : source.split("\\|")) {
            String trimmed = part.trim();
            if (!prefix.isBlank() && trimmed.startsWith(prefix)) {
                int bracket = trimmed.indexOf('[');
                String provider = bracket >= 0 ? trimmed.substring(prefix.length(), bracket).trim() : trimmed.substring(prefix.length()).trim();
                return normalizeTemperatureProvider(provider);
            }
        }
        return "Unavailable";
    }

    private String normalizeTemperatureProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return "Unavailable";
        }
        String lower = provider.toLowerCase(Locale.ROOT);
        if (lower.contains("windows pdh gpu counters") || lower.contains("windows performance counters") || lower.contains("jvm mxbean")) {
            return "Unavailable";
        }
        return provider;
    }

    private String buildGpuCoverageSummary(Map<String, RenderPhaseProfiler.PhaseSnapshot> renderPhases) {
        if (renderPhases == null || renderPhases.isEmpty()) {
            return "No tagged phases yet";
        }
        long totalGpuNanos = renderPhases.values().stream().mapToLong(RenderPhaseProfiler.PhaseSnapshot::gpuNanos).sum();
        long sharedGpuNanos = renderPhases.values().stream()
                .filter(phase -> phase.ownerMod() == null || phase.ownerMod().isBlank() || phase.ownerMod().startsWith("shared/"))
                .mapToLong(RenderPhaseProfiler.PhaseSnapshot::gpuNanos)
                .sum();
        long taggedPhases = renderPhases.values().stream()
                .filter(phase -> phase.ownerMod() != null && !phase.ownerMod().isBlank() && !phase.ownerMod().startsWith("shared/"))
                .count();
        double sharedPct = totalGpuNanos > 0L ? sharedGpuNanos * 100.0 / totalGpuNanos : 0.0;
        boolean irisLoaded = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris");
        boolean sodiumLoaded = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");
        List<String> coveredPaths = renderPhases.keySet().stream()
                .filter(name -> name != null && (name.contains("world") || name.contains("sky") || name.contains("particle") || name.contains("outline")))
                .limit(4)
                .toList();
        return String.format(Locale.ROOT,
                "%d tagged phases | %.1f%% shared/render carry-over | Iris %s | Sodium %s | paths %s",
                taggedPhases,
                sharedPct,
                irisLoaded ? "on" : "off",
                sodiumLoaded ? "on" : "off",
                coveredPaths.isEmpty() ? "warming up" : String.join(", ", coveredPaths));
    }

    private String buildCpuTemperatureUnavailableReason(TemperatureReading cpuTemperatureReading, WindowsTelemetryBridge.Sample bridgeSample) {
        if (cpuTemperatureReading != null && cpuTemperatureReading.value() >= 0.0) {
            return "CPU temperature provider active";
        }
        String source = bridgeSample.sensorSource() == null ? "" : bridgeSample.sensorSource();
        String error = bridgeSample.sensorErrorCode() == null || bridgeSample.sensorErrorCode().isBlank() ? "no provider-specific error reported" : bridgeSample.sensorErrorCode();
        if (source.toLowerCase(Locale.ROOT).contains("unavailable")) {
            return "CPU temperature unavailable because no external sensor bridge exposed a readable package sensor. Last bridge error: " + error;
        }
        return "CPU temperature unavailable. Source: " + source + " | Last bridge error: " + error;
    }

    private List<ThreadObservation> buildThreadObservations(ThreadSnapshotCollector.Snapshot latestStacks) {
        Map<Long, Long> threadAllocations = MemoryProfiler.getInstance().getThreadAllocationRateBytesPerSecond();
        return ThreadLoadProfiler.getInstance().getLatestRawThreadSnapshots().values().stream()
                .sorted((a, b) -> Double.compare(b.snapshot().loadPercent(), a.snapshot().loadPercent()))
                .map(raw -> {
                    ThreadSnapshotCollector.ThreadStackSnapshot stackSnapshot = latestStacks.threadsById().get(raw.threadId());
                    StackTraceElement[] stack = stackSnapshot == null ? null : stackSnapshot.stack();
                    AttributionInsights.ThreadAttribution attribution = AttributionInsights.attributeThread(raw.threadName(), stack);
                    ThreadRoleAnalysis role = classifyThreadRoleAnalysis(raw.threadName(), stack);
                    return new ThreadObservation(
                            raw,
                            stackSnapshot,
                            attribution,
                            role,
                            threadAllocations.getOrDefault(raw.threadId(), 0L)
                    );
                })
                .toList();
    }

    private List<ThreadDrilldown> buildThreadDrilldown(List<ThreadObservation> observations) {
        return observations.stream()
                .map(observation -> new ThreadDrilldown(
                        observation.raw().threadId(),
                        observation.raw().threadName(),
                        observation.raw().canonicalThreadName(),
                        observation.raw().snapshot().loadPercent(),
                        observation.allocationRateBytesPerSecond(),
                        observation.raw().snapshot().state(),
                        observation.raw().snapshot().blockedTimeDeltaMs(),
                        observation.raw().snapshot().waitedTimeDeltaMs(),
                        observation.attribution().ownerMod(),
                        observation.attribution().confidence().label(),
                        observation.attribution().reasonFrame(),
                        observation.attribution().topFrames(),
                        observation.role().label(),
                        observation.role().source(),
                        observation.attribution().candidateLabels()
                ))
                .toList();
    }

    private List<ContentionSample> buildContentionSamples(List<ThreadObservation> observations) {
        Map<Long, ThreadObservation> byThreadId = new LinkedHashMap<>();
        for (ThreadObservation observation : observations) {
            byThreadId.put(observation.raw().threadId(), observation);
        }
        List<ContentionSample> result = new ArrayList<>();
        for (ThreadObservation waiter : observations) {
            ThreadLoadProfiler.ThreadSnapshot waiterSnapshot = waiter.raw().snapshot();
            boolean waiting = waiterSnapshot.blockedCountDelta() > 0
                    || waiterSnapshot.waitedCountDelta() > 0
                    || "BLOCKED".equals(waiterSnapshot.state())
                    || "WAITING".equals(waiterSnapshot.state());
            if (!waiting || waiterSnapshot.lockOwnerThreadId() <= 0L) {
                continue;
            }
            ThreadObservation owner = byThreadId.get(waiterSnapshot.lockOwnerThreadId());
            AttributionInsights.ThreadAttribution ownerAttribution = owner == null
                    ? AttributionInsights.attributeThread(waiterSnapshot.lockOwnerName(), null)
                    : owner.attribution();
            ThreadRoleAnalysis ownerRole = owner == null
                    ? classifyThreadRoleAnalysis(waiterSnapshot.lockOwnerName(), null)
                    : owner.role();
            String lockName = waiterSnapshot.lockName() == null || waiterSnapshot.lockName().isBlank() ? "unknown lock" : waiterSnapshot.lockName();
            result.add(new ContentionSample(
                    waiter.raw().threadId(),
                    waiter.raw().threadName(),
                    waiter.attribution().ownerMod(),
                    waiter.attribution().confidence().label(),
                    waiter.role().label(),
                    owner == null ? waiterSnapshot.lockOwnerThreadId() : owner.raw().threadId(),
                    owner == null ? blankToUnknown(waiterSnapshot.lockOwnerName()) : owner.raw().threadName(),
                    ownerAttribution.ownerMod(),
                    ownerAttribution.confidence().label(),
                    ownerRole.label(),
                    lockName,
                    waiterSnapshot.blockedTimeDeltaMs(),
                    waiterSnapshot.waitedTimeDeltaMs(),
                    waiter.attribution().candidateLabels(),
                    ownerAttribution.candidateLabels(),
                    pairwiseConfidence(waiter.attribution(), ownerAttribution).label()
            ));
        }
        return result;
    }

    private long sumPhaseCalls(Map<String, RenderPhaseProfiler.PhaseSnapshot> phases, String... needles) {
        return phases.entrySet().stream()
                .filter(entry -> {
                    String lower = entry.getKey().toLowerCase(Locale.ROOT);
                    for (String needle : needles) {
                        if (lower.contains(needle)) {
                            return true;
                        }
                    }
                    return false;
                })
                .mapToLong(entry -> Math.max(entry.getValue().cpuCalls(), entry.getValue().gpuCalls()))
                .sum();
    }

    private int parseLeadingInt(String value) {
        if (value == null) {
            return -1;
        }
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private record PlayerMotionSnapshot(double speedBlocksPerSecond, int chunksEnteredLastSecond, double distanceTravelledBlocks) {}

    private PlayerMotionSnapshot samplePlayerMotion(long now) {
        try {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) {
                return new PlayerMotionSnapshot(-1.0, chunkEntryTimes.size(), distanceTravelledBlocks);
            }
            double speed = -1.0;
            if (lastPlayerPosValid && lastPlayerSampleAtMillis > 0L) {
                long elapsed = Math.max(1L, now - lastPlayerSampleAtMillis);
                double dx = client.player.getX() - lastPlayerX;
                double dy = client.player.getY() - lastPlayerY;
                double dz = client.player.getZ() - lastPlayerZ;
                double distance = Math.sqrt((dx * dx) + (dy * dy) + (dz * dz));
                distanceTravelledBlocks += distance;
                speed = distance * 1000.0 / elapsed;
            }
            lastPlayerX = client.player.getX();
            lastPlayerY = client.player.getY();
            lastPlayerZ = client.player.getZ();
            lastPlayerSampleAtMillis = now;
            lastPlayerPosValid = true;

            int chunkX = client.player.chunkPosition().x();
            int chunkZ = client.player.chunkPosition().z();
            if (!lastPlayerChunkValid || chunkX != lastPlayerChunkX || chunkZ != lastPlayerChunkZ) {
                chunkEntryTimes.addLast(now);
                lastPlayerChunkX = chunkX;
                lastPlayerChunkZ = chunkZ;
                lastPlayerChunkValid = true;
            }
            while (!chunkEntryTimes.isEmpty() && now - chunkEntryTimes.peekFirst() > 1000L) {
                chunkEntryTimes.removeFirst();
            }
            return new PlayerMotionSnapshot(speed, chunkEntryTimes.size(), distanceTravelledBlocks);
        } catch (Throwable ignored) {
            return new PlayerMotionSnapshot(-1.0, chunkEntryTimes.size(), distanceTravelledBlocks);
        }
    }

    static String classifyThreadRole(String threadName, StackTraceElement[] stack) {
        return classifyThreadRoleAnalysis(threadName, stack).label();
    }

    private static ThreadRoleAnalysis classifyThreadRoleAnalysis(String threadName, StackTraceElement[] stack) {
        String lowerName = threadName == null ? "" : threadName.toLowerCase(Locale.ROOT);
        String stackText = buildStackText(stack);
        String lowerStack = stackText.toLowerCase(Locale.ROOT);
        boolean render = lowerName.contains("render") || lowerStack.contains("gamerenderer") || lowerStack.contains("worldrenderer");
        boolean server = lowerName.contains("server") || lowerName.contains("main thread") || lowerStack.contains("minecraftserver");
        boolean profiler = lowerName.contains("taskmanager") || lowerStack.contains("wueffi.taskmanager");
        boolean gc = containsAny(lowerName, "g1", "gc") || containsAny(lowerStack, "sun.jvm", "g1");
        boolean network = containsAny(lowerName, "netty", "network") || containsAny(lowerStack, "clientconnection", "packet", "netty");
        boolean ioPool = containsAny(lowerName, "io", "file", "save", "storage")
                || containsAny(lowerStack, "region", "anvil", "storage", "filechannel", "asynchronousfilechannel", "nio", "zip", "compress", "flush");
        boolean chunkGeneration = containsAny(lowerName, "worldgen", "gen")
                || containsAny(lowerStack, "chunkstatus", "noisechunk", "worldgen", "generator");
        boolean chunkMeshing = containsAny(lowerName, "chunk build", "builder", "mesh")
                || containsAny(lowerStack, "chunkbuilder", "rebuild", "meshing", "compileterrain");
        boolean chunkUpload = containsAny(lowerName, "upload")
                || containsAny(lowerStack, "vertexbuffer", "upload", "bufferbuilder", "glbuffer");
        boolean workerPool = containsAny(lowerName, "worker", "executor", "pool", "forkjoin", "c2me", "async")
                || containsAny(lowerStack, "threadpoolexecutor", "forkjoin", "completablefuture", "executor", "worker", "c2me", "mailbox");

        if (profiler) {
            return new ThreadRoleAnalysis("Profiler", sourceForRole(lowerName, lowerStack, "taskmanager"), false, false, false, false, false, false);
        }
        if (render) {
            return new ThreadRoleAnalysis("Render", sourceForRole(lowerName, lowerStack, "render"), true, false, false, false, false, false);
        }
        if (server) {
            return new ThreadRoleAnalysis("Main Logic", sourceForRole(lowerName, lowerStack, "server"), true, false, false, false, false, false);
        }
        if (chunkUpload) {
            return new ThreadRoleAnalysis("Chunk Upload Worker", sourceForRole(lowerName, lowerStack, "upload"), false, true, false, false, false, true);
        }
        if (chunkMeshing) {
            return new ThreadRoleAnalysis("Chunk Meshing Worker", sourceForRole(lowerName, lowerStack, "mesh"), false, true, false, false, true, false);
        }
        if (chunkGeneration) {
            return new ThreadRoleAnalysis("Chunk Generation Worker", sourceForRole(lowerName, lowerStack, "worldgen"), false, true, false, true, false, false);
        }
        if (ioPool) {
            return new ThreadRoleAnalysis("IO Pool", sourceForRole(lowerName, lowerStack, "storage"), false, false, true, false, false, false);
        }
        if (network) {
            return new ThreadRoleAnalysis("Network", sourceForRole(lowerName, lowerStack, "netty"), false, false, false, false, false, false);
        }
        if (gc) {
            return new ThreadRoleAnalysis("GC", sourceForRole(lowerName, lowerStack, "gc"), false, false, false, false, false, false);
        }
        if (workerPool) {
            return new ThreadRoleAnalysis("Worker Pool", sourceForRole(lowerName, lowerStack, "worker"), false, true, false, false, false, false);
        }
        return new ThreadRoleAnalysis("Other", "fallback", false, false, false, false, false, false);
    }

    private String buildSchedulingConflictSummary(List<ThreadObservation> observations) {
        ThreadObservation hottestMain = observations.stream()
                .filter(observation -> observation.role().mainLogic())
                .max((a, b) -> Double.compare(a.raw().snapshot().loadPercent(), b.raw().snapshot().loadPercent()))
                .orElse(null);
        double workerLoad = observations.stream()
                .filter(observation -> observation.role().countsAsWorker())
                .mapToDouble(observation -> observation.raw().snapshot().loadPercent())
                .sum();
        int activeWorkers = countWorkers(observations, true);
        int physicalCores = estimatePhysicalCores();
        if (hottestMain != null
                && hottestMain.raw().snapshot().loadPercent() > 35.0
                && workerLoad > Math.max(45.0, physicalCores * 12.0)
                && activeWorkers >= Math.max(2, physicalCores / 2)) {
            return String.format(
                    Locale.ROOT,
                    "Possible scheduling conflict: %s %.1f%% with %s worker load %.1f%% across %d estimated physical cores",
                    hottestMain.raw().canonicalThreadName(),
                    hottestMain.raw().snapshot().loadPercent(),
                    dominantWorkerLabel(observations),
                    workerLoad,
                    physicalCores
            );
        }
        return "No scheduling conflict detected";
    }

    private String buildParallelismFlag(List<ThreadObservation> observations) {
        int activeWorkers = countWorkers(observations, true);
        int idleWorkers = countWorkers(observations, false);
        long blockedWorkers = observations.stream()
                .filter(observation -> observation.role().countsAsWorker())
                .filter(observation -> {
                    ThreadLoadProfiler.ThreadSnapshot snapshot = observation.raw().snapshot();
                    return snapshot.blockedCountDelta() > 0
                            || snapshot.waitedCountDelta() > 0
                            || "BLOCKED".equals(snapshot.state())
                            || "WAITING".equals(snapshot.state());
                })
                .count();
        double workerLoad = observations.stream()
                .filter(observation -> observation.role().countsAsWorker())
                .mapToDouble(observation -> observation.raw().snapshot().loadPercent())
                .sum();
        ThreadObservation hottestMain = observations.stream()
                .filter(observation -> observation.role().mainLogic())
                .max((a, b) -> Double.compare(a.raw().snapshot().loadPercent(), b.raw().snapshot().loadPercent()))
                .orElse(null);
        double mainLoad = hottestMain == null ? 0.0 : hottestMain.raw().snapshot().loadPercent();
        if (activeWorkers == 0 && workerLoad < 5.0) {
            return String.format(Locale.ROOT, "Parallelism low (%d high-load threads)", countHighLoadThreads(observations));
        }
        if (blockedWorkers >= Math.max(2, activeWorkers) && activeWorkers > 0) {
            return String.format(Locale.ROOT, "Parallelism blocked (%d active / %d waiting)", activeWorkers, blockedWorkers);
        }
        if (mainLoad > 35.0 && workerLoad > Math.max(80.0, estimatePhysicalCores() * 18.0)) {
            return String.format(Locale.ROOT, "Parallelism saturated (%d workers / %d idle / %d high-load threads)", activeWorkers, idleWorkers, countHighLoadThreads(observations));
        }
        return String.format(Locale.ROOT, "Parallelism healthy (%d workers / %d idle / %d high-load threads)", activeWorkers, idleWorkers, countHighLoadThreads(observations));
    }

    private int estimatePhysicalCores() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    }

    private int countHighLoadThreads(List<ThreadObservation> observations) {
        return (int) observations.stream()
                .map(ThreadObservation::raw)
                .map(ThreadLoadProfiler.RawThreadSnapshot::snapshot)
                .filter(snapshot -> snapshot.loadPercent() >= 50.0)
                .count();
    }

    private String buildMainLogicSummary(List<ThreadObservation> observations) {
        ThreadObservation main = observations.stream()
                .filter(observation -> observation.role().mainLogic())
                .max((a, b) -> Double.compare(a.raw().snapshot().loadPercent(), b.raw().snapshot().loadPercent()))
                .orElse(null);
        if (main == null) {
            return "Main Logic: n/a";
        }
        return String.format(Locale.ROOT, "Main Logic: %s (%.0f%%)", main.raw().threadName(), main.raw().snapshot().loadPercent());
    }

    private String buildBackgroundSummary(List<ThreadObservation> observations) {
        double backgroundLoad = observations.stream()
                .filter(observation -> !observation.role().mainLogic())
                .mapToDouble(observation -> observation.raw().snapshot().loadPercent())
                .sum();
        String label = observations.stream()
                .filter(observation -> !observation.role().mainLogic())
                .map(observation -> observation.role().label())
                .findFirst()
                .orElse("Infrastructure");
        return String.format(Locale.ROOT, "Background: %s (%.0f%%)", label, backgroundLoad);
    }

    private String buildCpuSensorStatus(String sensorSource) {
        String lower = sensorSource == null ? "" : sensorSource.toLowerCase(Locale.ROOT);
        if (lower.contains("cpu: core temp shared memory")) {
            return "CPU sensor via Core Temp";
        }
        if (lower.contains("cpu: librehardwaremonitor dll") || lower.contains("cpu: root/librehardwaremonitor")) {
            return "CPU sensor via LibreHardwareMonitor";
        }
        if (lower.contains("cpu: openhardwaremonitor dll") || lower.contains("cpu: root/openhardwaremonitor")) {
            return "CPU sensor via OpenHardwareMonitor";
        }
        if (lower.contains("cpu: hwinfo shared memory")) {
            return "CPU sensor via HWiNFO";
        }
        if (lower.contains("cpu: unavailable")) {
            return "CPU sensor unavailable";
        }
        return "CPU sensor provider detected";
    }



    private String buildParallelismEfficiency(double totalThreadLoad, int activeWorkers, int idleWorkers) {
        if (activeWorkers >= Math.max(4, estimatePhysicalCores()) && totalThreadLoad > 800.0) {
            return "Heavy multithreading active.";
        }
        if (activeWorkers == 0 && totalThreadLoad < 300.0) {
            return "Light multithreading active.";
        }
        if (idleWorkers > activeWorkers && activeWorkers > 0) {
            return "Moderate multithreading active with spare worker capacity.";
        }
        return "Moderate multithreading active.";
    }

    private int countWorkers(List<ThreadObservation> observations, boolean active) {
        return (int) observations.stream()
                .filter(observation -> observation.role().countsAsWorker())
                .filter(observation -> active
                        ? observation.raw().snapshot().loadPercent() >= 5.0 || "RUNNABLE".equals(observation.raw().snapshot().state())
                        : observation.raw().snapshot().loadPercent() < 5.0 && ("WAITING".equals(observation.raw().snapshot().state()) || "TIMED_WAITING".equals(observation.raw().snapshot().state())))
                .count();
    }

    private int countRoleMatches(List<ThreadObservation> observations, java.util.function.Predicate<ThreadRoleAnalysis> predicate) {
        return (int) observations.stream()
                .map(ThreadObservation::role)
                .filter(predicate)
                .count();
    }

    private AttributionInsights.Confidence pairwiseConfidence(AttributionInsights.ThreadAttribution waiter, AttributionInsights.ThreadAttribution owner) {
        if (waiter == null || owner == null) {
            return AttributionInsights.Confidence.WEAK_HEURISTIC;
        }
        boolean waiterConcrete = isConcreteMod(waiter.ownerMod());
        boolean ownerConcrete = isConcreteMod(owner.ownerMod());
        if (waiterConcrete && ownerConcrete
                && waiter.confidence() != AttributionInsights.Confidence.WEAK_HEURISTIC
                && owner.confidence() != AttributionInsights.Confidence.WEAK_HEURISTIC) {
            return AttributionInsights.Confidence.PAIRWISE_INFERRED;
        }
        if (waiterConcrete || ownerConcrete) {
            return AttributionInsights.Confidence.INFERRED;
        }
        return AttributionInsights.Confidence.WEAK_HEURISTIC;
    }

    private boolean isConcreteMod(String modId) {
        return modId != null && !modId.isBlank() && !modId.startsWith("shared/") && !modId.startsWith("runtime/");
    }

    private static String buildStackText(StackTraceElement[] stack) {
        if (stack == null || stack.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (StackTraceElement frame : stack) {
            if (frame == null) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(frame.getClassName()).append('#').append(frame.getMethodName());
        }
        return builder.toString();
    }

    private static String sourceForRole(String lowerName, String lowerStack, String marker) {
        boolean inName = marker != null && !marker.isBlank() && lowerName.contains(marker);
        boolean inStack = marker != null && !marker.isBlank() && lowerStack.contains(marker);
        if (inName && inStack) {
            return "thread name + stack ancestry";
        }
        if (inStack) {
            return "stack ancestry";
        }
        if (inName) {
            return "thread name";
        }
        return "heuristic";
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String dominantWorkerLabel(List<ThreadObservation> observations) {
        return observations.stream()
                .filter(observation -> observation.role().countsAsWorker())
                .map(observation -> observation.role().label())
                .findFirst()
                .orElse("background");
    }

    private String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private String sampleBiome() {
        try {
            Minecraft client = Minecraft.getInstance();
            if (client.level == null || client.player == null) {
                return "unknown";
            }
            return client.level.getBiome(client.player.blockPosition()).unwrapKey().map(key -> key.identifier().toString()).orElse("unknown");
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private String sampleLightQueue() {
        try {
            Minecraft client = Minecraft.getInstance();
            if (client.levelRenderer == null) {
                return "unavailable";
            }
            String debug = client.levelRenderer.getSectionStatistics();
            if (debug == null || debug.isBlank()) {
                return "unavailable";
            }
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("L:\\s*(\\d+)").matcher(debug);
            if (matcher.find()) {
                return matcher.group(1) + " updates";
            }
        } catch (Throwable ignored) {
        }
        return "unavailable";
    }

    private String stringOrEmpty(String value) {
        return value == null ? "" : value;
    }
}












