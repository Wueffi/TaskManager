package wueffi.taskmanager.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import wueffi.taskmanager.client.AttributionModelBuilder.EffectiveCpuAttribution;
import wueffi.taskmanager.client.AttributionModelBuilder.EffectiveGpuAttribution;
import wueffi.taskmanager.client.AttributionModelBuilder.EffectiveMemoryAttribution;
import wueffi.taskmanager.client.util.ConfigManager;
import wueffi.taskmanager.client.util.ModTimingSnapshot;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProfilerManager {

    public enum CaptureMode {
        OFF,
        OPEN_ONLY,
        PASSIVE_LIGHTWEIGHT,
        SPIKE_CAPTURE,
        MANUAL_DEEP;

        public CaptureMode next() {
            CaptureMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public enum LiveCollectionMode {
        INACTIVE,
        HUD,
        SESSION,
        SCREEN,
        CAPTURE
    }

    public record EntityCounts(int totalEntities, int livingEntities, int blockEntities) {
        static EntityCounts empty() {
            return new EntityCounts(0, 0, 0);
        }
    }

    public record ChunkCounts(int loadedChunks, int renderedChunks) {
        static ChunkCounts empty() {
            return new ChunkCounts(0, 0);
        }
    }

    public record HotChunkSnapshot(int chunkX, int chunkZ, int entityCount, int blockEntityCount, String topEntityClass, String topBlockEntityClass, double activityScore) {}

    public record EntityHotspot(String className, int count, String heuristic) {}

    public record BlockEntityHotspot(String className, int count, String heuristic) {}

    public record RuleFinding(String severity, String category, String message, String confidence, String details, String nextStep, String metricSummary) {}
    public record ConflictObservation(
            String waiterMod,
            String ownerMod,
            String lockName,
            String waiterThreadName,
            String ownerThreadName,
            String waiterRole,
            String ownerRole,
            long blockedTimeDeltaMs,
            long waitedTimeDeltaMs,
            boolean slowdownOverlap,
            String confidence,
            List<String> waiterCandidates,
            List<String> ownerCandidates
    ) {}
    public record ConflictEdge(
            String waiterMod,
            String ownerMod,
            String lockName,
            String waiterThreadName,
            String ownerThreadName,
            String waiterRole,
            String ownerRole,
            long observations,
            long slowdownObservations,
            long blockedTimeMs,
            long waitedTimeMs,
            String confidence,
            List<String> waiterCandidates,
            List<String> ownerCandidates
    ) {}
    public record PerformanceAlert(String key, String label, String message, String severity, long triggeredAtEpochMillis, double value, double threshold, int consecutiveBreaches) {}
    private record WorldScanResult(EntityCounts entityCounts, List<HotChunkSnapshot> hotChunks, List<EntityHotspot> entityHotspots, List<BlockEntityHotspot> blockEntityHotspots) {}

    record ExportResult(String status, Path directory, Path htmlReport, Path chromeTrace) {}

    public record ExportMetadata(
            String taskManagerVersion,
            String minecraftVersion,
            String fabricLoaderVersion,
            String osName,
            String osVersion,
            String javaVersion,
            String cpuInfo,
            String gpuInfo,
            int guiScale,
            String captureMode,
            String hudTriggerMode,
            int frameBudgetTargetFps,
            double frameBudgetTargetMs,
            java.util.List<String> modList,
            double consistentFps,
            double onePercentLowFps,
            double pointOnePercentLowFps,
            long sessionSampleIntervalMs,
            long observedSessionSampleIntervalMs,
            int missedSessionSamples,
            long maxSessionSampleGapMs,
            java.util.List<String> jvmLaunchArguments,
            java.util.Map<String, Object> minecraftSettings,
            java.util.Map<String, Object> graphicsModSettings
    ) {}

    public record SpikeCapture(
            long capturedAtEpochMillis,
            double frameDurationMs,
            double stutterScore,
            EntityCounts entityCounts,
            ChunkCounts chunkCounts,
            List<String> topCpuMods,
            List<String> topRenderPhases,
            List<String> topThreads,
            String likelyBottleneck,
            List<RuleFinding> findings
    ) {}

    public record SessionBaseline(
            double avgFps,
            double onePercentLowFps,
            double avgMspt,
            double msptP95,
            long avgHeapUsedBytes,
            Map<String, Double> cpuEffectivePercentByMod,
            Map<String, Double> gpuEffectivePercentByMod,
            Map<String, Double> memoryEffectiveMbByMod,
            long capturedAtEpochMillis,
            String label
    ) {}

    public record SessionDelta(
            double fpsChange,
            double onePercentLowFpsChange,
            double msptChange,
            double msptP95Change,
            double heapChangeMb,
            Map<String, Double> cpuDeltaByMod,
            Map<String, Double> gpuDeltaByMod,
            Map<String, Double> memoryDeltaMbByMod
    ) {}

    public record SpikeDelta(
            double frameDurationDeltaMs,
            double stutterScoreDelta,
            List<String> newTopCpuMods,
            List<String> removedTopCpuMods,
            String bottleneckChange
    ) {}

    public record SaveEvent(long startedAtEpochMillis, long durationMs, String type) {}

    public record SessionPoint(
            long capturedAtEpochMillis,
            long sampleIntervalMs,
            int missedSamplesSincePrevious,
            double currentFps,
            double averageFps,
            double onePercentLowFps,
            double pointOnePercentLowFps,
            double frameTimeMs,
            double frameTimeAvgMs,
            double frameTimeP95Ms,
            double frameTimeP99Ms,
            double frameTimeStdDevMs,
            double frameTimeVarianceMs,
            Map<String, Double> frameTimeBuckets,
            double frameBuildTimeMs,
            double gpuFrameTimeMs,
            double gpuFrameTimeP95Ms,
            double tickTimeMs,
            double millisecondsPerTick,
            double msptAvg,
            double msptP95,
            double msptP99,
            double clientTickMs,
            double serverTickMs,
            double stutterScore,
            double usedHeapMb,
            double allocatedHeapMb,
            long heapUsedBytes,
            long heapCommittedBytes,
            boolean isGcEvent,
            long gcPauseDurationMs,
            long gcPauseTotalMs,
            long gcPauseMaxMs,
            long gcCount,
            String gcType,
            long cpuSamples,
            long renderSamples,
            long drawCalls,
            long bufferUpdates,
            double mouseInputLatencyMs,
            String topThreadName,
            String cpuParallelismFlag,
            int chunksGenerating,
            int chunksMeshing,
            int chunksUploading,
            int lightsUpdatePending,
            long chunkMeshesRebuilt,
            long chunkMeshesUploaded,
            double playerSpeedBlocksPerSecond,
            int chunksEnteredLastSecond,
            double distanceTravelledBlocks,
            long vramUsedBytes,
            long vramReservedBytes,
            long textureUploadRate,
            EntityCounts entityCounts,
            ChunkCounts chunkCounts,
            SystemMetricsProfiler.Snapshot systemMetrics,
            NetworkPacketProfiler.Snapshot networkSnapshot,
            HotChunkSnapshot topHotChunk,
            List<EntityHotspot> entityHotspots,
            List<BlockEntityHotspot> blockEntityHotspots,
            List<String> lockSummaries,
            List<RuleFinding> ruleFindings,
            List<String> topCpuMods,
            List<String> topGpuMods,
            Map<String, Integer> cpuThreadCountsByMod,
            Map<String, Integer> gpuThreadCountsByMod,
            Map<String, Integer> memoryClassCountsByMod,
            Map<String, Double> cpuRawPercentByMod,
            Map<String, Double> cpuEffectivePercentByMod,
            Map<String, Double> gpuRawPercentByMod,
            Map<String, Double> gpuEffectivePercentByMod,
            Map<String, Double> memoryRawMbByMod,
            Map<String, Double> memoryEffectiveMbByMod,
            Map<String, String> cpuConfidenceByMod,
            Map<String, String> gpuConfidenceByMod,
            Map<String, String> memoryConfidenceByMod,
            Map<String, String> cpuProvenanceByMod,
            Map<String, String> gpuProvenanceByMod,
            Map<String, String> memoryProvenanceByMod,
            String collectorGovernorMode,
            double profilerSelfCostMs,
            boolean isSaveEvent,
            long saveDurationMs,
            String saveType
    ) {}

    public record ProfilerSnapshot(
            long capturedAtEpochMillis,
            CaptureMode mode,
            boolean captureActive,
            boolean cpuReady,
            boolean gpuReady,
            long cpuSampleAgeMillis,
            long totalCpuSamples,
            long totalRenderSamples,
            Map<String, CpuSamplingProfiler.Snapshot> cpuMods,
            Map<String, CpuSamplingProfiler.DetailSnapshot> cpuDetails,
            Map<String, ModTimingSnapshot> modInvokes,
            Map<String, RenderPhaseProfiler.PhaseSnapshot> renderPhases,
            MemoryProfiler.Snapshot memory,
            Map<String, Long> memoryMods,
            Map<String, Long> sharedMemoryFamilies,
            Map<String, Map<String, Long>> sharedFamilyClasses,
            Map<String, Map<String, Long>> memoryClassesByMod,
            long memoryAgeMillis,
            EntityCounts entityCounts,
            ChunkCounts chunkCounts,
            SystemMetricsProfiler.Snapshot systemMetrics,
            double stutterScore,
            List<StartupTimingProfiler.StartupRow> startupRows,
            long startupFirst,
            long startupLast,
            Map<String, Long> flamegraphStacks,
            List<SpikeCapture> spikes,
            boolean sessionLogging,
            long sessionLoggingElapsedMillis,
            String lastExportStatus
    ) {
        static ProfilerSnapshot empty() {
            return new ProfilerSnapshot(
                    System.currentTimeMillis(),
                    CaptureMode.OPEN_ONLY,
                    false,
                    false,
                    false,
                    Long.MAX_VALUE,
                    0,
                    0,
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    MemoryProfiler.Snapshot.empty(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Long.MAX_VALUE,
                    EntityCounts.empty(),
                    ChunkCounts.empty(),
                    SystemMetricsProfiler.Snapshot.empty(),
                    0,
                    List.of(),
                    0,
                    0,
                    Map.of(),
                    List.of(),
                    false,
                    0,
                    ""
            );
        }
    }

    private static final ProfilerManager INSTANCE = new ProfilerManager();
    public static ProfilerManager getInstance() { return INSTANCE; }

    private static final int WINDOW_SIZE = 20;
    private static final long SPIKE_THRESHOLD_NS = 50_000_000L;
    private static final int MAX_SPIKES = 8;
    private static final int MAX_WORLD_SCAN_ENTITIES = 2_000;
    private static final int MAX_WORLD_SCAN_BLOCK_ENTITIES = 1_000;
    private static final int WORLD_SCAN_ENTITY_SAMPLE_STRIDE = 4;
    private static final int WORLD_SCAN_BLOCK_ENTITY_SAMPLE_STRIDE = 2;
    private static final Gson EXPORT_GSON = new GsonBuilder().serializeSpecialFloatingPointValues().setPrettyPrinting().create();
    private static final Pattern CHUNK_DEBUG_PATTERN = Pattern.compile("C:\\s*(\\d+)/(\\d+)");

    private final Deque<Map<String, CpuSamplingProfiler.Snapshot>> cpuWindows = new ArrayDeque<>();
    private final Deque<Map<String, CpuSamplingProfiler.DetailSnapshot>> cpuDetailWindows = new ArrayDeque<>();
    private final Deque<Map<String, ModTimingSnapshot>> modWindows = new ArrayDeque<>();
    private final Deque<Map<String, RenderPhaseProfiler.PhaseSnapshot>> renderWindows = new ArrayDeque<>();
    private final Deque<List<ConflictObservation>> conflictWindows = new ArrayDeque<>();
    private final Deque<SpikeCapture> spikes = new ArrayDeque<>();
    private final Deque<SessionPoint> sessionHistory = new ArrayDeque<>();
    private final Deque<List<HotChunkSnapshot>> hotChunkHistory = new ArrayDeque<>();
    private final Map<Long, Deque<Integer>> chunkActivityHistory = new LinkedHashMap<>();


    private volatile List<HotChunkSnapshot> latestHotChunks = List.of();
    private volatile List<EntityHotspot> latestEntityHotspots = List.of();
    private volatile List<BlockEntityHotspot> latestBlockEntityHotspots = List.of();
    private volatile List<ConflictEdge> latestConflictEdges = List.of();
    private volatile List<String> latestLockSummaries = List.of();
    private volatile List<RuleFinding> latestRuleFindings = List.of();
    private final Deque<Map<String, Object>> stutterJumpSnapshots = new ArrayDeque<>();
    private double lastStutterScore = 0.0;
    private volatile boolean screenOpen = false;
    private volatile CaptureMode mode = CaptureMode.OPEN_ONLY;
    private volatile ProfilerSnapshot currentSnapshot = ProfilerSnapshot.empty();
    private volatile String lastExportStatus = "";
    private volatile Path lastExportDirectory;
    private volatile Path lastExportHtmlReport;
    private volatile SessionBaseline sessionBaseline;
    private volatile SpikeCapture pinnedSpike;
    private volatile EntityCounts latestEntityCounts = EntityCounts.empty();
    private volatile ChunkCounts latestChunkCounts = ChunkCounts.empty();
    private volatile boolean sessionLogging;
    private volatile long sessionLoggingStartedAtMillis;
    private volatile boolean sessionRecorded;
    private volatile long sessionRecordedAtMillis;
    private volatile int sessionMissedSamples;
    private volatile long sessionMaxSampleGapMillis;
    private volatile long sessionExpectedSampleIntervalMillis = 50L;
    private volatile PerformanceAlert latestPerformanceAlert;
    private volatile long performanceAlertFlashUntilMillis;
    private long lastSeenFrameSequence = 0;
    private long lastSnapshotPublishedAtMillis = 0L;
    private long lastWorldScanAtMillis = 0L;
    private long lastWorldScanDurationMillis = 0L;
    private long lastChunkCountsAtMillis = 0L;
    private int frameAlertConsecutiveBreaches = 0;
    private int serverAlertConsecutiveBreaches = 0;
    private volatile long activeSaveStartedAtMillis;
    private volatile long activeSaveStartedAtEpochMillis;
    private volatile String activeSaveType = "";
    private volatile long lastCompletedSaveStartedAtMillis;
    private volatile long lastCompletedSaveDurationMillis;
    private volatile String lastCompletedSaveType = "";
    private final Map<String, Long> lastPerformanceAlertAtMillis = new ConcurrentHashMap<>();
    private final Deque<SaveEvent> recentSaves = new ArrayDeque<>();
    private final RuleEngine ruleEngine = new RuleEngine();
    private final SessionExporter sessionExporter = new SessionExporter();

    public void initialize() {
        mode = ConfigManager.getCaptureMode();
        sessionBaseline = sessionExporter.loadBaseline();
        ThreadSnapshotCollector.getInstance().start();
        CpuSamplingProfiler.getInstance().start();
        publishSnapshot(true);
    }

    public void onScreenOpened() {
        screenOpen = true;
        if (mode == CaptureMode.OPEN_ONLY || mode == CaptureMode.MANUAL_DEEP) {
            clearLiveWindows();
            RenderPhaseProfiler.getInstance().reset();
            ModTimingProfiler.getInstance().reset();
            CpuSamplingProfiler.getInstance().reset();
            FlamegraphProfiler.getInstance().reset();
            TickProfiler.getInstance().reset();
        }
    }

    public void onScreenClosed() {
        screenOpen = false;
        if (mode == CaptureMode.OPEN_ONLY) {
            clearLiveWindows();
            publishSnapshot(true);
        }
    }

    public void cycleMode() {
        setMode(mode.next());
    }

    public void setMode(CaptureMode mode) {
        this.mode = mode;
        ConfigManager.setCaptureMode(mode);
        clearLiveWindows();
        publishSnapshot(true);
    }

    public boolean isCaptureActive() {
        return computeCaptureActive(mode, screenOpen, sessionLogging);
    }

    static boolean computeCaptureActive(CaptureMode mode, boolean screenOpen, boolean sessionLogging) {
        if (sessionLogging) {
            return true;
        }
        return switch (mode) {
            case OFF -> false;
            case OPEN_ONLY -> screenOpen;
            case PASSIVE_LIGHTWEIGHT, SPIKE_CAPTURE -> true;
            case MANUAL_DEEP -> screenOpen;
        };
    }

    public CaptureMode getMode() {
        return mode;
    }

    public LiveCollectionMode getLiveCollectionMode() {
        if (screenOpen) {
            return LiveCollectionMode.SCREEN;
        }
        if (sessionLogging) {
            return LiveCollectionMode.SESSION;
        }
        if (ConfigManager.isHudEnabled()) {
            return LiveCollectionMode.HUD;
        }
        if (isCaptureActive()) {
            return LiveCollectionMode.CAPTURE;
        }
        return LiveCollectionMode.INACTIVE;
    }

    public boolean shouldCollectFrameMetrics() {
        return getLiveCollectionMode() != LiveCollectionMode.INACTIVE;
    }

    public boolean shouldCollectDetailedMetrics() {
        if (isProfilerSelfProtectionActive() && !screenOpen && !sessionLogging) {
            return false;
        }
        return switch (getLiveCollectionMode()) {
            case SCREEN, SESSION, CAPTURE -> true;
            default -> false;
        };
    }

    public String getCollectorGovernorMode() {
        if (isProfilerSelfProtectionActive()) {
            return "self-protect";
        }
        double latestFrameMs = FrameTimelineProfiler.getInstance().getLatestFrameNs() / 1_000_000.0;
        double targetFrameMs = ConfigManager.getFrameBudgetTargetFrameMs();
        if (sessionLogging || screenOpen || isPerformanceAlertFlashActive() || latestFrameMs >= targetFrameMs * 1.75) {
            return "burst";
        }
        if (latestFrameMs >= targetFrameMs * 1.2) {
            return "tight";
        }
        if (!shouldCollectFrameMetrics()) {
            return "light";
        }
        return "normal";
    }

    public boolean isProfilerSelfProtectionActive() {
        if (screenOpen || sessionLogging) {
            return false;
        }
        SystemMetricsProfiler.Snapshot system = currentSnapshot.systemMetrics();
        return system != null && (system.profilerCpuLoadPercent() >= 4.0 || system.worldScanCostMillis() >= 12L || system.memoryHistogramCostMillis() >= 20L);
    }

    public long getLastWorldScanDurationMillis() {
        return lastWorldScanDurationMillis;
    }

    public ProfilerSnapshot getCurrentSnapshot() {
        return currentSnapshot;
    }

    public SessionBaseline getSessionBaseline() {
        return sessionBaseline;
    }

    public void setBaseline(SessionBaseline baseline) {
        sessionBaseline = baseline;
        sessionExporter.saveBaseline(baseline);
        requestSnapshotPublish();
    }

    public void clearBaseline() {
        sessionBaseline = null;
        sessionExporter.clearBaseline();
        requestSnapshotPublish();
    }

    public SessionBaseline captureBaseline(String label) {
        List<SessionPoint> points = getSessionHistory();
        if (points.isEmpty()) {
            return new SessionBaseline(0.0, 0.0, 0.0, 0.0, 0L, Map.of(), Map.of(), Map.of(), System.currentTimeMillis(), label == null || label.isBlank() ? "current" : label);
        }
        double avgFps = averageDouble(points, SessionPoint::averageFps);
        double onePercentLow = averageDouble(points, SessionPoint::onePercentLowFps);
        double avgMspt = averageDouble(points, SessionPoint::msptAvg);
        double msptP95 = averageDouble(points, SessionPoint::msptP95);
        long avgHeapUsedBytes = Math.round(points.stream().mapToLong(SessionPoint::heapUsedBytes).average().orElse(0.0));
        return new SessionBaseline(
                avgFps,
                onePercentLow,
                avgMspt,
                msptP95,
                avgHeapUsedBytes,
                averageMap(points, SessionPoint::cpuEffectivePercentByMod),
                averageMap(points, SessionPoint::gpuEffectivePercentByMod),
                averageMap(points, SessionPoint::memoryEffectiveMbByMod),
                System.currentTimeMillis(),
                label == null || label.isBlank() ? "baseline" : label
        );
    }

    public SessionDelta compareToBaseline(SessionBaseline baseline) {
        if (baseline == null) {
            return null;
        }
        SessionBaseline current = captureBaseline("current");
        return new SessionDelta(
                current.avgFps() - baseline.avgFps(),
                current.onePercentLowFps() - baseline.onePercentLowFps(),
                current.avgMspt() - baseline.avgMspt(),
                current.msptP95() - baseline.msptP95(),
                (current.avgHeapUsedBytes() - baseline.avgHeapUsedBytes()) / (1024.0 * 1024.0),
                subtractMaps(current.cpuEffectivePercentByMod(), baseline.cpuEffectivePercentByMod()),
                subtractMaps(current.gpuEffectivePercentByMod(), baseline.gpuEffectivePercentByMod()),
                subtractMaps(current.memoryEffectiveMbByMod(), baseline.memoryEffectiveMbByMod())
        );
    }

    public boolean importBaselineFromLatestExport() {
        SessionBaseline imported = sessionExporter.importLatestSessionBaseline();
        if (imported == null) {
            return false;
        }
        setBaseline(imported);
        return true;
    }

    private double averageDouble(List<SessionPoint> points, java.util.function.ToDoubleFunction<SessionPoint> extractor) {
        return points.stream().mapToDouble(extractor).average().orElse(0.0);
    }

    private Map<String, Double> averageMap(List<SessionPoint> points, java.util.function.Function<SessionPoint, Map<String, Double>> extractor) {
        Map<String, Double> totals = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SessionPoint point : points) {
            Map<String, Double> values = extractor.apply(point);
            if (values == null) {
                continue;
            }
            values.forEach((key, value) -> {
                totals.merge(key, value == null ? 0.0 : value, Double::sum);
                counts.merge(key, 1, Integer::sum);
            });
        }
        Map<String, Double> averages = new LinkedHashMap<>();
        totals.forEach((key, total) -> averages.put(key, total / Math.max(1, counts.getOrDefault(key, points.size()))));
        return averages;
    }

    private Map<String, Double> subtractMaps(Map<String, Double> current, Map<String, Double> baseline) {
        Map<String, Double> delta = new LinkedHashMap<>();
        current.forEach((key, value) -> delta.put(key, value - baseline.getOrDefault(key, 0.0)));
        baseline.forEach((key, value) -> delta.putIfAbsent(key, -(value == null ? 0.0 : value)));
        return delta;
    }

    public void pinSpike(SpikeCapture spike) {
        pinnedSpike = spike;
        requestSnapshotPublish();
    }

    public void clearPinnedSpike() {
        pinnedSpike = null;
        requestSnapshotPublish();
    }

    public SpikeCapture getPinnedSpike() {
        return pinnedSpike;
    }

    public SpikeDelta compareSpikeToPinned(SpikeCapture spike) {
        if (spike == null || pinnedSpike == null) {
            return null;
        }
        List<String> newTopCpuMods = spike.topCpuMods().stream()
                .filter(mod -> !pinnedSpike.topCpuMods().contains(mod))
                .toList();
        List<String> removedTopCpuMods = pinnedSpike.topCpuMods().stream()
                .filter(mod -> !spike.topCpuMods().contains(mod))
                .toList();
        String bottleneckChange = Objects.equals(pinnedSpike.likelyBottleneck(), spike.likelyBottleneck())
                ? "Same bottleneck"
                : pinnedSpike.likelyBottleneck() + " -> " + spike.likelyBottleneck();
        return new SpikeDelta(
                spike.frameDurationMs() - pinnedSpike.frameDurationMs(),
                spike.stutterScore() - pinnedSpike.stutterScore(),
                newTopCpuMods,
                removedTopCpuMods,
                bottleneckChange
        );
    }

    public List<SaveEvent> getRecentSaves() {
        return List.copyOf(recentSaves);
    }

    public void beginSaveEvent(String type) {
        activeSaveStartedAtMillis = System.nanoTime();
        activeSaveStartedAtEpochMillis = System.currentTimeMillis();
        activeSaveType = type == null || type.isBlank() ? "save" : type;
    }

    public void endSaveEvent() {
        long startedAtNanos = activeSaveStartedAtMillis;
        if (startedAtNanos <= 0L) {
            return;
        }
        long durationMs = Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
        SaveEvent saveEvent = new SaveEvent(activeSaveStartedAtEpochMillis, durationMs, activeSaveType == null || activeSaveType.isBlank() ? "save" : activeSaveType);
        activeSaveStartedAtMillis = 0L;
        activeSaveStartedAtEpochMillis = 0L;
        activeSaveType = "";
        lastCompletedSaveStartedAtMillis = saveEvent.startedAtEpochMillis();
        lastCompletedSaveDurationMillis = saveEvent.durationMs();
        lastCompletedSaveType = saveEvent.type();
        recentSaves.addFirst(saveEvent);
        while (recentSaves.size() > 16) {
            recentSaves.removeLast();
        }
    }

    public boolean isSessionLogging() {
        return sessionLogging;
    }

    public long getSessionLoggingElapsedMillis() {
        if (!sessionLogging || sessionLoggingStartedAtMillis == 0L) {
            return 0L;
        }
        return Math.max(0L, System.currentTimeMillis() - sessionLoggingStartedAtMillis);
    }

    public boolean isSessionRecorded() {
        return sessionRecorded;
    }

    public long getSessionRecordedAgeMillis() {
        if (!sessionRecorded || sessionRecordedAtMillis == 0L) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, System.currentTimeMillis() - sessionRecordedAtMillis);
    }

    public void toggleSessionLogging() {
        if (sessionLogging) {
            sessionLogging = false;
            publishSnapshot(true);
            return;
        }
        clearSessionState();
        sessionLogging = true;
        sessionRecorded = false;
        sessionRecordedAtMillis = 0L;
        sessionMissedSamples = 0;
        sessionMaxSampleGapMillis = 0L;
        sessionExpectedSampleIntervalMillis = 50L;
        sessionLoggingStartedAtMillis = System.currentTimeMillis();
        publishSnapshot(true);
    }

    public void onClientTickEnd(Minecraft client) {
        long selfCostStartedAt = System.nanoTime();
        try {
            WorldScanResult worldScan = sampleWorldData(client);
            latestEntityCounts = worldScan.entityCounts();
            latestChunkCounts = sampleChunkCounts(client);
            latestHotChunks = worldScan.hotChunks();
            latestEntityHotspots = worldScan.entityHotspots();
            latestBlockEntityHotspots = worldScan.blockEntityHotspots();
            String collectorGovernorMode = getCollectorGovernorMode();

            boolean selfProtecting = "self-protect".equals(collectorGovernorMode);
            if ((!selfProtecting && shouldCollectFrameMetrics()) || sessionLogging || screenOpen || (client.level != null && client.level.getGameTime() % 200 == 0)) {
                MemoryProfiler.getInstance().sampleJvm();
            }
            MemoryProfiler.Snapshot memorySnapshot = MemoryProfiler.getInstance().getDetailedSnapshot();
            ThreadLoadProfiler.getInstance().sample();
            SystemMetricsProfiler.getInstance().sample(memorySnapshot, latestEntityCounts, latestChunkCounts);
            NetworkPacketProfiler.getInstance().drainWindow();
            updateConflictTracking(SystemMetricsProfiler.getInstance().getSnapshot());
            latestLockSummaries = buildLockSummaries(SystemMetricsProfiler.getInstance().getSnapshot());

            if (!isCaptureActive()) {
                enforceSessionWindow(client);
                publishSnapshot(false);
                return;
            }

            CpuSamplingProfiler.WindowSnapshot cpuWindow = CpuSamplingProfiler.getInstance().drainWindow();
            Map<String, ModTimingSnapshot> modWindow = ModTimingProfiler.getInstance().drainSnapshot();
            Map<String, RenderPhaseProfiler.PhaseSnapshot> renderWindow = RenderPhaseProfiler.getInstance().drainSnapshot();

            pushWindow(cpuWindows, cpuWindow.samples());
            pushWindow(cpuDetailWindows, cpuWindow.detailsByMod());
            pushWindow(modWindows, modWindow);
            pushWindow(renderWindows, renderWindow);

            boolean shouldSamplePerModMemory = shouldCollectDetailedMetrics() || sessionLogging;
            long memoryCadenceMillis = CollectorMath.computeAdaptiveMemoryCadenceMillis(collectorGovernorMode, screenOpen, sessionLogging);
            boolean allowExpensiveMemoryCollection = (!"light".equals(collectorGovernorMode) && !"self-protect".equals(collectorGovernorMode)) || screenOpen || sessionLogging;
            if (shouldSamplePerModMemory && allowExpensiveMemoryCollection && MemoryProfiler.getInstance().getLastModSampleAgeMillis() > memoryCadenceMillis) {
                MemoryProfiler.getInstance().requestPerModSample();
            }

            latestRuleFindings = ruleEngine.buildRuleFindings(this, memorySnapshot);
            evaluatePerformanceAlerts(client);
            captureSpikeIfNeeded();
            captureStutterJumpSnapshot(client);
            recordSessionPoint();
            enforceSessionWindow(client);
            publishSnapshot(false, cpuWindow.lastSampleAgeMillis());
        } finally {
            FrameTimelineProfiler.getInstance().addSelfCost(System.nanoTime() - selfCostStartedAt);
        }
    }

    public java.util.List<SessionPoint> getSessionHistory() {
        return java.util.List.copyOf(sessionHistory);
    }

    public PerformanceAlert getLatestPerformanceAlert() {
        PerformanceAlert alert = latestPerformanceAlert;
        if (alert == null) {
            return null;
        }
        return System.currentTimeMillis() - alert.triggeredAtEpochMillis() > 6_000L ? null : alert;
    }

    public boolean isPerformanceAlertFlashActive() {
        return performanceAlertFlashUntilMillis > System.currentTimeMillis();
    }

    public java.util.List<String> getJvmTuningAdvisor() {
        return ruleEngine.buildJvmTuningAdvisor(currentSnapshot.memory(), SystemMetricsProfiler.getInstance().getSnapshot());
    }

    public java.util.List<String> getEnabledResourcePackNames() {
        return detectEnabledResourcePacks(Minecraft.getInstance());
    }

    public String getGraphicsPipelineSummary() {
        String shaderPack = detectShaderPackName();
        String sodiumThreads = detectSodiumChunkThreads();
        boolean irisLoaded = FabricLoader.getInstance().isModLoaded("iris");
        boolean sodiumLoaded = FabricLoader.getInstance().isModLoaded("sodium");
        return "Iris " + (irisLoaded ? "on" : "off")
                + " | Sodium " + (sodiumLoaded ? "on" : "off")
                + " | shader " + shaderPack
                + " | chunk threads " + sodiumThreads;
    }

    public String exportSession() {
        return sessionExporter.exportSession(this);
    }

    void beginExport() {
        lastExportStatus = "Exporting session...";
        lastExportDirectory = null;
        lastExportHtmlReport = null;
    }

    String lastExportStatus() {
        return lastExportStatus == null || lastExportStatus.isBlank() ? "Export already in progress..." : lastExportStatus;
    }

    void finishExport(ExportResult result) {
        lastExportDirectory = result.directory();
        lastExportHtmlReport = result.htmlReport();
        lastExportStatus = result.status();
        requestSnapshotPublish();
    }

    ExportResult runSessionExport() {
        return sessionExporter.runSessionExport(this);
    }

    Map<String, Object> buildSessionExportPayload() {
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("generatedAtEpochMillis", System.currentTimeMillis());
        export.put("executiveSummary", buildExecutiveSummary());
        export.put("metadata", buildExportMetadata());
        export.put("captureMode", mode.name());
        export.put("sessionDurationSeconds", ConfigManager.getSessionDurationSeconds());
        export.put("entityCounts", latestEntityCounts);
        export.put("chunkCounts", latestChunkCounts);
        export.put("stutterScore", FrameTimelineProfiler.getInstance().getStutterScore());
        export.put("systemMetrics", SystemMetricsProfiler.getInstance().getSnapshot());
        export.put("topThreadBreakdown", SystemMetricsProfiler.getInstance().getSnapshot().threadLoadPercentByName());
        export.put("topThreadDetails", SystemMetricsProfiler.getInstance().getSnapshot().threadDetailsByName());
        export.put("highestThreadCpuThread", highestCpuThreadName(SystemMetricsProfiler.getInstance().getSnapshot()));
        export.put("stutterScoreTopThread", buildStutterScoreThreadLink());
        export.put("threadLoadHistory", ThreadLoadProfiler.getInstance().getHistory());
        export.put("networkPacketHistory", NetworkPacketProfiler.getInstance().getHistory());
        export.put("networkSpikeBookmarks", NetworkPacketProfiler.getInstance().getSpikeHistory());
        export.put("spikeBookmarks", buildSpikeBookmarks());
        export.put("hotChunks", latestHotChunks);
        export.put("hotChunkHistory", buildHotChunkHistoryExport());
        export.put("entityHotspots", latestEntityHotspots);
        export.put("blockEntityHotspots", latestBlockEntityHotspots);
        export.put("lockSummaries", latestLockSummaries);
        export.put("ruleFindings", latestRuleFindings);
        export.put("stutterJumpSnapshots", new ArrayList<>(stutterJumpSnapshots));
        export.put("exportSummary", buildExportSummary());
        export.put("diagnosis", buildDiagnosis());
        export.put("spikes", new ArrayList<>(spikes));
        export.put("pinnedSpike", pinnedSpike);
        export.put("baseline", sessionBaseline);
        export.put("baselineDelta", compareToBaseline(sessionBaseline));
        export.put("recentSaves", new ArrayList<>(recentSaves));
        export.put("sessionPoints", new ArrayList<>(sessionHistory));
        export.put("frameTimeTimelineMs", FrameTimelineProfiler.getInstance().getOrderedFrameMsHistory());
        export.put("frameTimestampTimelineNs", FrameTimelineProfiler.getInstance().getOrderedFrameTimestampHistory());
        export.put("fpsTimeline", FrameTimelineProfiler.getInstance().getOrderedFpsHistory());
        export.put("profilerSelfCostTimelineMs", FrameTimelineProfiler.getInstance().getOrderedSelfCostMsHistory());
        export.put("chunkPipelineTimeline", buildChunkPipelineTimeline());
        export.put("startupRows", currentSnapshot.startupRows());
        export.put("startupSummary", buildStartupSummary());
        export.put("currentSnapshot", currentSnapshot);
        export.put("attribution", buildAttributionExport());
        export.put("renderPhaseOwners", buildRenderPhaseOwnerSummary());
        export.put("sharedBucketBreakdowns", buildSharedBucketBreakdownExport());
        export.put("chunkWork", ChunkWorkProfiler.getInstance().getSnapshot());
        export.put("entityCosts", EntityCostProfiler.getInstance().getSnapshot());
        export.put("shaderCompiles", ShaderCompilationProfiler.getInstance().getSnapshot());
        export.put("textureUploads", TextureUploadProfiler.getInstance().getSnapshot());
        return export;
    }

    String exportJson(Map<String, Object> export) {
        return EXPORT_GSON.toJson(export);
    }

    String buildSessionHtmlReport(Map<String, Object> export) {
        return buildHtmlReport(export);
    }

    String buildChromeTraceJson() {
        List<Map<String, Object>> events = new ArrayList<>();
        for (SessionPoint point : sessionHistory) {
            appendTraceSpan(events, "Frame", "render", point.capturedAtEpochMillis(), point.frameTimeMs());
            appendTraceSpan(events, "GPU Frame", "gpu", point.capturedAtEpochMillis(), point.gpuFrameTimeMs());
            appendTraceSpan(events, "Client Tick", "client", point.capturedAtEpochMillis(), point.clientTickMs());
            appendTraceSpan(events, "Server Tick", "server", point.capturedAtEpochMillis(), point.serverTickMs());
            if (point.gcPauseDurationMs() > 0L) {
                appendTraceSpan(events, "GC Pause: " + point.gcType(), "jvm", point.capturedAtEpochMillis(), point.gcPauseDurationMs());
            }
        }
        for (SpikeCapture spike : spikes) {
            appendTraceSpan(events, "Spike: " + spike.likelyBottleneck(), "markers", spike.capturedAtEpochMillis(), spike.frameDurationMs());
        }
        for (ShaderCompilationProfiler.CompileEvent event : ShaderCompilationProfiler.getInstance().getSnapshot().recentEvents()) {
            appendTraceSpan(events, "Shader Compile: " + event.label(), "render", event.completedAtEpochMillis(), event.durationNs() / 1_000_000.0);
        }
        for (SaveEvent saveEvent : recentSaves) {
            appendTraceSpan(events, "Save: " + saveEvent.type(), "io", saveEvent.startedAtEpochMillis(), saveEvent.durationMs());
        }
        return EXPORT_GSON.toJson(events);
    }

    void logSessionExport(Path file) {
        taskmanagerClient.LOGGER.info(
                "TaskManager export {} entities total/living/block {}/{}/{} chunks loaded/rendered {}/{} stutterScore {}",
                file.getFileName(),
                latestEntityCounts.totalEntities(),
                latestEntityCounts.livingEntities(),
                latestEntityCounts.blockEntities(),
                latestChunkCounts.loadedChunks(),
                latestChunkCounts.renderedChunks(),
                String.format("%.1f", FrameTimelineProfiler.getInstance().getStutterScore())
        );
    }

    void notifyExportFinished(Minecraft client, ExportResult result) {
        if (client.player == null) {
            return;
        }
        client.player.sendSystemMessage(Component.literal("Task Manager: " + result.status()));
        if (result.htmlReport() != null) {
            Component openReport = Component.literal("[Open Session Report]")
                    .setStyle(Style.EMPTY
                            .withColor(ChatFormatting.AQUA)
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent.OpenFile(result.htmlReport().toAbsolutePath().toString())));
            client.player.sendSystemMessage(openReport);
        }
        if (result.chromeTrace() != null) {
            Component openTrace = Component.literal("[Open Chrome Trace]")
                    .setStyle(Style.EMPTY
                            .withColor(ChatFormatting.GOLD)
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent.OpenFile(result.chromeTrace().toAbsolutePath().toString())));
            client.player.sendSystemMessage(openTrace);
        }
        if (result.directory() != null) {
            Component openFolder = Component.literal("[Open Session Logs Folder]")
                    .setStyle(Style.EMPTY
                            .withColor(ChatFormatting.GREEN)
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent.OpenFile(result.directory().toAbsolutePath().toString())));
            client.player.sendSystemMessage(openFolder);
        }
    }

    private void recordSessionPoint() {
        Map<String, CpuSamplingProfiler.Snapshot> cpu = aggregateCpuWindows();
        Map<String, CpuSamplingProfiler.DetailSnapshot> cpuDetails = aggregateCpuDetailWindows();
        Map<String, ModTimingSnapshot> modInvokes = aggregateModWindows();
        Map<String, RenderPhaseProfiler.PhaseSnapshot> renderPhases = aggregateRenderWindows();
        EffectiveCpuAttribution effectiveCpu = AttributionModelBuilder.buildEffectiveCpuAttribution(cpu, cpuDetails, modInvokes);
        EffectiveGpuAttribution rawGpuAttribution = AttributionModelBuilder.buildEffectiveGpuAttribution(renderPhases, cpu, effectiveCpu, false);
        EffectiveGpuAttribution effectiveGpuAttribution = AttributionModelBuilder.buildEffectiveGpuAttribution(renderPhases, cpu, effectiveCpu, true);
        long totalRenderSamples = cpu.values().stream().mapToLong(CpuSamplingProfiler.Snapshot::renderSamples).sum();
        List<String> topCpu = effectiveCpu.displaySnapshots().entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().totalSamples(), a.getValue().totalSamples()))
                .limit(3)
                .map(Map.Entry::getKey)
                .toList();
        List<String> topGpu = effectiveGpuAttribution.gpuNanosByMod().entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(3)
                .map(Map.Entry::getKey)
                .toList();

        MemoryProfiler.Snapshot memory = MemoryProfiler.getInstance().getDetailedSnapshot();
        Map<String, Long> rawMemoryMods = MemoryProfiler.getInstance().getModMemoryBytes();
        EffectiveMemoryAttribution effectiveMemory = AttributionModelBuilder.buildEffectiveMemoryAttribution(rawMemoryMods);
        double usedHeapMb = memory.heapUsedBytes() / (1024.0 * 1024.0);
        double allocatedHeapMb = memory.heapCommittedBytes() / (1024.0 * 1024.0);
        SessionPoint previous = sessionHistory.peekLast();
        boolean isGcEvent = previous != null && (previous.usedHeapMb() - usedHeapMb) >= 500.0;

        long drawCalls = renderPhases.values().stream().mapToLong(RenderPhaseProfiler.PhaseSnapshot::gpuCalls).sum();
        long bufferUpdates = renderPhases.values().stream().mapToLong(RenderPhaseProfiler.PhaseSnapshot::cpuCalls).sum();
        double clientTickMs = TickProfiler.getInstance().getAverageClientTickNs() / 1_000_000.0;
        double serverTickMs = TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0;
        double millisecondsPerTick = Math.max(clientTickMs, serverTickMs);
        double msptP95 = TickProfiler.getInstance().getAverageServerTickNs() > 0 ? TickProfiler.getInstance().getServerTickP95Ns() / 1_000_000.0 : 0.0;
        double msptP99 = TickProfiler.getInstance().getAverageServerTickNs() > 0 ? TickProfiler.getInstance().getServerTickP99Ns() / 1_000_000.0 : 0.0;
        FrameTimelineProfiler frameTimeline = FrameTimelineProfiler.getInstance();
        double frameTimeMs = frameTimeline.getLatestFrameNs() / 1_000_000.0;
        double frameTimeP95Ms = frameTimeline.getPercentileFrameNs(0.95) / 1_000_000.0;
        double frameTimeP99Ms = frameTimeline.getPercentileFrameNs(0.99) / 1_000_000.0;
        double frameBuildTimeMs = renderPhases.values().stream().mapToLong(RenderPhaseProfiler.PhaseSnapshot::cpuNanos).sum() / 1_000_000.0;
        double gpuFrameTimeMs = renderPhases.values().stream().mapToLong(RenderPhaseProfiler.PhaseSnapshot::gpuNanos).sum() / 1_000_000.0;
        double gpuFrameTimeP95Ms = percentileSessionGpuFrameTime(gpuFrameTimeMs);
        SystemMetricsProfiler.Snapshot systemSnapshot = SystemMetricsProfiler.getInstance().getSnapshot();
        List<NetworkPacketProfiler.Snapshot> packetHistory = NetworkPacketProfiler.getInstance().getHistory();
        NetworkPacketProfiler.Snapshot networkSnapshot = packetHistory.isEmpty()
                ? new NetworkPacketProfiler.Snapshot(0L, 0L, Map.of(), Map.of(), Map.of(), Map.of())
                : packetHistory.get(packetHistory.size() - 1);
        HotChunkSnapshot topHotChunk = latestHotChunks.isEmpty() ? null : latestHotChunks.getFirst();
        Map<String, Integer> cpuThreadCountsByMod = new LinkedHashMap<>();
        Map<String, Integer> gpuThreadCountsByMod = new LinkedHashMap<>();
        Map<String, Integer> memoryClassCountsByMod = new LinkedHashMap<>();
        cpuDetails.forEach((modId, detail) -> {
            cpuThreadCountsByMod.put(modId, detail.sampledThreadCount());
            if (cpu.containsKey(modId) && cpu.get(modId).renderSamples() > 0) {
                gpuThreadCountsByMod.put(modId, detail.sampledThreadCount());
            }
        });
        MemoryProfiler.getInstance().getTopClassesByMod().forEach((modId, classes) -> memoryClassCountsByMod.put(modId, classes.size()));
        Map<String, Double> cpuRawPercentByMod = buildCpuPercentByMod(cpu);
        Map<String, Double> cpuEffectivePercentByMod = buildCpuPercentByMod(effectiveCpu.displaySnapshots());
        Map<String, Double> gpuRawPercentByMod = buildGpuPercentByMod(rawGpuAttribution);
        Map<String, Double> gpuEffectivePercentByMod = buildGpuPercentByMod(effectiveGpuAttribution);
        Map<String, Double> memoryRawMbByMod = buildMemoryMbByMod(rawMemoryMods);
        Map<String, Double> memoryEffectiveMbByMod = buildMemoryMbByMod(effectiveMemory.displayBytes());
        Map<String, String> cpuConfidenceByMod = buildCpuConfidenceByMod(cpu, effectiveCpu, cpuDetails);
        Map<String, String> gpuConfidenceByMod = buildGpuConfidenceByMod(rawGpuAttribution, effectiveGpuAttribution);
        Map<String, String> memoryConfidenceByMod = buildMemoryConfidenceByMod(rawMemoryMods, effectiveMemory, MemoryProfiler.getInstance().getLastModSampleAgeMillis());
        Map<String, String> cpuProvenanceByMod = buildCpuProvenanceByMod(cpu, effectiveCpu, cpuDetails);
        Map<String, String> gpuProvenanceByMod = buildGpuProvenanceByMod(rawGpuAttribution, effectiveGpuAttribution);
        Map<String, String> memoryProvenanceByMod = buildMemoryProvenanceByMod(rawMemoryMods, effectiveMemory, MemoryProfiler.getInstance().getLastModSampleAgeMillis());

        long capturedAtMillis = System.currentTimeMillis();
        SessionPoint previousPoint = sessionHistory.peekLast();
        long sampleIntervalMs = previousPoint == null ? sessionExpectedSampleIntervalMillis : Math.max(0L, capturedAtMillis - previousPoint.capturedAtEpochMillis());
        int missedSamplesSincePrevious = computeMissedSamples(previousPoint == null ? 0L : previousPoint.capturedAtEpochMillis(), capturedAtMillis, (int) sessionExpectedSampleIntervalMillis);
        sessionMissedSamples += missedSamplesSincePrevious;
        sessionMaxSampleGapMillis = Math.max(sessionMaxSampleGapMillis, sampleIntervalMs);
        boolean saveEventComplete = lastCompletedSaveDurationMillis > 0L
                && lastCompletedSaveStartedAtMillis > 0L
                && (previousPoint == null || lastCompletedSaveStartedAtMillis > previousPoint.capturedAtEpochMillis());

        sessionHistory.addLast(new SessionPoint(
                capturedAtMillis,
                sampleIntervalMs,
                missedSamplesSincePrevious,
                frameTimeline.getCurrentFps(),
                frameTimeline.getAverageFps(),
                frameTimeline.getOnePercentLowFps(),
                frameTimeline.getPointOnePercentLowFps(),
                frameTimeMs,
                frameTimeline.getAverageFrameNs() / 1_000_000.0,
                frameTimeP95Ms,
                frameTimeP99Ms,
                frameTimeline.getFrameStdDevMs(),
                frameTimeline.getFrameVarianceMs(),
                frameTimeline.getFrameTimeHistogram(),
                frameBuildTimeMs,
                gpuFrameTimeMs,
                gpuFrameTimeP95Ms,
                millisecondsPerTick,
                millisecondsPerTick,
                millisecondsPerTick,
                msptP95,
                msptP99,
                clientTickMs,
                serverTickMs,
                frameTimeline.getStutterScore(),
                usedHeapMb,
                allocatedHeapMb,
                memory.heapUsedBytes(),
                memory.heapCommittedBytes(),
                isGcEvent,
                memory.gcPauseDurationMs(),
                memory.gcTimeMillis(),
                memory.gcPauseDurationMs(),
                memory.gcCount(),
                memory.gcType(),
                cpu.values().stream().mapToLong(CpuSamplingProfiler.Snapshot::totalSamples).sum(),
                totalRenderSamples,
                drawCalls,
                bufferUpdates,
                systemSnapshot.mouseInputLatencyMs(),
                highestCpuThreadName(systemSnapshot),
                sessionParallelismFlag(systemSnapshot, frameTimeline.getStutterScore()),
                systemSnapshot.chunksGenerating(),
                systemSnapshot.chunksMeshing(),
                systemSnapshot.chunksUploading(),
                systemSnapshot.lightsUpdatePending(),
                systemSnapshot.chunkMeshesRebuilt(),
                systemSnapshot.chunkMeshesUploaded(),
                systemSnapshot.playerSpeedBlocksPerSecond(),
                systemSnapshot.chunksEnteredLastSecond(),
                systemSnapshot.distanceTravelledBlocks(),
                systemSnapshot.vramUsedBytes(),
                systemSnapshot.vramTotalBytes(),
                systemSnapshot.textureUploadRate(),
                latestEntityCounts,
                latestChunkCounts,
                systemSnapshot,
                networkSnapshot,
                topHotChunk,
                List.copyOf(latestEntityHotspots),
                List.copyOf(latestBlockEntityHotspots),
                List.copyOf(latestLockSummaries),
                List.copyOf(latestRuleFindings),
                topCpu,
                topGpu,
                cpuThreadCountsByMod,
                gpuThreadCountsByMod,
                memoryClassCountsByMod,
                cpuRawPercentByMod,
                cpuEffectivePercentByMod,
                gpuRawPercentByMod,
                gpuEffectivePercentByMod,
                memoryRawMbByMod,
                memoryEffectiveMbByMod,
                cpuConfidenceByMod,
                gpuConfidenceByMod,
                memoryConfidenceByMod,
                cpuProvenanceByMod,
                gpuProvenanceByMod,
                memoryProvenanceByMod,
                getCollectorGovernorMode(),
                frameTimeline.getSelfCostAvgMs(),
                saveEventComplete,
                saveEventComplete ? lastCompletedSaveDurationMillis : 0L,
                saveEventComplete ? lastCompletedSaveType : ""
        ));
    }

    private List<Map<String, Object>> buildSpikeBookmarks() {
        List<SessionPoint> points = new ArrayList<>(sessionHistory);
        List<Map<String, Object>> bookmarks = new ArrayList<>();
        for (SpikeCapture spike : spikes) {
            int nearestIndex = -1;
            long bestDistance = Long.MAX_VALUE;
            for (int i = 0; i < points.size(); i++) {
                long distance = Math.abs(points.get(i).capturedAtEpochMillis() - spike.capturedAtEpochMillis());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    nearestIndex = i;
                }
            }
            Map<String, Object> bookmark = new LinkedHashMap<>();
            bookmark.put("capturedAtEpochMillis", spike.capturedAtEpochMillis());
            bookmark.put("frameDurationMs", spike.frameDurationMs());
            bookmark.put("stutterScore", spike.stutterScore());
            bookmark.put("nearestSessionPointIndex", nearestIndex);
            bookmark.put("likelyBottleneck", spike.likelyBottleneck());
            bookmark.put("topCpuMods", spike.topCpuMods());
            bookmark.put("topRenderPhases", spike.topRenderPhases());
            bookmark.put("topThreads", spike.topThreads());
            bookmark.put("findings", spike.findings());
            bookmarks.add(bookmark);
        }
        return bookmarks;
    }

    private Map<String, Object> buildExecutiveSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (sessionHistory.isEmpty()) {
            summary.put("whatChanged", "No session samples were captured.");
            summary.put("when", "No recorded interval.");
            summary.put("likelyWhy", buildDiagnosis());
            return summary;
        }

        SessionPoint first = sessionHistory.peekFirst();
        SessionPoint last = sessionHistory.peekLast();
        SpikeCapture worstSpike = spikes.stream().max((a, b) -> Double.compare(a.frameDurationMs(), b.frameDurationMs())).orElse(null);
        RuleFinding highestFinding = latestRuleFindings.stream()
                .sorted((a, b) -> Integer.compare(severityRank(b.severity()), severityRank(a.severity())))
                .findFirst()
                .orElse(null);

        double targetFrameMs = ConfigManager.getFrameBudgetTargetFrameMs();
        long frameBudgetBreaches = countFrameBudgetBreaches();
        String whatChanged = worstSpike != null
                ? "Frame pacing shifted from " + String.format(Locale.ROOT, "%.1f", first.frameTimeMs()) + " ms to a worst spike of " + String.format(Locale.ROOT, "%.1f", worstSpike.frameDurationMs()) + " ms against a " + String.format(Locale.ROOT, "%.1f", targetFrameMs) + " ms budget."
                : "Session stayed between " + String.format(Locale.ROOT, "%.1f", first.frameTimeMs()) + " ms and " + String.format(Locale.ROOT, "%.1f", last.frameTimeMs()) + " ms frame time against a " + String.format(Locale.ROOT, "%.1f", targetFrameMs) + " ms budget.";
        String when = "From " + formatExportTimestamp(first.capturedAtEpochMillis()) + " to " + formatExportTimestamp(last.capturedAtEpochMillis()) + " (" + sessionHistory.size() + " samples, missed " + sessionMissedSamples + ", " + frameBudgetBreaches + " budget breaches).";
        String likelyWhy = highestFinding != null
                ? highestFinding.category() + ": " + highestFinding.message()
                : (worstSpike != null ? worstSpike.likelyBottleneck() : buildDiagnosis());

        summary.put("whatChanged", whatChanged);
        summary.put("when", when);
        summary.put("likelyWhy", likelyWhy);
        return summary;
    }

    private Map<String, Object> buildExportSummary() {
        SystemMetricsProfiler.Snapshot system = SystemMetricsProfiler.getInstance().getSnapshot();
        Map<String, Object> summary = new LinkedHashMap<>();
        SpikeCapture worstSpike = spikes.stream().max((a, b) -> Double.compare(a.frameDurationMs(), b.frameDurationMs())).orElse(null);
        summary.put("worstSpike", worstSpike);
        summary.put("likelyBottleneck", currentBottleneckLabel());
        summary.put("topHotThreads", system.threadDetailsByName());
        summary.put("topBlockedThreads", topBlockedThreadSummaries(system));
        summary.put("stutterScoreTopThread", buildStutterScoreThreadLink());
        summary.put("gcAnomalies", sessionHistory.stream().filter(SessionPoint::isGcEvent).count());
        summary.put("thermalState", thermalStateLabel(system));
        summary.put("pagingState", system.vramPagingActive() ? "VRAM paging detected" : "No VRAM paging detected");
        summary.put("topHotChunk", latestHotChunks.isEmpty() ? null : latestHotChunks.getFirst());
        summary.put("topEntityHotspots", latestEntityHotspots);
        summary.put("topBlockEntityHotspots", latestBlockEntityHotspots);
        summary.put("conflictEdges", latestConflictEdges);
        summary.put("lockSummaries", latestLockSummaries);
        summary.put("networkSpikeBookmarks", NetworkPacketProfiler.getInstance().getSpikeHistory());
        summary.put("ruleFindingsBySeverity", buildRuleFindingSeverityBreakdown());
        summary.put("parallelismEfficiency", system.parallelismEfficiency());
        summary.put("serverThreadWaitMs", system.serverThreadWaitMs());
        summary.put("serverThreadBlockedMs", system.serverThreadBlockedMs());
        summary.put("activeIdleWorkerRatio", String.format(Locale.ROOT, "%d/%d (%.2f)", system.activeWorkers(), system.idleWorkers(), system.activeToIdleWorkerRatio()));
        summary.put("offHeapAllocationRate", system.offHeapAllocationRateBytesPerSecond());
        summary.put("currentBiome", system.currentBiome());
        summary.put("lightUpdateQueue", system.lightUpdateQueue());
        summary.put("maxEntitiesInHotChunk", system.maxEntitiesInHotChunk());
        summary.put("sensorErrors", system.sensorErrorCode());
        summary.put("jvmTuningAdvisor", buildJvmTuningAdvisor(currentSnapshot.memory(), system));
        summary.put("redFlagThresholds", buildRedFlagThresholds());
        double frameAvg = FrameTimelineProfiler.getInstance().getAverageFrameNs() / 1_000_000.0;
        double frameP95 = FrameTimelineProfiler.getInstance().getPercentileFrameNs(0.95) / 1_000_000.0;
        double frameP99 = FrameTimelineProfiler.getInstance().getPercentileFrameNs(0.99) / 1_000_000.0;
        double gpuFrameMs = aggregateRenderWindows().values().stream().mapToLong(RenderPhaseProfiler.PhaseSnapshot::gpuNanos).sum() / 1_000_000.0;
        double msptAvg = TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0;
        double msptP95 = TickProfiler.getInstance().getServerTickP95Ns() / 1_000_000.0;
        double msptP99 = TickProfiler.getInstance().getServerTickP99Ns() / 1_000_000.0;
        summary.put("frameTimeAvgMs", frameAvg);
        summary.put("frameTimeP95Ms", frameP95);
        summary.put("frameTimeP99Ms", frameP99);
        summary.put("gpuFrameTimeMs", gpuFrameMs);
        summary.put("gpuFrameTimeP95Ms", percentileSessionGpuFrameTime(gpuFrameMs));
        summary.put("msptAvg", msptAvg);
        summary.put("msptP95", msptP95);
        summary.put("msptP99", msptP99);
        summary.put("frameTimeHistogram", FrameTimelineProfiler.getInstance().getFrameTimeHistogram());
        summary.put("frameBudgetTargetFps", ConfigManager.getFrameBudgetTargetFps());
        summary.put("frameBudgetTargetMs", ConfigManager.getFrameBudgetTargetFrameMs());
        summary.put("frameBudgetBreaches", countFrameBudgetBreaches());
        summary.put("frameBudgetBreachRate", sessionHistory.isEmpty() ? 0.0 : countFrameBudgetBreaches() * 100.0 / sessionHistory.size());
        summary.put("vramPeakMb", peakSessionValue(SessionPoint::vramUsedBytes) / (1024.0 * 1024.0));
        summary.put("networkInboundPeakBytesPerSecond", peakSystemMetricValue(SystemMetricsProfiler.Snapshot::bytesReceivedPerSecond));
        summary.put("networkOutboundPeakBytesPerSecond", peakSystemMetricValue(SystemMetricsProfiler.Snapshot::bytesSentPerSecond));
        summary.put("diskReadPeakBytesPerSecond", peakSystemMetricValue(SystemMetricsProfiler.Snapshot::diskReadBytesPerSecond));
        summary.put("diskWritePeakBytesPerSecond", peakSystemMetricValue(SystemMetricsProfiler.Snapshot::diskWriteBytesPerSecond));
        summary.put("chunkActivityPeak", peakChunkActivity());
        summary.put("cpuTemperatureReason", system.cpuTemperatureUnavailableReason());
        summary.put("sensorDiagnostics", buildSensorDiagnostics(system));
        summary.put("worstFrame", Map.of("frameTimeMs", Math.max(frameP99, currentSnapshot.spikes().stream().mapToDouble(SpikeCapture::frameDurationMs).max().orElse(frameP99)), "avgFrameTimeMs", frameAvg, "p95FrameTimeMs", frameP95, "p99FrameTimeMs", frameP99));
        summary.put("worstMsptSpike", Map.of("msptAvg", msptAvg, "msptP95", msptP95, "msptP99", msptP99));
        summary.put("topCpuMods", buildTopCpuModSummary());
        summary.put("topGpuMods", buildTopGpuModSummary());
        summary.put("topMemoryMods", buildTopMemoryModSummary());
        summary.put("hotChunkSummary", latestHotChunks.isEmpty() ? null : latestHotChunks.getFirst());
        summary.put("blockEntityClasses", latestBlockEntityHotspots);
        summary.put("startupSummary", buildStartupSummary());
        summary.put("sessionSampleIntervalMs", sessionExpectedSampleIntervalMillis);
        summary.put("observedSessionSampleIntervalMs", computeObservedSessionSampleIntervalMs());
        summary.put("missedSessionSamples", sessionMissedSamples);
        summary.put("maxSessionSampleGapMs", sessionMaxSampleGapMillis);
        summary.put("metadata", buildExportMetadata());
        return summary;
    }

    private Map<String, Object> buildStartupSummary() {
        Map<String, Object> startup = new LinkedHashMap<>();
        List<StartupTimingProfiler.StartupRow> rows = currentSnapshot.startupRows();
        startup.put("spanMs", Math.max(0.0, (currentSnapshot.startupLast() - currentSnapshot.startupFirst()) / 1_000_000.0));
        startup.put("mods", rows.size());
        startup.put("measuredEntrypoints", rows.stream().anyMatch(StartupTimingProfiler.StartupRow::measuredEntrypoints));
        startup.put("measuredRows", rows.stream().filter(StartupTimingProfiler.StartupRow::measuredEntrypoints).count());
        startup.put("fallbackRows", rows.stream().filter(row -> !row.measuredEntrypoints()).count());
        startup.put("topMods", rows.stream().limit(6).map(row -> Map.of(
                "mod", row.modId(),
                "activeMs", row.activeNanos() / 1_000_000.0,
                "entrypoints", row.entrypoints(),
                "registrations", row.registrations(),
                "stage", row.stageSummary(),
                "definition", row.definitionSummary()
        )).toList());
        startup.put("slowestMods", rows.stream().limit(10).map(row -> Map.of(
                "mod", row.modId(),
                "displayName", FabricLoader.getInstance().getModContainer(row.modId()).map(mod -> mod.getMetadata().getName()).orElse(row.modId()),
                "activeMs", row.activeNanos() / 1_000_000.0,
                "startMs", (row.first() - currentSnapshot.startupFirst()) / 1_000_000.0,
                "endMs", (row.last() - currentSnapshot.startupFirst()) / 1_000_000.0,
                "entrypoints", row.entrypoints(),
                "registrations", row.registrations(),
                "measured", row.measuredEntrypoints(),
                "stage", row.stageSummary(),
                "definition", row.definitionSummary()
        )).toList());
        startup.put("entrypointHeavyMods", rows.stream().sorted((a, b) -> Integer.compare(b.entrypoints(), a.entrypoints())).limit(5).map(row -> Map.of(
                "mod", row.modId(),
                "entrypoints", row.entrypoints(),
                "activeMs", row.activeNanos() / 1_000_000.0
        )).toList());
        startup.put("registrationHeavyMods", rows.stream().sorted((a, b) -> Integer.compare(b.registrations(), a.registrations())).limit(5).map(row -> Map.of(
                "mod", row.modId(),
                "registrations", row.registrations(),
                "activeMs", row.activeNanos() / 1_000_000.0
        )).toList());
        return startup;
    }

    private ExportMetadata buildExportMetadata() {
        Minecraft client = Minecraft.getInstance();
        String taskManagerVersion = FabricLoader.getInstance().getModContainer("taskmanager")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        String minecraftVersion = FabricLoader.getInstance().getModContainer("minecraft")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        String loaderVersion = FabricLoader.getInstance().getModContainer("fabricloader")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        String cpuInfo = HardwareInfoResolver.getCpuDisplayName();
        SystemMetricsProfiler.Snapshot system = SystemMetricsProfiler.getInstance().getSnapshot();
        String gpuInfo = (system.gpuVendor() == null || system.gpuVendor().isBlank() ? "Unknown GPU" : system.gpuVendor())
                + " | " + (system.gpuRenderer() == null || system.gpuRenderer().isBlank() ? "Unknown renderer" : system.gpuRenderer());
        int guiScale = client != null ? client.options.guiScale().get() : -1;
        java.util.List<String> modList = FabricLoader.getInstance().getAllMods().stream()
                .map(mod -> mod.getMetadata().getId() + "@" + mod.getMetadata().getVersion().getFriendlyString())
                .sorted()
                .toList();
        FrameTimelineProfiler frames = FrameTimelineProfiler.getInstance();
        return new ExportMetadata(
                taskManagerVersion,
                minecraftVersion,
                loaderVersion,
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.version", "unknown"),
                System.getProperty("java.version", "unknown"),
                cpuInfo,
                gpuInfo,
                guiScale,
                mode.name(),
                ConfigManager.getHudTriggerMode().name(),
                ConfigManager.getFrameBudgetTargetFps(),
                ConfigManager.getFrameBudgetTargetFrameMs(),
                modList,
                computeConsistentFps(frames),
                frames.getOnePercentLowFps(),
                frames.getPointOnePercentLowFps(),
                sessionExpectedSampleIntervalMillis,
                computeObservedSessionSampleIntervalMs(),
                sessionMissedSamples,
                sessionMaxSampleGapMillis,
                ManagementFactory.getRuntimeMXBean().getInputArguments(),
                buildMinecraftSettings(client),
                buildGraphicsModSettings(client)
        );
    }

    private double computeConsistentFps(FrameTimelineProfiler frames) {
        double[] history = frames.getOrderedFpsHistory();
        if (history.length == 0) return 0.0;
        double[] copy = java.util.Arrays.copyOf(history, history.length);
        java.util.Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    static boolean shouldPublishSnapshot(boolean force, long nowMillis, long lastPublishedMillis, int snapshotDelayMs) {
        return force || lastPublishedMillis == 0L || nowMillis - lastPublishedMillis >= snapshotDelayMs;
    }

    static int computeMissedSamples(long previousSampleAtMillis, long currentSampleAtMillis, int expectedIntervalMs) {
        if (previousSampleAtMillis <= 0L || currentSampleAtMillis <= previousSampleAtMillis || expectedIntervalMs <= 0) {
            return 0;
        }
        long delta = currentSampleAtMillis - previousSampleAtMillis;
        return Math.max(0, (int) Math.round((double) delta / expectedIntervalMs) - 1);
    }

    static long computeObservedSampleIntervalMs(long[] capturedAtMillis, int fallbackIntervalMs) {
        if (capturedAtMillis.length < 2) {
            return fallbackIntervalMs;
        }
        long totalInterval = 0L;
        for (int i = 1; i < capturedAtMillis.length; i++) {
            totalInterval += Math.max(0L, capturedAtMillis[i] - capturedAtMillis[i - 1]);
        }
        return Math.max(1L, totalInterval / (capturedAtMillis.length - 1));
    }

    private long computeObservedSessionSampleIntervalMs() {
        long[] capturedAtMillis = sessionHistory.stream().mapToLong(SessionPoint::capturedAtEpochMillis).toArray();
        return computeObservedSampleIntervalMs(capturedAtMillis, (int) sessionExpectedSampleIntervalMillis);
    }

    private Map<String, Object> buildMinecraftSettings(Minecraft client) {
        Map<String, Object> settings = new LinkedHashMap<>();
        if (client == null) return settings;
        settings.put("renderDistance", client.options.renderDistance().get());
        settings.put("simulationDistance", client.options.simulationDistance().get());
        settings.put("entityDistanceScale", client.options.entityDistanceScaling().get());
        settings.put("guiScale", client.options.guiScale().get());
        settings.put("vsync", readOptionValue(client.options, "getEnableVsync"));
        settings.put("maxFramerate", readOptionValue(client.options, "getMaxFps"));
        return settings;
    }

    private Map<String, Object> buildGraphicsModSettings(Minecraft client) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("irisDetected", FabricLoader.getInstance().isModLoaded("iris"));
        settings.put("sodiumDetected", FabricLoader.getInstance().isModLoaded("sodium"));
        settings.put("shaderPack", detectShaderPackName());
        settings.put("chunkUpdateThreads", detectSodiumChunkThreads());
        settings.put("resourcePacks", detectEnabledResourcePacks(client));
        return settings;
    }

    private Object readOptionValue(Object options, String methodName) {
        if (options == null) return "unavailable";
        try {
            Object option = options.getClass().getMethod(methodName).invoke(options);
            if (option == null) return "unavailable";
            try {
                return option.getClass().getMethod("getValue").invoke(option);
            } catch (ReflectiveOperationException ignored) {
                return option.toString();
            }
        } catch (ReflectiveOperationException e) {
            return "unavailable";
        }
    }

    private String detectShaderPackName() {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        for (Path candidate : List.of(gameDir.resolve("config/iris.properties"), gameDir.resolve("optionsshaders.txt"))) {
            String value = readConfigValue(candidate, "shaderPack", "shaderPackName", "shader_pack", "packName");
            if (value != null) {
                return value;
            }
        }
        return "best-effort unavailable";
    }

    private String detectSodiumChunkThreads() {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        for (Path candidate : List.of(gameDir.resolve("config/sodium-options.json"), gameDir.resolve("config/sodium-options.json5"))) {
            String value = readConfigValue(candidate, "chunkBuilderThreads", "chunk_build_threads", "chunkUpdateThreads");
            if (value != null) {
                return value;
            }
        }
        return "runtime worker-derived";
    }

    private String readConfigValue(Path path, String... keys) {
        try {
            if (!Files.exists(path)) {
                return null;
            }
            String content = Files.readString(path);
            for (String key : keys) {
                Pattern pattern = Pattern.compile("(?:\"" + Pattern.quote(key) + "\"|" + Pattern.quote(key) + ")\s*[:=]\s*[\"]?([^\"\r\n,}]+)");
                Matcher matcher = pattern.matcher(content);
                if (matcher.find()) {
                    String value = matcher.group(1).trim();
                    if (!value.isBlank() && !"null".equalsIgnoreCase(value)) {
                        return value;
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private List<String> detectEnabledResourcePacks(Minecraft client) {
        if (client == null) {
            return List.of();
        }
        try {
            Object manager = client.getClass().getMethod("getResourcePackManager").invoke(client);
            if (manager == null) {
                return List.of();
            }
            Object enabledProfiles = manager.getClass().getMethod("getEnabledProfiles").invoke(manager);
            if (!(enabledProfiles instanceof Iterable<?> iterable)) {
                return List.of();
            }
            List<String> packs = new ArrayList<>();
            for (Object profile : iterable) {
                if (profile == null) {
                    continue;
                }
                String name = extractPackDisplayName(profile);
                if (name != null && !name.isBlank()) {
                    packs.add(name);
                }
            }
            return packs;
        } catch (ReflectiveOperationException ignored) {
            return List.of();
        }
    }

    void requestSnapshotPublish() {
        publishSnapshot();
    }

    private String extractPackDisplayName(Object profile) {
        for (String methodName : List.of("getDisplayName", "getName", "getId")) {
            try {
                Object value = profile.getClass().getMethod(methodName).invoke(profile);
                if (value instanceof Component text) {
                    return text.getString();
                }
                if (value != null) {
                    return value.toString();
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private List<String> buildJvmTuningAdvisor(MemoryProfiler.Snapshot memory, SystemMetricsProfiler.Snapshot system) {
        return ruleEngine.buildJvmTuningAdvisor(memory, system);
    }

    private String firstJvmArgValue(List<String> args, String prefix) {
        for (String arg : args) {
            if (arg != null && arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
        }
        return null;
    }

    private void evaluatePerformanceAlerts(Minecraft client) {
        if (!ConfigManager.isPerformanceAlertsEnabled()) {
            latestPerformanceAlert = null;
            performanceAlertFlashUntilMillis = 0L;
            frameAlertConsecutiveBreaches = 0;
            serverAlertConsecutiveBreaches = 0;
            return;
        }

        long now = System.currentTimeMillis();
        double latestFrameMs = FrameTimelineProfiler.getInstance().getLatestFrameNs() / 1_000_000.0;
        double serverTickMs = TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0;
        int required = ConfigManager.getPerformanceAlertConsecutiveTicks();
        frameAlertConsecutiveBreaches = latestFrameMs >= ConfigManager.getPerformanceAlertFrameThresholdMs() ? frameAlertConsecutiveBreaches + 1 : 0;
        serverAlertConsecutiveBreaches = serverTickMs >= ConfigManager.getPerformanceAlertServerThresholdMs() ? serverAlertConsecutiveBreaches + 1 : 0;

        PerformanceAlert nextAlert = null;
        if (serverAlertConsecutiveBreaches >= required) {
            nextAlert = createPerformanceAlert("server-mspt", "Server MSPT", serverTickMs, ConfigManager.getPerformanceAlertServerThresholdMs(), serverAlertConsecutiveBreaches);
        }
        if (frameAlertConsecutiveBreaches >= required) {
            PerformanceAlert frameAlert = createPerformanceAlert("frame-ms", "Frame Time", latestFrameMs, ConfigManager.getPerformanceAlertFrameThresholdMs(), frameAlertConsecutiveBreaches);
            if (nextAlert == null || frameAlert.value() > nextAlert.value()) {
                nextAlert = frameAlert;
            }
        }

        if (nextAlert == null) {
            if (latestPerformanceAlert != null && now - latestPerformanceAlert.triggeredAtEpochMillis() > 6_000L) {
                latestPerformanceAlert = null;
            }
            return;
        }

        long lastAlertAt = lastPerformanceAlertAtMillis.getOrDefault(nextAlert.key(), 0L);
        if (now - lastAlertAt < 8_000L) {
            return;
        }
        lastPerformanceAlertAtMillis.put(nextAlert.key(), now);
        latestPerformanceAlert = nextAlert;
        performanceAlertFlashUntilMillis = now + 2_500L;
        if (ConfigManager.isPerformanceAlertChatEnabled() && client != null && client.gui != null) {
            client.gui.getChat().addClientSystemMessage(Component.literal("[Task Manager] " + nextAlert.message()).withStyle(ChatFormatting.YELLOW));
        }
    }

    private PerformanceAlert createPerformanceAlert(String key, String label, double value, double threshold, int consecutiveBreaches) {
        double ratio = threshold <= 0.0 ? 0.0 : value / threshold;
        String severity = ratio >= 1.75 ? "critical" : ratio >= 1.25 ? "warning" : "info";
        String message = label + " stayed above " + String.format(Locale.ROOT, "%.1f", threshold)
                + " ms for " + consecutiveBreaches + " consecutive ticks (now " + String.format(Locale.ROOT, "%.1f", value) + " ms).";
        return new PerformanceAlert(key, label, message, severity, System.currentTimeMillis(), value, threshold, consecutiveBreaches);
    }

    private List<Map<String, Object>> buildChunkPipelineTimeline() {
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (SessionPoint point : sessionHistory) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("capturedAtEpochMillis", point.capturedAtEpochMillis());
            row.put("chunksGenerating", point.chunksGenerating());
            row.put("chunksMeshing", point.chunksMeshing());
            row.put("chunksUploading", point.chunksUploading());
            row.put("lightsUpdatePending", point.lightsUpdatePending());
            row.put("chunkMeshesRebuilt", point.chunkMeshesRebuilt());
            row.put("chunkMeshesUploaded", point.chunkMeshesUploaded());
            timeline.add(row);
        }
        return timeline;
    }

    private Map<String, Object> buildSensorDiagnostics(SystemMetricsProfiler.Snapshot system) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("activeSource", system.sensorSource());
        diagnostics.put("status", system.cpuSensorStatus());
        diagnostics.put("lastError", system.sensorErrorCode());
        diagnostics.put("cpuTemperatureReason", system.cpuTemperatureUnavailableReason());
        return diagnostics;
    }

    private Map<String, Double> buildCpuPercentByMod(Map<String, CpuSamplingProfiler.Snapshot> snapshots) {
        LinkedHashMap<String, Double> result = new LinkedHashMap<>();
        double total = Math.max(1L, totalCpuMetric(snapshots));
        snapshots.entrySet().stream()
                .sorted((a, b) -> Long.compare(cpuMetricValue(b.getValue()), cpuMetricValue(a.getValue())))
                .forEach(entry -> result.put(entry.getKey(), cpuMetricValue(entry.getValue()) * 100.0 / total));
        return result;
    }

    private long cpuMetricValue(CpuSamplingProfiler.Snapshot snapshot) {
        if (snapshot == null) {
            return 0L;
        }
        return snapshot.totalCpuNanos() > 0L ? snapshot.totalCpuNanos() : snapshot.totalSamples();
    }

    private long totalCpuMetric(Map<String, CpuSamplingProfiler.Snapshot> snapshots) {
        long totalCpuNanos = snapshots.values().stream().mapToLong(CpuSamplingProfiler.Snapshot::totalCpuNanos).sum();
        if (totalCpuNanos > 0L) {
            return totalCpuNanos;
        }
        return snapshots.values().stream().mapToLong(CpuSamplingProfiler.Snapshot::totalSamples).sum();
    }

    private Map<String, Double> buildGpuPercentByMod(EffectiveGpuAttribution attribution) {
        LinkedHashMap<String, Double> result = new LinkedHashMap<>();
        double total = Math.max(1L, attribution.totalGpuNanos());
        attribution.gpuNanosByMod().entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(entry -> result.put(entry.getKey(), entry.getValue() * 100.0 / total));
        return result;
    }

    private Map<String, Double> buildMemoryMbByMod(Map<String, Long> memoryByMod) {
        LinkedHashMap<String, Double> result = new LinkedHashMap<>();
        memoryByMod.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(entry -> result.put(entry.getKey(), entry.getValue() / (1024.0 * 1024.0)));
        return result;
    }

    private Map<String, String> buildCpuConfidenceByMod(Map<String, CpuSamplingProfiler.Snapshot> rawCpu, EffectiveCpuAttribution effectiveCpu, Map<String, CpuSamplingProfiler.DetailSnapshot> cpuDetails) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        effectiveCpu.displaySnapshots().forEach((modId, snapshot) -> {
            long rawSamples = rawCpu.getOrDefault(modId, new CpuSamplingProfiler.Snapshot(0, 0, 0)).totalSamples();
            long shownSamples = snapshot.totalSamples();
            long redistributedSamples = effectiveCpu.redistributedSamplesByMod().getOrDefault(modId, 0L);
            AttributionInsights.Confidence confidence = AttributionInsights.cpuConfidence(modId, cpuDetails.get(modId), rawSamples, shownSamples, redistributedSamples);
            result.put(modId, confidence.label());
        });
        return result;
    }

    private Map<String, String> buildGpuConfidenceByMod(EffectiveGpuAttribution rawGpu, EffectiveGpuAttribution effectiveGpu) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        effectiveGpu.gpuNanosByMod().forEach((modId, displayGpuNanos) -> {
            AttributionInsights.Confidence confidence = AttributionInsights.gpuConfidence(
                    modId,
                    rawGpu.gpuNanosByMod().getOrDefault(modId, 0L),
                    displayGpuNanos,
                    effectiveGpu.redistributedGpuNanosByMod().getOrDefault(modId, 0L),
                    rawGpu.renderSamplesByMod().getOrDefault(modId, 0L),
                    effectiveGpu.renderSamplesByMod().getOrDefault(modId, 0L)
            );
            result.put(modId, confidence.label());
        });
        return result;
    }

    private Map<String, String> buildMemoryConfidenceByMod(Map<String, Long> rawMemory, EffectiveMemoryAttribution effectiveMemory, long memoryAgeMillis) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        effectiveMemory.displayBytes().forEach((modId, displayBytes) -> {
            AttributionInsights.Confidence confidence = AttributionInsights.memoryConfidence(
                    modId,
                    rawMemory.getOrDefault(modId, 0L),
                    displayBytes,
                    effectiveMemory.redistributedBytesByMod().getOrDefault(modId, 0L),
                    memoryAgeMillis
            );
            result.put(modId, confidence.label());
        });
        return result;
    }

    private Map<String, String> buildCpuProvenanceByMod(Map<String, CpuSamplingProfiler.Snapshot> rawCpu, EffectiveCpuAttribution effectiveCpu, Map<String, CpuSamplingProfiler.DetailSnapshot> cpuDetails) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        effectiveCpu.displaySnapshots().forEach((modId, snapshot) -> result.put(
                modId,
                AttributionInsights.cpuProvenance(
                        rawCpu.getOrDefault(modId, new CpuSamplingProfiler.Snapshot(0, 0, 0)).totalSamples(),
                        effectiveCpu.redistributedSamplesByMod().getOrDefault(modId, 0L),
                        cpuDetails.get(modId)
                )
        ));
        return result;
    }

    private Map<String, String> buildGpuProvenanceByMod(EffectiveGpuAttribution rawGpu, EffectiveGpuAttribution effectiveGpu) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        effectiveGpu.gpuNanosByMod().forEach((modId, displayGpuNanos) -> result.put(
                modId,
                AttributionInsights.gpuProvenance(
                        rawGpu.gpuNanosByMod().getOrDefault(modId, 0L),
                        effectiveGpu.redistributedGpuNanosByMod().getOrDefault(modId, 0L),
                        rawGpu.renderSamplesByMod().getOrDefault(modId, 0L),
                        effectiveGpu.renderSamplesByMod().getOrDefault(modId, 0L)
                )
        ));
        return result;
    }

    private Map<String, String> buildMemoryProvenanceByMod(Map<String, Long> rawMemory, EffectiveMemoryAttribution effectiveMemory, long memoryAgeMillis) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        effectiveMemory.displayBytes().forEach((modId, displayBytes) -> result.put(
                modId,
                AttributionInsights.memoryProvenance(
                        rawMemory.getOrDefault(modId, 0L),
                        effectiveMemory.redistributedBytesByMod().getOrDefault(modId, 0L),
                        memoryAgeMillis
                )
        ));
        return result;
    }

    private Map<String, Object> buildAttributionExport() {
        Map<String, Object> export = new LinkedHashMap<>();
        EffectiveCpuAttribution effectiveCpu = AttributionModelBuilder.buildEffectiveCpuAttribution(currentSnapshot.cpuMods(), currentSnapshot.cpuDetails(), currentSnapshot.modInvokes());
        EffectiveGpuAttribution rawGpu = AttributionModelBuilder.buildEffectiveGpuAttribution(currentSnapshot.renderPhases(), currentSnapshot.cpuMods(), effectiveCpu, false);
        EffectiveGpuAttribution effectiveGpu = AttributionModelBuilder.buildEffectiveGpuAttribution(currentSnapshot.renderPhases(), currentSnapshot.cpuMods(), effectiveCpu, true);
        EffectiveMemoryAttribution effectiveMemory = AttributionModelBuilder.buildEffectiveMemoryAttribution(currentSnapshot.memoryMods());

        Map<String, Object> cpu = new LinkedHashMap<>();
        cpu.put("sampleAgeMs", currentSnapshot.cpuSampleAgeMillis());
        cpu.put("collectorSource", "hybrid ThreadMXBean CPU budgets + sampled busy-thread stacks");
        cpu.put("rawTop", topCpuRows(currentSnapshot.cpuMods(), Math.max(1L, totalCpuMetric(currentSnapshot.cpuMods())), currentSnapshot.cpuMods(), effectiveCpu, false));
        cpu.put("effectiveTop", topCpuRows(effectiveCpu.displaySnapshots(), Math.max(1L, totalCpuMetric(effectiveCpu.displaySnapshots())), currentSnapshot.cpuMods(), effectiveCpu, true));
        cpu.put("redistributedSamplesByMod", effectiveCpu.redistributedSamplesByMod());
        export.put("cpu", cpu);

        Map<String, Object> gpu = new LinkedHashMap<>();
        gpu.put("sampleAgeMs", currentSnapshot.cpuSampleAgeMillis());
        gpu.put("collectorSource", "GPU timer queries on tagged render phases + sampled render-thread ownership");
        gpu.put("rawTop", topGpuRows(rawGpu, rawGpu, effectiveGpu, false));
        gpu.put("effectiveTop", topGpuRows(effectiveGpu, rawGpu, effectiveGpu, true));
        gpu.put("redistributedGpuNanosByMod", effectiveGpu.redistributedGpuNanosByMod());
        gpu.put("redistributedRenderSamplesByMod", effectiveGpu.redistributedRenderSamplesByMod());
        export.put("gpu", gpu);

        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("sampleAgeMs", currentSnapshot.memoryAgeMillis());
        memory.put("collectorSource", "live heap histogram + per-thread allocated-byte deltas");
        memory.put("rawTop", topMemoryRows(currentSnapshot.memoryMods(), effectiveMemory, false));
        memory.put("effectiveTop", topMemoryRows(effectiveMemory.displayBytes(), effectiveMemory, true));
        memory.put("redistributedBytesByMod", effectiveMemory.redistributedBytesByMod());
        export.put("memory", memory);
        return export;
    }

    private List<Map<String, Object>> buildRenderPhaseOwnerSummary() {
        return currentSnapshot.renderPhases().entrySet().stream()
                .sorted((a, b) -> Long.compare((b.getValue().cpuNanos() + b.getValue().gpuNanos()), (a.getValue().cpuNanos() + a.getValue().gpuNanos())))
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("phase", entry.getKey());
                    row.put("owner", AttributionModelBuilder.effectiveGpuPhaseOwner(entry.getValue()));
                    row.put("cpuMs", entry.getValue().cpuNanos() / 1_000_000.0);
                    row.put("gpuMs", entry.getValue().gpuNanos() / 1_000_000.0);
                    row.put("cpuCalls", entry.getValue().cpuCalls());
                    row.put("gpuCalls", entry.getValue().gpuCalls());
                    row.put("likelyOwners", entry.getValue().likelyOwners());
                    row.put("likelyFrames", entry.getValue().likelyFrames());
                    return row;
                })
                .toList();
    }

    private Map<String, Object> buildSharedBucketBreakdownExport() {
        Map<String, Object> export = new LinkedHashMap<>();
        Map<String, Object> cpu = new LinkedHashMap<>();
        currentSnapshot.cpuDetails().entrySet().stream()
                .filter(entry -> AttributionModelBuilder.isSharedAttributionBucket(entry.getKey()))
                .forEach(entry -> cpu.put(entry.getKey(), entry.getValue().topFrames()));
        export.put("cpu", cpu);

        Map<String, Object> gpu = new LinkedHashMap<>();
        currentSnapshot.renderPhases().entrySet().stream()
                .filter(entry -> {
                    String owner = AttributionModelBuilder.effectiveGpuPhaseOwner(entry.getValue());
                    return AttributionModelBuilder.isSharedAttributionBucket(owner);
                })
                .forEach(entry -> gpu.put(entry.getKey(), Map.of(
                        "owner", AttributionModelBuilder.effectiveGpuPhaseOwner(entry.getValue()),
                        "cpuMs", entry.getValue().cpuNanos() / 1_000_000.0,
                        "gpuMs", entry.getValue().gpuNanos() / 1_000_000.0,
                        "likelyOwners", entry.getValue().likelyOwners(),
                        "likelyFrames", entry.getValue().likelyFrames()
                )));
        export.put("gpu", gpu);

        Map<String, Object> memory = new LinkedHashMap<>();
        currentSnapshot.sharedMemoryFamilies().forEach((family, bytes) -> memory.put(family, Map.of(
                "bytes", bytes,
                "classes", currentSnapshot.sharedFamilyClasses().getOrDefault(family, Map.of())
        )));
        export.put("memory", memory);
        return export;
    }

    private List<Map<String, Object>> topCpuRows(Map<String, CpuSamplingProfiler.Snapshot> snapshots, long totalSamples, Map<String, CpuSamplingProfiler.Snapshot> rawCpu, EffectiveCpuAttribution effectiveCpu, boolean effectiveView) {
        return snapshots.entrySet().stream()
                .sorted((a, b) -> Long.compare(cpuMetricValue(b.getValue()), cpuMetricValue(a.getValue())))
                .limit(8)
                .map(entry -> {
                    String modId = entry.getKey();
                    long rawSamples = rawCpu.getOrDefault(modId, new CpuSamplingProfiler.Snapshot(0, 0, 0)).totalSamples();
                    long shownSamples = entry.getValue().totalSamples();
                    long redistributedSamples = effectiveCpu.redistributedSamplesByMod().getOrDefault(modId, 0L);
                    CpuSamplingProfiler.DetailSnapshot detail = currentSnapshot.cpuDetails().get(modId);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("mod", modId);
                    row.put("samples", entry.getValue().totalSamples());
                    row.put("cpuNanos", entry.getValue().totalCpuNanos());
                    row.put("percent", cpuMetricValue(entry.getValue()) * 100.0 / Math.max(1L, totalSamples));
                    row.put("rawSamples", rawSamples);
                    row.put("effectiveSamples", effectiveView ? shownSamples : rawSamples);
                    row.put("redistributedSamples", redistributedSamples);
                    row.put("confidence", AttributionInsights.cpuConfidence(modId, detail, rawSamples, shownSamples, redistributedSamples).label());
                    row.put("provenance", AttributionInsights.cpuProvenance(rawSamples, redistributedSamples, detail));
                    row.put("collectorSource", "hybrid ThreadMXBean CPU budgets + sampled busy-thread stacks");
                    row.put("sampleAgeMs", currentSnapshot.cpuSampleAgeMillis());
                    return row;
                })
                .toList();
    }

    private List<Map<String, Object>> topGpuRows(EffectiveGpuAttribution displayAttribution, EffectiveGpuAttribution rawAttribution, EffectiveGpuAttribution effectiveAttribution, boolean effectiveView) {
        return displayAttribution.gpuNanosByMod().entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(8)
                .map(entry -> {
                    String modId = entry.getKey();
                    long rawGpuNanos = rawAttribution.gpuNanosByMod().getOrDefault(modId, 0L);
                    long displayGpuNanos = entry.getValue();
                    long redistributedGpuNanos = effectiveAttribution.redistributedGpuNanosByMod().getOrDefault(modId, 0L);
                    long rawRenderSamples = rawAttribution.renderSamplesByMod().getOrDefault(modId, 0L);
                    long displayRenderSamples = displayAttribution.renderSamplesByMod().getOrDefault(modId, 0L);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("mod", modId);
                    row.put("gpuMs", entry.getValue() / 1_000_000.0);
                    row.put("percent", entry.getValue() * 100.0 / Math.max(1L, displayAttribution.totalGpuNanos()));
                    row.put("renderSamples", displayRenderSamples);
                    row.put("rawGpuMs", rawGpuNanos / 1_000_000.0);
                    row.put("effectiveGpuMs", effectiveView ? displayGpuNanos / 1_000_000.0 : rawGpuNanos / 1_000_000.0);
                    row.put("redistributedGpuMs", redistributedGpuNanos / 1_000_000.0);
                    row.put("confidence", AttributionInsights.gpuConfidence(modId, rawGpuNanos, displayGpuNanos, redistributedGpuNanos, rawRenderSamples, displayRenderSamples).label());
                    row.put("provenance", AttributionInsights.gpuProvenance(rawGpuNanos, redistributedGpuNanos, rawRenderSamples, displayRenderSamples));
                    row.put("collectorSource", "GPU timer queries on tagged render phases + sampled render-thread ownership");
                    row.put("sampleAgeMs", currentSnapshot.cpuSampleAgeMillis());
                    return row;
                })
                .toList();
    }

    private List<Map<String, Object>> topMemoryRows(Map<String, Long> memoryByMod, EffectiveMemoryAttribution effectiveMemory, boolean effectiveView) {
        long total = Math.max(1L, memoryByMod.values().stream().mapToLong(Long::longValue).sum());
        return memoryByMod.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(8)
                .map(entry -> {
                    String modId = entry.getKey();
                    long rawBytes = currentSnapshot.memoryMods().getOrDefault(modId, 0L);
                    long displayBytes = entry.getValue();
                    long redistributedBytes = effectiveMemory.redistributedBytesByMod().getOrDefault(modId, 0L);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("mod", modId);
                    row.put("memoryMb", entry.getValue() / (1024.0 * 1024.0));
                    row.put("percent", entry.getValue() * 100.0 / total);
                    row.put("rawMemoryMb", rawBytes / (1024.0 * 1024.0));
                    row.put("effectiveMemoryMb", effectiveView ? displayBytes / (1024.0 * 1024.0) : rawBytes / (1024.0 * 1024.0));
                    row.put("redistributedMemoryMb", redistributedBytes / (1024.0 * 1024.0));
                    row.put("confidence", AttributionInsights.memoryConfidence(modId, rawBytes, displayBytes, redistributedBytes, currentSnapshot.memoryAgeMillis()).label());
                    row.put("provenance", AttributionInsights.memoryProvenance(rawBytes, redistributedBytes, currentSnapshot.memoryAgeMillis()));
                    row.put("collectorSource", "live heap histogram + per-thread allocated-byte deltas");
                    row.put("sampleAgeMs", currentSnapshot.memoryAgeMillis());
                    return row;
                })
                .toList();
    }

    private List<Map<String, Object>> buildTopCpuModSummary() {
        EffectiveCpuAttribution effectiveCpu = AttributionModelBuilder.buildEffectiveCpuAttribution(currentSnapshot.cpuMods(), currentSnapshot.cpuDetails(), currentSnapshot.modInvokes());
        long totalCpuSamples = Math.max(1L, totalCpuMetric(effectiveCpu.displaySnapshots()));
        return effectiveCpu.displaySnapshots().entrySet().stream()
                .sorted((a, b) -> Long.compare(cpuMetricValue(b.getValue()), cpuMetricValue(a.getValue())))
                .limit(5)
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("mod", entry.getKey());
                    row.put("samples", entry.getValue().totalSamples());
                    row.put("cpuNanos", entry.getValue().totalCpuNanos());
                    row.put("percentCpu", cpuMetricValue(entry.getValue()) * 100.0 / totalCpuSamples);
                    row.put("rawSamples", currentSnapshot.cpuMods().getOrDefault(entry.getKey(), new CpuSamplingProfiler.Snapshot(0, 0, 0)).totalSamples());
                    row.put("redistributedSamples", effectiveCpu.redistributedSamplesByMod().getOrDefault(entry.getKey(), 0L));
                    row.put("threadCount", currentSnapshot.cpuDetails().get(entry.getKey()) == null ? 0 : currentSnapshot.cpuDetails().get(entry.getKey()).sampledThreadCount());
                    row.put("confidence", buildCpuConfidenceByMod(currentSnapshot.cpuMods(), effectiveCpu, currentSnapshot.cpuDetails()).getOrDefault(entry.getKey(), "Unknown"));
                    row.put("provenance", buildCpuProvenanceByMod(currentSnapshot.cpuMods(), effectiveCpu, currentSnapshot.cpuDetails()).getOrDefault(entry.getKey(), "Unknown"));
                    row.put("sampleAgeMs", currentSnapshot.cpuSampleAgeMillis());
                    row.put("collectorSource", "hybrid ThreadMXBean CPU budgets + sampled busy-thread stacks");
                    return row;
                })
                .toList();
    }

    private List<Map<String, Object>> buildTopGpuModSummary() {
        EffectiveCpuAttribution effectiveCpu = AttributionModelBuilder.buildEffectiveCpuAttribution(currentSnapshot.cpuMods(), currentSnapshot.cpuDetails(), currentSnapshot.modInvokes());
        EffectiveGpuAttribution rawGpu = AttributionModelBuilder.buildEffectiveGpuAttribution(currentSnapshot.renderPhases(), currentSnapshot.cpuMods(), effectiveCpu, false);
        EffectiveGpuAttribution effectiveGpu = AttributionModelBuilder.buildEffectiveGpuAttribution(currentSnapshot.renderPhases(), currentSnapshot.cpuMods(), effectiveCpu, true);
        long totalGpuNs = Math.max(1L, effectiveGpu.totalGpuNanos());
        return effectiveGpu.gpuNanosByMod().entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(5)
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("mod", entry.getKey());
                    row.put("gpuFrameTimeMsEstimate", entry.getValue() / 1_000_000.0);
                    row.put("percentGpuEstimate", entry.getValue() * 100.0 / totalGpuNs);
                    row.put("rawGpuFrameTimeMs", rawGpu.gpuNanosByMod().getOrDefault(entry.getKey(), 0L) / 1_000_000.0);
                    row.put("renderSamples", effectiveGpu.renderSamplesByMod().getOrDefault(entry.getKey(), 0L));
                    row.put("redistributedGpuNanos", effectiveGpu.redistributedGpuNanosByMod().getOrDefault(entry.getKey(), 0L));
                    row.put("threadCount", currentSnapshot.cpuDetails().get(entry.getKey()) == null ? 0 : currentSnapshot.cpuDetails().get(entry.getKey()).sampledThreadCount());
                    row.put("confidence", buildGpuConfidenceByMod(rawGpu, effectiveGpu).getOrDefault(entry.getKey(), "Unknown"));
                    row.put("provenance", buildGpuProvenanceByMod(rawGpu, effectiveGpu).getOrDefault(entry.getKey(), "Unknown"));
                    row.put("sampleAgeMs", currentSnapshot.cpuSampleAgeMillis());
                    row.put("collectorSource", "GPU timer queries on tagged render phases + sampled render-thread ownership");
                    return row;
                })
                .toList();
    }

    private List<Map<String, Object>> buildTopMemoryModSummary() {
        EffectiveMemoryAttribution effectiveMemory = AttributionModelBuilder.buildEffectiveMemoryAttribution(currentSnapshot.memoryMods());
        long totalMemory = Math.max(1L, currentSnapshot.memoryMods().values().stream().mapToLong(Long::longValue).sum());
        return currentSnapshot.memoryMods().entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(5)
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("mod", entry.getKey());
                    row.put("memoryMb", entry.getValue() / (1024.0 * 1024.0));
                    row.put("percentAttributedMemory", entry.getValue() * 100.0 / totalMemory);
                    row.put("classCount", currentSnapshot.memoryClassesByMod().getOrDefault(entry.getKey(), Map.of()).size());
                    row.put("confidence", buildMemoryConfidenceByMod(currentSnapshot.memoryMods(), effectiveMemory, currentSnapshot.memoryAgeMillis()).getOrDefault(entry.getKey(), "Unknown"));
                    row.put("provenance", buildMemoryProvenanceByMod(currentSnapshot.memoryMods(), effectiveMemory, currentSnapshot.memoryAgeMillis()).getOrDefault(entry.getKey(), "Unknown"));
                    row.put("sampleAgeMs", currentSnapshot.memoryAgeMillis());
                    row.put("collectorSource", "live heap histogram + per-thread allocated-byte deltas");
                    return row;
                })
                .toList();
    }

    private String currentBottleneckLabel() {
        SystemMetricsProfiler.Snapshot system = SystemMetricsProfiler.getInstance().getSnapshot();
        double serverTickMs = TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0;
        if (system.gpuCoreLoadPercent() > 90.0 && serverTickMs < 15.0) {
            return "GPU bottleneck";
        }
        if (serverTickMs > 40.0) {
            return "Logic bottleneck";
        }
        if (system.diskWriteBytesPerSecond() > 8L * 1024L * 1024L) {
            return "I/O bottleneck";
        }
        return "Balanced";
    }

    private String thermalStateLabel(SystemMetricsProfiler.Snapshot system) {
        if (system.gpuTemperatureC() > 85.0 || system.cpuTemperatureC() > 90.0) {
            return "Thermal warning";
        }
        if (system.cpuTemperatureC() < 0 && system.gpuTemperatureC() < 0) {
            return "Sensors unavailable";
        }
        return "Thermals nominal";
    }


    private List<String> topBlockedThreadSummaries(SystemMetricsProfiler.Snapshot system) {
        return system.threadDetailsByName().entrySet().stream()
                .filter(entry -> entry.getValue().blockedCountDelta() > 0 || entry.getValue().waitedCountDelta() > 0 || "BLOCKED".equals(entry.getValue().state()) || "WAITING".equals(entry.getValue().state()))
                .limit(5)
                .map(entry -> {
                    ThreadLoadProfiler.ThreadSnapshot details = entry.getValue();
                    String lock = describeLock(details);
                    return entry.getKey() + " | " + details.state() + " | blocked " + details.blockedCountDelta() + " | waited " + details.waitedCountDelta() + " | lock " + lock;
                })
                .toList();
    }

    private Map<String, Long> buildRuleFindingSeverityBreakdown() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (RuleFinding finding : latestRuleFindings) {
            counts.merge(finding.severity(), 1L, Long::sum);
        }
        return counts;
    }

    private String describeLock(ThreadLoadProfiler.ThreadSnapshot detail) {
        if (detail == null) {
            return "unknown lock";
        }
        if (detail.lockName() != null && !detail.lockName().isBlank()) {
            return detail.lockName();
        }
        if (detail.lockOwnerName() != null && !detail.lockOwnerName().isBlank()) {
            return "owned by " + detail.lockOwnerName();
        }
        return "unknown lock";
    }

    private double percentileSessionGpuFrameTime(double currentGpuFrameTimeMs) {
        java.util.List<Double> values = new java.util.ArrayList<>();
        for (SessionPoint point : sessionHistory) {
            values.add(point.gpuFrameTimeMs());
        }
        values.add(currentGpuFrameTimeMs);
        values.sort(Double::compareTo);
        if (values.isEmpty()) {
            return 0.0;
        }
        int idx = Math.min(values.size() - 1, Math.max(0, (int) Math.ceil(values.size() * 0.95) - 1));
        return values.get(idx);
    }

    private Map<String, Object> buildStutterScoreThreadLink() {
        SystemMetricsProfiler.Snapshot system = SystemMetricsProfiler.getInstance().getSnapshot();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stutterScore", FrameTimelineProfiler.getInstance().getStutterScore());
        result.put("topThreadName", highestCpuThreadName(system));
        result.put("parallelismFlag", sessionParallelismFlag(system, FrameTimelineProfiler.getInstance().getStutterScore()));
        return result;
    }

    private String sessionParallelismFlag(SystemMetricsProfiler.Snapshot system, double stutterScore) {
        if (system.activeHighLoadThreads() > Math.max(1, system.estimatedPhysicalCores() / 2) && stutterScore > 10.0) {
            return "Thread Overscheduling Warning";
        }
        return system.cpuParallelismFlag();
    }

    private String highestCpuThreadName(SystemMetricsProfiler.Snapshot system) {
        if (system.threadLoadPercentByName().isEmpty()) {
            return "unknown";
        }
        return system.threadLoadPercentByName().entrySet().iterator().next().getKey();
    }

    private String buildDiagnosis() {
        SystemMetricsProfiler.Snapshot system = SystemMetricsProfiler.getInstance().getSnapshot();
        MemoryProfiler.Snapshot memory = MemoryProfiler.getInstance().getDetailedSnapshot();
        double serverTickMs = TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0;
        RuleFinding highestFinding = latestRuleFindings.stream()
                .sorted((a, b) -> Integer.compare(severityRank(b.severity()), severityRank(a.severity())))
                .findFirst()
                .orElse(null);
        String status = highestFinding == null ? "Stable" : switch (highestFinding.severity()) {
            case "critical" -> "Critical";
            case "warning", "error" -> "Warning";
            default -> "Stable";
        };
        String systemBound = system.gpuCoreLoadPercent() > 90.0 && serverTickMs < 15.0 ? "GPU-bound" : serverTickMs > 40.0 ? "CPU-bound" : "Balanced";
        String thermal = system.gpuTemperatureC() > 85.0 || system.cpuTemperatureC() > 90.0 ? "Thermal warning." : "Thermals are optimal.";
        boolean memoryPressure = false;
        SessionPoint lastPoint = sessionHistory.peekLast();
        if (lastPoint != null) {
            memoryPressure = lastPoint.isGcEvent() && lastPoint.usedHeapMb() >= (lastPoint.allocatedHeapMb() * 0.9);
        }
        String memoryText = memoryPressure ? "Memory pressure detected." : "Memory pressure is low.";
        String logicText = serverTickMs > 40.0 ? String.format("Entity logic overhead is high (%.1fms).", serverTickMs) : String.format("Entity logic overhead is low (%.1fms).", serverTickMs);
        String schedulingText = system.schedulingConflictSummary() == null || system.schedulingConflictSummary().isBlank() ? "" : (" " + system.schedulingConflictSummary() + ".");
        String overscheduleText = sessionParallelismFlag(system, FrameTimelineProfiler.getInstance().getStutterScore()).equals("Thread Overscheduling Warning") ? " Thread overscheduling is likely." : "";
        String conflictText = latestConflictEdges.isEmpty() ? "" : (" Top conflict candidate: " + latestConflictEdges.getFirst().waiterMod() + " waiting on " + latestConflictEdges.getFirst().ownerMod() + " via " + latestConflictEdges.getFirst().lockName() + ".");
        return "Status: " + status + ". System is " + systemBound + ". " + thermal + " " + memoryText + " " + logicText + " Parallelism Efficiency: " + system.parallelismEfficiency() + schedulingText + overscheduleText + conflictText;
    }

    private void appendTraceSpan(List<Map<String, Object>> events, String name, String threadName, long completedAtEpochMillis, double durationMs) {
        if (completedAtEpochMillis <= 0L || durationMs <= 0.0) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("name", name);
        event.put("cat", "taskmanager");
        event.put("ph", "X");
        event.put("pid", 1);
        event.put("tid", threadName);
        event.put("ts", Math.max(0L, completedAtEpochMillis * 1000L - Math.round(durationMs * 1000.0)));
        event.put("dur", Math.max(1L, Math.round(durationMs * 1000.0)));
        events.add(event);
    }

    private void enforceSessionWindow(Minecraft client) {
        int maxSessionPoints = Math.max(60, ConfigManager.getSessionDurationSeconds() * 20);
        while (sessionHistory.size() > maxSessionPoints) {
            sessionHistory.removeFirst();
        }
        if (sessionLogging && getSessionLoggingElapsedMillis() >= ConfigManager.getSessionDurationSeconds() * 1000L) {
            sessionLogging = false;
            sessionRecorded = true;
            sessionRecordedAtMillis = System.currentTimeMillis();
            String exportStatus = exportSession();
            if (client != null && client.player != null) {
                client.player.sendSystemMessage(Component.literal("Task Manager: Session recorded. " + exportStatus));
            }
        }
    }

    private void captureSpikeIfNeeded() {
        FrameTimelineProfiler frameProfiler = FrameTimelineProfiler.getInstance();
        if (frameProfiler.getFrameSequence() == lastSeenFrameSequence) {
            return;
        }

        lastSeenFrameSequence = frameProfiler.getFrameSequence();
        long frameNs = frameProfiler.getLatestFrameNs();
        if (frameNs < SPIKE_THRESHOLD_NS && mode != CaptureMode.SPIKE_CAPTURE) {
            return;
        }

        Map<String, CpuSamplingProfiler.Snapshot> cpu = aggregateCpuWindows();
        Map<String, RenderPhaseProfiler.PhaseSnapshot> render = aggregateRenderWindows();

        List<String> topCpuMods = cpu.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().totalSamples(), a.getValue().totalSamples()))
                .limit(3)
                .map(entry -> entry.getKey() + " " + entry.getValue().totalSamples())
                .toList();

        List<String> topRenderPhases = render.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().cpuNanos() + b.getValue().gpuNanos(), a.getValue().cpuNanos() + a.getValue().gpuNanos()))
                .limit(3)
                .map(entry -> entry.getKey() + " " + String.format("%.2f ms", (entry.getValue().cpuNanos() + entry.getValue().gpuNanos()) / 1_000_000.0))
                .toList();

        double frameDurationMs = frameNs / 1_000_000.0;
        double stutterScore = frameProfiler.getStutterScore();
        List<String> topThreads = SystemMetricsProfiler.getInstance().getSnapshot().threadDetailsByName().entrySet().stream()
                .limit(5)
                .map(entry -> entry.getKey() + " " + String.format("%.1f%%", entry.getValue().loadPercent()))
                .toList();
        spikes.addFirst(new SpikeCapture(System.currentTimeMillis(), frameDurationMs, stutterScore, latestEntityCounts, latestChunkCounts, topCpuMods, topRenderPhases, topThreads, currentBottleneckLabel(), latestRuleFindings));
        NetworkPacketProfiler.getInstance().captureSpikeBookmark();
        while (spikes.size() > MAX_SPIKES) {
            spikes.removeLast();
        }
    }

    private void publishSnapshot() {
        publishSnapshot(false, Long.MAX_VALUE);
    }

    private void publishSnapshot(boolean force) {
        publishSnapshot(force, Long.MAX_VALUE);
    }

    private void publishSnapshot(long cpuSampleAgeMillis) {
        publishSnapshot(false, cpuSampleAgeMillis);
    }

    private void publishSnapshot(boolean force, long cpuSampleAgeMillis) {
        long now = System.currentTimeMillis();
        int snapshotDelayMs = ConfigManager.isHudEnabled() ? 50 : ConfigManager.getProfilerUpdateDelayMs();
        if (!shouldPublishSnapshot(force, now, lastSnapshotPublishedAtMillis, snapshotDelayMs)) {
            return;
        }
        lastSnapshotPublishedAtMillis = now;
        Map<String, CpuSamplingProfiler.Snapshot> cpu = aggregateCpuWindows();
        Map<String, CpuSamplingProfiler.DetailSnapshot> cpuDetails = aggregateCpuDetailWindows();
        Map<String, ModTimingSnapshot> modInvokes = aggregateModWindows();
        Map<String, RenderPhaseProfiler.PhaseSnapshot> render = aggregateRenderWindows();

        long totalCpuSamples = cpu.values().stream().mapToLong(CpuSamplingProfiler.Snapshot::totalSamples).sum();
        long totalRenderSamples = cpu.values().stream().mapToLong(CpuSamplingProfiler.Snapshot::renderSamples).sum();

        StartupTimingProfiler startup = StartupTimingProfiler.getInstance();
        currentSnapshot = new ProfilerSnapshot(
                System.currentTimeMillis(),
                mode,
                isCaptureActive(),
                CpuSamplingProfiler.getInstance().hasEnoughCpuSamples(totalCpuSamples),
                CpuSamplingProfiler.getInstance().hasEnoughRenderSamples(totalRenderSamples),
                cpuSampleAgeMillis,
                totalCpuSamples,
                totalRenderSamples,
                cpu,
                cpuDetails,
                modInvokes,
                render,
                MemoryProfiler.getInstance().getDetailedSnapshot(),
                MemoryProfiler.getInstance().getModMemoryBytes(),
                MemoryProfiler.getInstance().getSharedClassFamilies(),
                MemoryProfiler.getInstance().getSharedFamilyClasses(),
                MemoryProfiler.getInstance().getTopClassesByMod(),
                MemoryProfiler.getInstance().getLastModSampleAgeMillis(),
                latestEntityCounts,
                latestChunkCounts,
                SystemMetricsProfiler.getInstance().getSnapshot(),
                FrameTimelineProfiler.getInstance().getStutterScore(),
                startup.getSortedRows(),
                startup.getGlobalFirst(),
                startup.getGlobalLast(),
                FlamegraphProfiler.getInstance().getStacks(),
                new ArrayList<>(spikes),
                sessionLogging,
                getSessionLoggingElapsedMillis(),
                lastExportStatus
        );
    }

    public List<HotChunkSnapshot> getLatestHotChunks() {
        return latestHotChunks;
    }

    public List<SpikeCapture> getSpikes() {
        return List.copyOf(spikes);
    }

    public List<Integer> getChunkActivityHistory(ChunkPos chunkPos) {
        if (chunkPos == null) {
            return List.of();
        }
        Deque<Integer> history = chunkActivityHistory.get(chunkKey(chunkPos.x(), chunkPos.z()));
        return history == null ? List.of() : List.copyOf(history);
    }

    public List<RuleFinding> getLatestRuleFindings() {
        return latestRuleFindings;
    }

    public List<EntityHotspot> getLatestEntityHotspots() {
        return latestEntityHotspots;
    }

    public List<BlockEntityHotspot> getLatestBlockEntityHotspots() {
        return latestBlockEntityHotspots;
    }

    public List<ConflictEdge> getLatestConflictEdges() {
        return latestConflictEdges;
    }

    public List<String> getLatestLockSummaries() {
        return latestLockSummaries;
    }

    public String getCurrentBottleneckLabel() {
        return currentBottleneckLabel();
    }

    private List<HotChunkSnapshot> sampleHotChunks(Minecraft client) {
        if (client.level == null) {
            hotChunkHistory.clear();
            chunkActivityHistory.clear();
            return List.of();
        }

        record ChunkAggregate(int entityCount, int blockEntityCount, Map<String, Integer> entityClasses, Map<String, Integer> blockEntityClasses) {}
        Map<Long, int[]> counts = new LinkedHashMap<>();
        Map<Long, Map<String, Integer>> entityClasses = new LinkedHashMap<>();
        Map<Long, Map<String, Integer>> blockEntityClasses = new LinkedHashMap<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            ChunkPos pos = entity.chunkPosition();
            long key = chunkKey(pos.x(), pos.z());
            counts.computeIfAbsent(key, ignored -> new int[2])[0]++;
            entityClasses.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).merge(entity.getClass().getSimpleName(), 1, Integer::sum);
        }
        for (BlockEntity blockEntity : client.level.getGloballyRenderedBlockEntities()) {
            ChunkPos pos = ChunkPos.containing(blockEntity.getBlockPos());
            long key = chunkKey(pos.x(), pos.z());
            counts.computeIfAbsent(key, ignored -> new int[2])[1]++;
            blockEntityClasses.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).merge(blockEntity.getClass().getSimpleName(), 1, Integer::sum);
        }
        List<HotChunkSnapshot> result = counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare((b.getValue()[0] + (b.getValue()[1] * 2)), (a.getValue()[0] + (a.getValue()[1] * 2))))
                .limit(8)
                .map(entry -> new HotChunkSnapshot(
                        (int) (entry.getKey() >> 32),
                        (int) (long) entry.getKey(),
                        entry.getValue()[0],
                        entry.getValue()[1],
                        topClassName(entityClasses.get(entry.getKey())),
                        topClassName(blockEntityClasses.get(entry.getKey())),
                        entry.getValue()[0] + (entry.getValue()[1] * 2.0)
                ))
                .toList();
        hotChunkHistory.addLast(result);
        while (hotChunkHistory.size() > 120) {
            hotChunkHistory.removeFirst();
        }
        for (HotChunkSnapshot chunk : result) {
            long key = chunkKey(chunk.chunkX(), chunk.chunkZ());
            Deque<Integer> history = chunkActivityHistory.computeIfAbsent(key, ignored -> new ArrayDeque<>());
            history.addLast(chunk.entityCount() + chunk.blockEntityCount() * 2);
            while (history.size() > 120) {
                history.removeFirst();
            }
        }
        if (chunkActivityHistory.size() > 64) {
            List<Long> keep = result.stream().map(chunk -> chunkKey(chunk.chunkX(), chunk.chunkZ())).toList();
            chunkActivityHistory.keySet().removeIf(key -> !keep.contains(key));
        }
        return result;
    }

    private List<Map<String, Object>> buildHotChunkHistoryExport() {
        List<Map<String, Object>> export = new ArrayList<>();
        for (HotChunkSnapshot chunk : latestHotChunks) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chunkX", chunk.chunkX());
            row.put("chunkZ", chunk.chunkZ());
            row.put("entityCount", chunk.entityCount());
            row.put("blockEntityCount", chunk.blockEntityCount());
            row.put("topEntityClass", chunk.topEntityClass());
            row.put("topBlockEntityClass", chunk.topBlockEntityClass());
            row.put("activityHistory", getChunkActivityHistory(new ChunkPos(chunk.chunkX(), chunk.chunkZ())));
            export.add(row);
        }
        return export;
    }

    private int severityRank(String severity) {
        return RuleEngine.severityRank(severity);
    }
    private String formatBytesPerSecond(long value) {
        if (value < 0) {
            return "N/A";
        }
        if (value >= 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.2f MB/s", value / (1024.0 * 1024.0));
        }
        if (value >= 1024L) {
            return String.format(Locale.ROOT, "%.1f KB/s", value / 1024.0);
        }
        return value + " B/s";
    }

    private String formatBytesMb(long bytes) {
        if (bytes < 0) {
            return "N/A";
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private String topClassName(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return "none";
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("none");
    }

    private long chunkKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private List<EntityHotspot> sampleEntityHotspots(Minecraft client) {
        if (client.level == null) {
            return List.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            counts.merge(entity.getType().toString(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(8)
                .map(entry -> new EntityHotspot(entry.getKey(), entry.getValue(), classifyEntityHeuristic(entry.getKey())))
                .toList();
    }

    private List<BlockEntityHotspot> sampleBlockEntityHotspots(Minecraft client) {
        if (client.level == null) {
            return List.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (BlockEntity blockEntity : client.level.getGloballyRenderedBlockEntities()) {
            counts.merge(blockEntity.getClass().getSimpleName(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(8)
                .map(entry -> new BlockEntityHotspot(entry.getKey(), entry.getValue(), classifyBlockEntityHeuristic(entry.getKey())))
                .toList();
    }

    private void updateConflictTracking(SystemMetricsProfiler.Snapshot system) {
        boolean slowdownOverlap = FrameTimelineProfiler.getInstance().getLatestFrameNs() / 1_000_000.0 > Math.max(20.0, ConfigManager.getFrameBudgetTargetFrameMs() * 1.25)
                || TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0 > 20.0
                || FrameTimelineProfiler.getInstance().getStutterScore() > 10.0;
        List<ConflictObservation> observations = system.contentionSamples().stream()
                .map(sample -> new ConflictObservation(
                        sample.waiterMod(),
                        sample.ownerMod(),
                        sample.lockName(),
                        sample.waiterThreadName(),
                        sample.ownerThreadName(),
                        sample.waiterRole(),
                        sample.ownerRole(),
                        sample.blockedTimeDeltaMs(),
                        sample.waitedTimeDeltaMs(),
                        slowdownOverlap,
                        sample.confidence(),
                        List.copyOf(sample.waiterCandidates()),
                        List.copyOf(sample.ownerCandidates())
                ))
                .toList();
        conflictWindows.addLast(observations);
        while (conflictWindows.size() > WINDOW_SIZE) {
            conflictWindows.removeFirst();
        }
        latestConflictEdges = aggregateConflictEdges();
    }

    private List<ConflictEdge> aggregateConflictEdges() {
        record Aggregate(
                String waiterMod,
                String ownerMod,
                String lockName,
                String waiterThreadName,
                String ownerThreadName,
                String waiterRole,
                String ownerRole,
                long observations,
                long slowdownObservations,
                long blockedTimeMs,
                long waitedTimeMs,
                String confidence,
                List<String> waiterCandidates,
                List<String> ownerCandidates
        ) {}
        Map<String, Aggregate> aggregates = new LinkedHashMap<>();
        for (List<ConflictObservation> window : conflictWindows) {
            for (ConflictObservation observation : window) {
                String key = observation.waiterMod() + "|" + observation.ownerMod() + "|" + observation.lockName();
                Aggregate existing = aggregates.get(key);
                if (existing == null) {
                    aggregates.put(key, new Aggregate(
                            observation.waiterMod(),
                            observation.ownerMod(),
                            observation.lockName(),
                            observation.waiterThreadName(),
                            observation.ownerThreadName(),
                            observation.waiterRole(),
                            observation.ownerRole(),
                            1L,
                            observation.slowdownOverlap() ? 1L : 0L,
                            observation.blockedTimeDeltaMs(),
                            observation.waitedTimeDeltaMs(),
                            observation.confidence(),
                            observation.waiterCandidates(),
                            observation.ownerCandidates()
                    ));
                    continue;
                }
                aggregates.put(key, new Aggregate(
                        existing.waiterMod(),
                        existing.ownerMod(),
                        existing.lockName(),
                        observation.waiterThreadName() == null || observation.waiterThreadName().isBlank() ? existing.waiterThreadName() : observation.waiterThreadName(),
                        observation.ownerThreadName() == null || observation.ownerThreadName().isBlank() ? existing.ownerThreadName() : observation.ownerThreadName(),
                        observation.waiterRole() == null || observation.waiterRole().isBlank() ? existing.waiterRole() : observation.waiterRole(),
                        observation.ownerRole() == null || observation.ownerRole().isBlank() ? existing.ownerRole() : observation.ownerRole(),
                        existing.observations() + 1L,
                        existing.slowdownObservations() + (observation.slowdownOverlap() ? 1L : 0L),
                        existing.blockedTimeMs() + observation.blockedTimeDeltaMs(),
                        existing.waitedTimeMs() + observation.waitedTimeDeltaMs(),
                        strongerConfidence(existing.confidence(), observation.confidence()),
                        existing.waiterCandidates().isEmpty() ? observation.waiterCandidates() : existing.waiterCandidates(),
                        existing.ownerCandidates().isEmpty() ? observation.ownerCandidates() : existing.ownerCandidates()
                ));
            }
        }
        return aggregates.values().stream()
                .map(aggregate -> new ConflictEdge(
                        aggregate.waiterMod(),
                        aggregate.ownerMod(),
                        aggregate.lockName(),
                        aggregate.waiterThreadName(),
                        aggregate.ownerThreadName(),
                        aggregate.waiterRole(),
                        aggregate.ownerRole(),
                        aggregate.observations(),
                        aggregate.slowdownObservations(),
                        aggregate.blockedTimeMs(),
                        aggregate.waitedTimeMs(),
                        aggregate.confidence(),
                        aggregate.waiterCandidates(),
                        aggregate.ownerCandidates()
                ))
                .sorted((a, b) -> {
                    int slowdownCompare = Long.compare(b.slowdownObservations(), a.slowdownObservations());
                    if (slowdownCompare != 0) {
                        return slowdownCompare;
                    }
                    int observationCompare = Long.compare(b.observations(), a.observations());
                    if (observationCompare != 0) {
                        return observationCompare;
                    }
                    return Long.compare((b.blockedTimeMs() + b.waitedTimeMs()), (a.blockedTimeMs() + a.waitedTimeMs()));
                })
                .limit(10)
                .toList();
    }

    private String strongerConfidence(String left, String right) {
        return confidenceRank(right) > confidenceRank(left) ? right : left;
    }

    private int confidenceRank(String confidence) {
        if (confidence == null) {
            return 0;
        }
        return switch (confidence.toLowerCase(Locale.ROOT)) {
            case "known incompatibility" -> 4;
            case "pairwise inferred", "measured" -> 3;
            case "inferred" -> 2;
            case "weak heuristic" -> 1;
            default -> 0;
        };
    }

    private List<String> buildLockSummaries(SystemMetricsProfiler.Snapshot system) {
        if (system.contentionSamples() != null && !system.contentionSamples().isEmpty()) {
            return system.contentionSamples().stream()
                    .limit(5)
                    .map(sample -> {
                        String waiter = formatConflictParty(sample.waiterMod(), sample.waiterThreadName(), sample.waiterRole());
                        String owner = formatConflictParty(sample.ownerMod(), sample.ownerThreadName(), sample.ownerRole());
                        return waiter + " waiting on " + owner + " via " + sample.lockName()
                                + " (" + sample.confidence() + ", blocked " + sample.blockedTimeDeltaMs() + " ms, waited " + sample.waitedTimeDeltaMs() + " ms)";
                    })
                    .toList();
        }
        return system.threadDetailsByName().entrySet().stream()
                .filter(entry -> entry.getValue().blockedCountDelta() > 0 || entry.getValue().waitedCountDelta() > 0 || "BLOCKED".equals(entry.getValue().state()) || "WAITING".equals(entry.getValue().state()))
                .limit(5)
                .map(entry -> {
                    ThreadLoadProfiler.ThreadSnapshot detail = entry.getValue();
                    String lockName = detail.lockName() == null || detail.lockName().isBlank() ? "unknown lock" : detail.lockName();
                    String owner = detail.lockOwnerName() == null || detail.lockOwnerName().isBlank() ? "" : (" owned by " + detail.lockOwnerName());
                    return entry.getKey() + " waiting on " + lockName + owner + " (blocked " + detail.blockedCountDelta() + ", waited " + detail.waitedCountDelta() + ")";
                })
                .toList();
    }

    private String formatConflictParty(String modId, String threadName, String role) {
        String mod = modId == null || modId.isBlank() ? "unknown" : modId;
        String thread = threadName == null || threadName.isBlank() ? "unknown thread" : threadName;
        String roleText = role == null || role.isBlank() ? "unknown role" : role;
        return mod + " [" + roleText + " | " + thread + "]";
    }

    private String classifyEntityHeuristic(String className) {
        return RuleEngine.classifyEntityHeuristic(className);
    }

    private String classifyBlockEntityHeuristic(String className) {
        return RuleEngine.classifyBlockEntityHeuristic(className);
    }



    private void captureStutterJumpSnapshot(Minecraft client) {
        double currentStutter = FrameTimelineProfiler.getInstance().getStutterScore();
        if (currentStutter > 20.0 && lastStutterScore <= 20.0) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("capturedAtEpochMillis", System.currentTimeMillis());
            row.put("stutterScore", currentStutter);
            row.put("currentBiome", sampleCurrentBiome(client));
            row.put("closestEntities", sampleClosestEntities(client, 3));
            row.put("topThreads", SystemMetricsProfiler.getInstance().getSnapshot().threadDetailsByName().entrySet().stream()
                    .limit(2)
                    .map(entry -> entry.getKey() + " " + String.format(Locale.ROOT, "%.1f%%", entry.getValue().loadPercent()))
                    .toList());
            stutterJumpSnapshots.addFirst(row);
            while (stutterJumpSnapshots.size() > 8) {
                stutterJumpSnapshots.removeLast();
            }
        }
        lastStutterScore = currentStutter;
    }

    private String sampleCurrentBiome(Minecraft client) {
        try {
            if (client == null || client.level == null || client.player == null) {
                return "unknown";
            }
            Holder<?> biome = client.level.getBiome(client.player.blockPosition());
            return biome.unwrapKey().map(key -> key.identifier().toString()).orElse("unknown");
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private List<String> sampleClosestEntities(Minecraft client, int limit) {
        if (client == null || client.level == null || client.player == null) {
            return List.of();
        }
        java.util.List<Entity> entities = new java.util.ArrayList<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity != client.player) {
                entities.add(entity);
            }
        }
        return entities.stream()
                .sorted((a, b) -> Double.compare(a.distanceToSqr(client.player), b.distanceToSqr(client.player)))
                .limit(limit)
                .map(entity -> entity.getName().getString())
                .toList();
    }

    private List<Map<String, String>> buildRedFlagThresholds() {
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(redFlag("Sync-Lock Latency", "Flags contention windows that can indicate mod interactions or storage stalls.", "> 10ms"));
        rows.add(redFlag("Draw Call Counter", "Tells you if your base has too many chests/signs.", "> 8,000"));
        rows.add(redFlag("Lighting Queue", "Detects lag caused by light/shadow recalculations.", "> 500 updates"));
        rows.add(redFlag("Thread State Ratio", "Shows if your CPU is working or just waiting.", "< 0.5 ratio"));
        rows.add(redFlag("IO Write Speed", "Detects if your SSD is slowing down the world save.", "> 200ms"));
        return rows;
    }

    private Map<String, String> redFlag(String feature, String why, String value) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("featureName", feature);
        row.put("whyYouNeedIt", why);
        row.put("redFlagValue", value);
        return row;
    }

    private WorldScanResult sampleWorldData(Minecraft client) {
        if (client.level == null) {
            hotChunkHistory.clear();
            chunkActivityHistory.clear();
            lastWorldScanAtMillis = System.currentTimeMillis();
            lastWorldScanDurationMillis = 0L;
            return new WorldScanResult(EntityCounts.empty(), List.of(), List.of(), List.of());
        }

        long now = System.currentTimeMillis();
        long cadenceMillis = CollectorMath.computeAdaptiveWorldScanCadenceMillis(shouldCollectDetailedMetrics(), sessionLogging, isProfilerSelfProtectionActive(), lastWorldScanDurationMillis);
        if (lastWorldScanAtMillis > 0L && now - lastWorldScanAtMillis < cadenceMillis && !latestHotChunks.isEmpty()) {
            return new WorldScanResult(latestEntityCounts, latestHotChunks, latestEntityHotspots, latestBlockEntityHotspots);
        }
        lastWorldScanAtMillis = now;
        long scanStartedAtMillis = now;

        Map<Long, int[]> chunkCounts = new LinkedHashMap<>();
        Map<Long, Map<String, Integer>> chunkEntityClasses = new LinkedHashMap<>();
        Map<Long, Map<String, Integer>> chunkBlockEntityClasses = new LinkedHashMap<>();
        Map<String, Integer> globalEntityCounts = new LinkedHashMap<>();
        Map<String, Integer> globalBlockEntityCounts = new LinkedHashMap<>();
        int totalEntities = 0;
        int livingEntities = 0;
        int sampledEntities = 0;
        for (Entity entity : client.level.entitiesForRendering()) {
            totalEntities++;
            if (entity instanceof LivingEntity) {
                livingEntities++;
            }
            boolean includeInHotspotScan = sampledEntities < MAX_WORLD_SCAN_ENTITIES
                    || (totalEntities - MAX_WORLD_SCAN_ENTITIES) % WORLD_SCAN_ENTITY_SAMPLE_STRIDE == 0;
            if (!includeInHotspotScan) {
                continue;
            }
            sampledEntities++;
            String entityKey = entity.getType().toString();
            globalEntityCounts.merge(entityKey, 1, Integer::sum);
            ChunkPos pos = entity.chunkPosition();
            long key = chunkKey(pos.x(), pos.z());
            chunkCounts.computeIfAbsent(key, ignored -> new int[2])[0]++;
            chunkEntityClasses.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).merge(entity.getClass().getSimpleName(), 1, Integer::sum);
        }

        int blockEntities = 0;
        int sampledBlockEntities = 0;
        for (BlockEntity blockEntity : client.level.getGloballyRenderedBlockEntities()) {
            blockEntities++;
            boolean includeInHotspotScan = sampledBlockEntities < MAX_WORLD_SCAN_BLOCK_ENTITIES
                    || (blockEntities - MAX_WORLD_SCAN_BLOCK_ENTITIES) % WORLD_SCAN_BLOCK_ENTITY_SAMPLE_STRIDE == 0;
            if (!includeInHotspotScan) {
                continue;
            }
            sampledBlockEntities++;
            String blockEntityKey = blockEntity.getClass().getSimpleName();
            globalBlockEntityCounts.merge(blockEntityKey, 1, Integer::sum);
            ChunkPos pos = ChunkPos.containing(blockEntity.getBlockPos());
            long key = chunkKey(pos.x(), pos.z());
            chunkCounts.computeIfAbsent(key, ignored -> new int[2])[1]++;
            chunkBlockEntityClasses.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).merge(blockEntityKey, 1, Integer::sum);
        }

        List<HotChunkSnapshot> hotChunks = chunkCounts.entrySet().stream()
                .sorted((a, b) -> Integer.compare((b.getValue()[0] + (b.getValue()[1] * 2)), (a.getValue()[0] + (a.getValue()[1] * 2))))
                .limit(8)
                .map(entry -> new HotChunkSnapshot(
                        (int) (entry.getKey() >> 32),
                        (int) (long) entry.getKey(),
                        entry.getValue()[0],
                        entry.getValue()[1],
                        topClassName(chunkEntityClasses.get(entry.getKey())),
                        topClassName(chunkBlockEntityClasses.get(entry.getKey())),
                        entry.getValue()[0] + (entry.getValue()[1] * 2.0)
                ))
                .toList();

        hotChunkHistory.addLast(hotChunks);
        while (hotChunkHistory.size() > 120) {
            hotChunkHistory.removeFirst();
        }
        for (HotChunkSnapshot chunk : hotChunks) {
            long key = chunkKey(chunk.chunkX(), chunk.chunkZ());
            Deque<Integer> history = chunkActivityHistory.computeIfAbsent(key, ignored -> new ArrayDeque<>());
            history.addLast(chunk.entityCount() + chunk.blockEntityCount() * 2);
            while (history.size() > 120) {
                history.removeFirst();
            }
        }
        if (chunkActivityHistory.size() > 64) {
            List<Long> keep = hotChunks.stream().map(chunk -> chunkKey(chunk.chunkX(), chunk.chunkZ())).toList();
            chunkActivityHistory.keySet().removeIf(key -> !keep.contains(key));
        }

        List<EntityHotspot> entityHotspots = globalEntityCounts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(8)
                .map(entry -> new EntityHotspot(entry.getKey(), entry.getValue(), classifyEntityHeuristic(entry.getKey())))
                .toList();

        List<BlockEntityHotspot> blockEntityHotspots = globalBlockEntityCounts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(8)
                .map(entry -> new BlockEntityHotspot(entry.getKey(), entry.getValue(), classifyBlockEntityHeuristic(entry.getKey())))
                .toList();

        WorldScanResult result = new WorldScanResult(
                new EntityCounts(totalEntities, livingEntities, blockEntities),
                hotChunks,
                entityHotspots,
                blockEntityHotspots
        );
        lastWorldScanDurationMillis = Math.max(0L, System.currentTimeMillis() - scanStartedAtMillis);
        return result;
    }


    private EntityCounts sampleEntityCounts(Minecraft client) {
        if (client.level == null) {
            return EntityCounts.empty();
        }

        int total = 0;
        int living = 0;
        for (Entity entity : client.level.entitiesForRendering()) {
            total++;
            if (entity instanceof LivingEntity) {
                living++;
            }
        }

        int blockEntities = client.level.getGloballyRenderedBlockEntities().size();
        return new EntityCounts(total, living, blockEntities);
    }

    private ChunkCounts sampleChunkCounts(Minecraft client) {
        if (client.levelRenderer == null) {
            return ChunkCounts.empty();
        }

        long now = System.currentTimeMillis();
        if (lastChunkCountsAtMillis > 0L && now - lastChunkCountsAtMillis < 250L && latestChunkCounts.loadedChunks() > 0) {
            return latestChunkCounts;
        }
        lastChunkCountsAtMillis = now;

        String debug = client.levelRenderer.getSectionStatistics();
        if (debug == null || debug.isBlank()) {
            return ChunkCounts.empty();
        }

        Matcher matcher = CHUNK_DEBUG_PATTERN.matcher(debug);
        if (!matcher.find()) {
            return ChunkCounts.empty();
        }

        try {
            int rendered = Integer.parseInt(matcher.group(1));
            int loaded = Integer.parseInt(matcher.group(2));
            return new ChunkCounts(loaded, rendered);
        } catch (NumberFormatException ignored) {
            return ChunkCounts.empty();
        }
    }

    private Map<String, CpuSamplingProfiler.Snapshot> aggregateCpuWindows() {
        Map<String, long[]> totals = new LinkedHashMap<>();
        for (Map<String, CpuSamplingProfiler.Snapshot> window : cpuWindows) {
            window.forEach((mod, snapshot) -> {
                long[] value = totals.computeIfAbsent(mod, ignored -> new long[6]);
                value[0] += snapshot.totalSamples();
                value[1] += snapshot.clientSamples();
                value[2] += snapshot.renderSamples();
                value[3] += snapshot.totalCpuNanos();
                value[4] += snapshot.clientCpuNanos();
                value[5] += snapshot.renderCpuNanos();
            });
        }

        Map<String, CpuSamplingProfiler.Snapshot> result = new LinkedHashMap<>();
        totals.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .forEach(entry -> result.put(entry.getKey(), new CpuSamplingProfiler.Snapshot(entry.getValue()[0], entry.getValue()[1], entry.getValue()[2], entry.getValue()[3], entry.getValue()[4], entry.getValue()[5])));
        return result;
    }

    private Map<String, CpuSamplingProfiler.DetailSnapshot> aggregateCpuDetailWindows() {
        Map<String, Map<String, Long>> threadTotals = new LinkedHashMap<>();
        Map<String, Map<String, Long>> frameTotals = new LinkedHashMap<>();
        Map<String, Integer> distinctThreadCounts = new LinkedHashMap<>();
        for (Map<String, CpuSamplingProfiler.DetailSnapshot> window : cpuDetailWindows) {
            window.forEach((mod, detail) -> {
                mergeLongMap(threadTotals.computeIfAbsent(mod, ignored -> new LinkedHashMap<>()), detail.topThreads());
                mergeLongMap(frameTotals.computeIfAbsent(mod, ignored -> new LinkedHashMap<>()), detail.topFrames());
                distinctThreadCounts.merge(mod, detail.sampledThreadCount(), Math::max);
            });
        }

        Map<String, CpuSamplingProfiler.DetailSnapshot> result = new LinkedHashMap<>();
        for (String mod : aggregateCpuWindows().keySet()) {
            result.put(mod, new CpuSamplingProfiler.DetailSnapshot(
                    topEntries(threadTotals.get(mod), 5),
                    topEntries(frameTotals.get(mod), 5),
                    distinctThreadCounts.getOrDefault(mod, 0)
            ));
        }
        return result;
    }

    private void mergeLongMap(Map<String, Long> target, Map<String, Long> source) {
        if (source == null) {
            return;
        }
        source.forEach((key, value) -> target.merge(key, value, Long::sum));
    }

    private Map<String, Long> topEntries(Map<String, Long> source, int limit) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> result = new LinkedHashMap<>();
        source.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private Map<String, ModTimingSnapshot> aggregateModWindows() {
        Map<String, long[]> totals = new LinkedHashMap<>();
        for (Map<String, ModTimingSnapshot> window : modWindows) {
            window.forEach((mod, snapshot) -> {
                long[] value = totals.computeIfAbsent(mod, ignored -> new long[2]);
                value[0] += snapshot.totalNanos();
                value[1] += snapshot.calls();
            });
        }

        Map<String, ModTimingSnapshot> result = new LinkedHashMap<>();
        totals.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .forEach(entry -> result.put(entry.getKey(), new ModTimingSnapshot(entry.getValue()[0], entry.getValue()[1])));
        return result;
    }

    private Map<String, RenderPhaseProfiler.PhaseSnapshot> aggregateRenderWindows() {
        Map<String, long[]> totals = new LinkedHashMap<>();
        Map<String, Map<String, Long>> likelyOwnerTotals = new LinkedHashMap<>();
        Map<String, Map<String, Long>> likelyFrameTotals = new LinkedHashMap<>();
        for (Map<String, RenderPhaseProfiler.PhaseSnapshot> window : renderWindows) {
            window.forEach((phase, snapshot) -> {
                long[] value = totals.computeIfAbsent(phase, ignored -> new long[4]);
                value[0] += snapshot.cpuNanos();
                value[1] += snapshot.cpuCalls();
                value[2] += snapshot.gpuNanos();
                value[3] += snapshot.gpuCalls();
                mergeLongMap(likelyOwnerTotals.computeIfAbsent(phase, ignored -> new LinkedHashMap<>()), snapshot.likelyOwners());
                mergeLongMap(likelyFrameTotals.computeIfAbsent(phase, ignored -> new LinkedHashMap<>()), snapshot.likelyFrames());
            });
        }

        Map<String, RenderPhaseProfiler.PhaseSnapshot> result = new LinkedHashMap<>();
        totals.entrySet().stream()
                .sorted((a, b) -> Long.compare((b.getValue()[0] + b.getValue()[2]), (a.getValue()[0] + a.getValue()[2])))
                .forEach(entry -> result.put(entry.getKey(), new RenderPhaseProfiler.PhaseSnapshot(
                        entry.getValue()[0],
                        entry.getValue()[1],
                        entry.getValue()[2],
                        entry.getValue()[3],
                        renderWindows.stream()
                                .map(window -> window.get(entry.getKey()))
                                .filter(java.util.Objects::nonNull)
                                .map(RenderPhaseProfiler.PhaseSnapshot::ownerMod)
                                .filter(owner -> owner != null && !owner.isBlank())
                                .findFirst()
                                .orElse("shared/render"),
                        topEntries(likelyOwnerTotals.get(entry.getKey()), 4),
                        topEntries(likelyFrameTotals.get(entry.getKey()), 4)
                )));
        return result;
    }

    private <T> void pushWindow(Deque<Map<String, T>> deque, Map<String, T> window) {
        deque.addLast(window);
        while (deque.size() > WINDOW_SIZE) {
            deque.removeFirst();
        }
    }

    private String buildHtmlReport(Map<String, Object> export) {
        Map<String, Object> executiveSummary = buildExecutiveSummary();
        Map<String, Object> summary = buildExportSummary();
        String diagnosis = buildDiagnosis();
        StringBuilder html = new StringBuilder();
        html.append("<html><head><meta charset='utf-8'><title>Task Manager Session</title><style>")
                .append("body{font-family:Segoe UI,Arial,sans-serif;background:#0f1115;color:#e5e7eb;margin:24px;}h1,h2{color:#f8fafc;}section{background:#171a21;border:1px solid #2b3240;border-radius:10px;padding:16px;margin:12px 0;}code{color:#93c5fd;}table{border-collapse:collapse;width:100%;}td,th{border-bottom:1px solid #2b3240;padding:6px 8px;text-align:left;} .warn{color:#fbbf24;} .good{color:#86efac;}")
                .append("</style></head><body>");
        html.append("<h1 id='top'>Task Manager Session Report</h1>");
        html.append("<section><strong>Jump to:</strong> ")
                .append("<a href='#overview'>Overview</a> | ")
                .append("<a href='#summary'>Summary</a> | ")
                .append("<a href='#findings'>Findings</a> | ")
                .append("<a href='#startup'>Startup</a> | ")
                .append("<a href='#spikes'>Spikes</a></section>");
        html.append("<section id='overview'><h2>Executive Summary</h2><pre>").append(escapeHtml(EXPORT_GSON.toJson(executiveSummary))).append("</pre></section>");
        html.append("<section><h2>Diagnosis</h2><p>").append(escapeHtml(diagnosis)).append("</p></section>");
        html.append("<section><h2>Metadata</h2><pre>").append(escapeHtml(EXPORT_GSON.toJson(buildExportMetadata()))).append("</pre></section>");
        html.append("<section><h2>Attribution</h2><p>Raw shows true-owned work. Effective folds shared/runtime buckets back into concrete mods for readability.</p><pre>").append(escapeHtml(EXPORT_GSON.toJson(buildAttributionExport()))).append("</pre></section>");
        html.append("<section><h2>Render Phase Owners</h2><pre>").append(escapeHtml(EXPORT_GSON.toJson(buildRenderPhaseOwnerSummary()))).append("</pre></section>");
        html.append("<section><h2>Shared Bucket Breakdowns</h2><pre>").append(escapeHtml(EXPORT_GSON.toJson(buildSharedBucketBreakdownExport()))).append("</pre></section>");
        html.append("<section><h2>Performance Overview</h2><table>");
        html.append("<tr><th>Frame Budget</th><td>").append(escapeHtml(String.format(Locale.ROOT, "%d FPS / %.2f ms", ConfigManager.getFrameBudgetTargetFps(), ConfigManager.getFrameBudgetTargetFrameMs()))).append("</td></tr>");
        html.append("<tr><th>Budget Breaches</th><td>").append(escapeHtml(String.valueOf(summary.get("frameBudgetBreaches")))).append("</td></tr>");
        html.append("<tr><th>VRAM Peak</th><td>").append(escapeHtml(String.format(Locale.ROOT, "%.1f MB", ((Number) summary.getOrDefault("vramPeakMb", 0.0)).doubleValue()))).append("</td></tr>");
        html.append("<tr><th>Network Peaks</th><td>").append(escapeHtml(formatBytesPerSecond(((Number) summary.getOrDefault("networkInboundPeakBytesPerSecond", 0L)).longValue()) + " in | " + formatBytesPerSecond(((Number) summary.getOrDefault("networkOutboundPeakBytesPerSecond", 0L)).longValue()) + " out")).append("</td></tr>");
        html.append("<tr><th>Disk Peaks</th><td>").append(escapeHtml(formatBytesPerSecond(((Number) summary.getOrDefault("diskReadPeakBytesPerSecond", 0L)).longValue()) + " read | " + formatBytesPerSecond(((Number) summary.getOrDefault("diskWritePeakBytesPerSecond", 0L)).longValue()) + " write")).append("</td></tr>");
        html.append("<tr><th>Chunk Activity Peak</th><td>").append(escapeHtml(String.valueOf(summary.getOrDefault("chunkActivityPeak", 0)))).append("</td></tr>");
        html.append("</table></section>");
        html.append("<section id='summary'><h2>Summary</h2><table>");
        for (Map.Entry<String, Object> entry : summary.entrySet()) {
            html.append("<tr><th>").append(escapeHtml(entry.getKey())).append("</th><td>").append(escapeHtml(String.valueOf(entry.getValue()))).append("</td></tr>");
        }
        html.append("</table></section>");
        html.append("<section><h2>Highlights</h2><table>");
        html.append("<tr><th>Worst frame</th><td>").append(escapeHtml(String.valueOf(summary.get("worstFrame")))).append("</td></tr>");
        html.append("<tr><th>Worst MSPT spike</th><td>").append(escapeHtml(String.valueOf(summary.get("worstMsptSpike")))).append("</td></tr>");
        html.append("<tr><th>Top CPU mods</th><td>").append(escapeHtml(String.valueOf(summary.get("topCpuMods")))).append("</td></tr>");
        html.append("<tr><th>Top GPU mods</th><td>").append(escapeHtml(String.valueOf(summary.get("topGpuMods")))).append("</td></tr>");
        html.append("<tr><th>Top memory mods</th><td>").append(escapeHtml(String.valueOf(summary.get("topMemoryMods")))).append("</td></tr>");
        html.append("<tr><th>Hot chunk</th><td>").append(escapeHtml(String.valueOf(summary.get("hotChunkSummary")))).append("</td></tr>");
        html.append("<tr><th>Block entity classes</th><td>").append(escapeHtml(String.valueOf(summary.get("blockEntityClasses")))).append("</td></tr>");
        html.append("<tr><th>Sensors</th><td>").append(escapeHtml(String.valueOf(summary.get("sensorDiagnostics")))).append("</td></tr>");
        html.append("</table></section>");
        html.append("<section id='findings'><h2>Rule Findings</h2><ul>");
        for (RuleFinding finding : latestRuleFindings) {
            html.append("<li><strong>").append(escapeHtml(finding.category())).append("</strong> [").append(escapeHtml(finding.severity())).append("] ")
                    .append(escapeHtml(finding.message())).append(" <code>").append(escapeHtml(finding.confidence())).append("</code><br><small>").append(escapeHtml(finding.metricSummary())).append("</small><br><small>").append(escapeHtml(finding.details())).append("</small><br><small><strong>Next:</strong> ").append(escapeHtml(finding.nextStep())).append("</small></li>");
        }
        html.append("</ul></section>");
        html.append("<section><h2>Conflict Findings</h2><ul>");
        for (ConflictEdge edge : latestConflictEdges) {
            html.append("<li>")
                    .append(escapeHtml(edge.waiterMod()))
                    .append(" -> ")
                    .append(escapeHtml(edge.ownerMod()))
                    .append(" via ")
                    .append(escapeHtml(edge.lockName()))
                    .append(" <code>")
                    .append(escapeHtml(edge.confidence()))
                    .append("</code><br><small>")
                    .append(escapeHtml(String.format(Locale.ROOT, "obs %d | slowdown %d | blocked %d ms | waited %d ms", edge.observations(), edge.slowdownObservations(), edge.blockedTimeMs(), edge.waitedTimeMs())))
                    .append("</small></li>");
        }
        html.append("</ul></section>");
        html.append("<section><h2>Entity Hotspots</h2><ul>");
        for (EntityHotspot hotspot : latestEntityHotspots) {
            html.append("<li>").append(escapeHtml(hotspot.className())).append(" x").append(hotspot.count()).append(" - ").append(escapeHtml(hotspot.heuristic())).append("</li>");
        }
        html.append("</ul></section>");
        html.append("<section id='startup'><h2>Startup</h2><pre>").append(escapeHtml(EXPORT_GSON.toJson(buildStartupSummary()))).append("</pre></section>");
        html.append("<section><h2>Startup Slowest Mods</h2><pre>").append(escapeHtml(EXPORT_GSON.toJson(buildStartupSummary().get("slowestMods")))).append("</pre></section>");
        html.append("<section><h2>Block Entity Hotspots</h2><ul>");
        for (BlockEntityHotspot hotspot : latestBlockEntityHotspots) {
            html.append("<li>").append(escapeHtml(hotspot.className())).append(" x").append(hotspot.count()).append(" - ").append(escapeHtml(hotspot.heuristic())).append("</li>");
        }
        html.append("</ul></section>");
        html.append("<section><h2>Locks / Waiting Threads</h2><ul>");
        for (String lockSummary : latestLockSummaries) {
            html.append("<li>").append(escapeHtml(lockSummary)).append("</li>");
        }
        html.append("</ul></section>");
        html.append("<section id='spikes'><h2>Spike Bookmarks</h2><pre>").append(escapeHtml(EXPORT_GSON.toJson(buildSpikeBookmarks()))).append("</pre></section>");
        html.append("<section><h2>Network Spike Bookmarks</h2><pre>").append(escapeHtml(EXPORT_GSON.toJson(NetworkPacketProfiler.getInstance().getSpikeHistory()))).append("</pre></section>");
                html.append("<section><h2>Stutter Jump Snapshots</h2><pre>").append(escapeHtml(EXPORT_GSON.toJson(new ArrayList<>(stutterJumpSnapshots)))).append("</pre></section>");
        html.append("</body></html>");
        return html.toString();
    }

    private long countFrameBudgetBreaches() {
        double targetFrameMs = ConfigManager.getFrameBudgetTargetFrameMs();
        return sessionHistory.stream().filter(point -> point.frameTimeMs() > targetFrameMs).count();
    }

    private long peakSessionValue(java.util.function.ToLongFunction<SessionPoint> extractor) {
        return sessionHistory.stream().mapToLong(extractor).max().orElse(0L);
    }

    private long peakSystemMetricValue(java.util.function.ToLongFunction<SystemMetricsProfiler.Snapshot> extractor) {
        return sessionHistory.stream()
                .map(SessionPoint::systemMetrics)
                .filter(java.util.Objects::nonNull)
                .mapToLong(extractor)
                .max()
                .orElse(0L);
    }

    private int peakChunkActivity() {
        return sessionHistory.stream()
                .map(SessionPoint::systemMetrics)
                .filter(java.util.Objects::nonNull)
                .mapToInt(snapshot -> snapshot.chunksGenerating() + snapshot.chunksMeshing() + snapshot.chunksUploading())
                .max()
                .orElse(0);
    }

    private String formatExportTimestamp(long epochMillis) {
        return java.time.Instant.ofEpochMilli(epochMillis).toString();
    }

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private void clearLiveWindows() {
        cpuWindows.clear();
        cpuDetailWindows.clear();
        modWindows.clear();
        renderWindows.clear();
        conflictWindows.clear();
        spikes.clear();
        latestHotChunks = List.of();
        latestEntityHotspots = List.of();
        latestBlockEntityHotspots = List.of();
        latestConflictEdges = List.of();
        latestLockSummaries = List.of();
        latestRuleFindings = List.of();
        stutterJumpSnapshots.clear();
        lastStutterScore = 0.0;
        latestPerformanceAlert = null;
        performanceAlertFlashUntilMillis = 0L;
        frameAlertConsecutiveBreaches = 0;
        serverAlertConsecutiveBreaches = 0;
        lastWorldScanDurationMillis = 0L;
        lastChunkCountsAtMillis = 0L;
        NetworkPacketProfiler.getInstance().reset();
        ThreadLoadProfiler.getInstance().reset();
        ChunkWorkProfiler.getInstance().reset();
        EntityCostProfiler.getInstance().reset();
        ShaderCompilationProfiler.getInstance().reset();
        lastSeenFrameSequence = 0;
    }

    private void clearSessionState() {
        sessionHistory.clear();
        hotChunkHistory.clear();
        chunkActivityHistory.clear();
        sessionLoggingStartedAtMillis = 0L;
        sessionLogging = false;
        sessionRecorded = false;
        sessionRecordedAtMillis = 0L;
        sessionMissedSamples = 0;
        sessionMaxSampleGapMillis = 0L;
        sessionExpectedSampleIntervalMillis = 50L;
        lastPerformanceAlertAtMillis.clear();
    }
}





























