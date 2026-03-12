package wueffi.taskmanager.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.registry.entry.RegistryEntry;
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
import java.util.function.ToLongFunction;
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
            Map<String, Double> memoryEffectiveMbByMod
    ) {}

    private record EffectiveCpuAttribution(Map<String, CpuSamplingProfiler.Snapshot> displaySnapshots, Map<String, Long> redistributedSamplesByMod, Map<String, Long> redistributedRenderSamplesByMod, long totalSamples, long totalRenderSamples) {}

    private record EffectiveGpuAttribution(Map<String, Long> gpuNanosByMod, Map<String, Long> renderSamplesByMod, Map<String, Long> redistributedGpuNanosByMod, Map<String, Long> redistributedRenderSamplesByMod, long totalGpuNanos, long totalRenderSamples) {}

    private record EffectiveMemoryAttribution(Map<String, Long> displayBytes, Map<String, Long> redistributedBytesByMod, long totalBytes) {}

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
    private static final Gson EXPORT_GSON = new GsonBuilder().serializeSpecialFloatingPointValues().setPrettyPrinting().create();
    private static final Pattern CHUNK_DEBUG_PATTERN = Pattern.compile("C:\\s*(\\d+)/(\\d+)");

    private final Deque<Map<String, CpuSamplingProfiler.Snapshot>> cpuWindows = new ArrayDeque<>();
    private final Deque<Map<String, CpuSamplingProfiler.DetailSnapshot>> cpuDetailWindows = new ArrayDeque<>();
    private final Deque<Map<String, ModTimingSnapshot>> modWindows = new ArrayDeque<>();
    private final Deque<Map<String, RenderPhaseProfiler.PhaseSnapshot>> renderWindows = new ArrayDeque<>();
    private final Deque<SpikeCapture> spikes = new ArrayDeque<>();
    private final Deque<SessionPoint> sessionHistory = new ArrayDeque<>();
    private final Deque<List<HotChunkSnapshot>> hotChunkHistory = new ArrayDeque<>();
    private final Map<Long, Deque<Integer>> chunkActivityHistory = new LinkedHashMap<>();


    private volatile List<HotChunkSnapshot> latestHotChunks = List.of();
    private volatile List<EntityHotspot> latestEntityHotspots = List.of();
    private volatile List<BlockEntityHotspot> latestBlockEntityHotspots = List.of();
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
    private volatile EntityCounts latestEntityCounts = EntityCounts.empty();
    private volatile ChunkCounts latestChunkCounts = ChunkCounts.empty();
    private volatile boolean sessionLogging;
    private volatile long sessionLoggingStartedAtMillis;
    private volatile boolean sessionRecorded;
    private volatile long sessionRecordedAtMillis;
    private volatile int sessionMissedSamples;
    private volatile long sessionMaxSampleGapMillis;
    private volatile long sessionExpectedSampleIntervalMillis = 50L;
    private long lastSeenFrameSequence = 0;
    private long lastSnapshotPublishedAtMillis = 0L;

    public void initialize() {
        mode = ConfigManager.getCaptureMode();
        CpuSamplingProfiler.getInstance().start();
        publishSnapshot(true);
    }

    public void onScreenOpened() {
        screenOpen = true;
        if (mode == CaptureMode.OPEN_ONLY || mode == CaptureMode.MANUAL_DEEP) {
            clearRollingWindows();
            RenderPhaseProfiler.getInstance().reset();
            ModTimingProfiler.getInstance().reset();
            CpuSamplingProfiler.getInstance().reset();
            FlamegraphProfiler.getInstance().reset();
            TickProfiler.getInstance().reset();
        }
        MemoryProfiler.getInstance().sampleJvm();
        publishSnapshot(true);
    }

    public void onScreenClosed() {
        screenOpen = false;
        if (mode == CaptureMode.OPEN_ONLY) {
            clearRollingWindows();
            publishSnapshot(true);
        }
    }

    public void cycleMode() {
        setMode(mode.next());
    }

    public void setMode(CaptureMode mode) {
        this.mode = mode;
        ConfigManager.setCaptureMode(mode);
        clearRollingWindows();
        publishSnapshot(true);
    }

    public boolean isCaptureActive() {
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
        return switch (getLiveCollectionMode()) {
            case SCREEN, SESSION, CAPTURE -> true;
            default -> false;
        };
    }

    public ProfilerSnapshot getCurrentSnapshot() {
        return currentSnapshot;
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
        sessionHistory.clear();
        hotChunkHistory.clear();
        chunkActivityHistory.clear();
        latestHotChunks = List.of();
        latestRuleFindings = List.of();
        sessionLogging = true;
        sessionRecorded = false;
        sessionRecordedAtMillis = 0L;
        sessionMissedSamples = 0;
        sessionMaxSampleGapMillis = 0L;
        sessionExpectedSampleIntervalMillis = 50L;
        sessionLoggingStartedAtMillis = System.currentTimeMillis();
        publishSnapshot(true);
    }

    public void onClientTickEnd(MinecraftClient client) {
        latestEntityCounts = sampleEntityCounts(client);
        latestChunkCounts = sampleChunkCounts(client);
        latestHotChunks = sampleHotChunks(client);
        latestEntityHotspots = sampleEntityHotspots(client);
        latestBlockEntityHotspots = sampleBlockEntityHotspots(client);

        if (shouldCollectFrameMetrics() || (client.world != null && client.world.getTime() % 200 == 0)) {
            MemoryProfiler.getInstance().sampleJvm();
        }
        SystemMetricsProfiler.getInstance().sample(MemoryProfiler.getInstance().getDetailedSnapshot(), latestEntityCounts, latestChunkCounts);
        ThreadLoadProfiler.getInstance().sample();
        NetworkPacketProfiler.getInstance().drainWindow();
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

        boolean allowDeepMemory = screenOpen || mode == CaptureMode.MANUAL_DEEP;
        if (allowDeepMemory && TaskManagerScreen.isMemoryTabActive(client) && MemoryProfiler.getInstance().getLastModSampleAgeMillis() > 2000) {
            MemoryProfiler.getInstance().samplePerMod();
        }

        latestRuleFindings = buildRuleFindings();
        captureSpikeIfNeeded();
        captureStutterJumpSnapshot(client);
        recordSessionPoint();
        enforceSessionWindow(client);
        publishSnapshot(false, cpuWindow.lastSampleAgeMillis());
    }

    public java.util.List<SessionPoint> getSessionHistory() {
        return java.util.List.copyOf(sessionHistory);
    }

    public String exportSession() {
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
        export.put("sessionPoints", new ArrayList<>(sessionHistory));
        export.put("frameTimeTimelineMs", FrameTimelineProfiler.getInstance().getOrderedFrameMsHistory());
        export.put("frameTimestampTimelineNs", FrameTimelineProfiler.getInstance().getOrderedFrameTimestampHistory());
        export.put("fpsTimeline", FrameTimelineProfiler.getInstance().getOrderedFpsHistory());
        export.put("chunkPipelineTimeline", buildChunkPipelineTimeline());
        export.put("startupRows", currentSnapshot.startupRows());
        export.put("startupSummary", buildStartupSummary());
        export.put("currentSnapshot", currentSnapshot);
        export.put("attribution", buildAttributionExport());
        export.put("renderPhaseOwners", buildRenderPhaseOwnerSummary());
        export.put("sharedBucketBreakdowns", buildSharedBucketBreakdownExport());

        Path dir = FabricLoader.getInstance().getGameDir().resolve("taskmanager-sessions");
        try {
            Files.createDirectories(dir);
            long exportTimestamp = System.currentTimeMillis();
            Path file = dir.resolve("taskmanager-session-" + exportTimestamp + ".json");
            Files.writeString(file, EXPORT_GSON.toJson(export));
            Path htmlFile = dir.resolve("taskmanager-session-" + exportTimestamp + ".html");
            Files.writeString(htmlFile, buildHtmlReport(export));
            lastExportDirectory = dir;
            lastExportHtmlReport = htmlFile;
            lastExportStatus = "Exported " + file.getFileName() + " + " + htmlFile.getFileName();
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
        } catch (Exception e) {
            lastExportDirectory = null;
            lastExportHtmlReport = null;
            lastExportStatus = "Export failed: " + e.getMessage();
        }
        publishSnapshot();
        return lastExportStatus;
    }

    private void recordSessionPoint() {
        Map<String, CpuSamplingProfiler.Snapshot> cpu = aggregateCpuWindows();
        Map<String, CpuSamplingProfiler.DetailSnapshot> cpuDetails = aggregateCpuDetailWindows();
        Map<String, ModTimingSnapshot> modInvokes = aggregateModWindows();
        Map<String, RenderPhaseProfiler.PhaseSnapshot> renderPhases = aggregateRenderWindows();
        EffectiveCpuAttribution effectiveCpu = buildEffectiveCpuAttribution(cpu, modInvokes);
        EffectiveGpuAttribution rawGpuAttribution = buildEffectiveGpuAttribution(renderPhases, cpu, modInvokes, false);
        EffectiveGpuAttribution effectiveGpuAttribution = buildEffectiveGpuAttribution(renderPhases, cpu, modInvokes, true);
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
        EffectiveMemoryAttribution effectiveMemory = buildEffectiveMemoryAttribution(rawMemoryMods);
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

        long capturedAtMillis = System.currentTimeMillis();
        SessionPoint previousPoint = sessionHistory.peekLast();
        long sampleIntervalMs = previousPoint == null ? sessionExpectedSampleIntervalMillis : Math.max(0L, capturedAtMillis - previousPoint.capturedAtEpochMillis());
        int missedSamplesSincePrevious = computeMissedSamples(previousPoint == null ? 0L : previousPoint.capturedAtEpochMillis(), capturedAtMillis, (int) sessionExpectedSampleIntervalMillis);
        sessionMissedSamples += missedSamplesSincePrevious;
        sessionMaxSampleGapMillis = Math.max(sessionMaxSampleGapMillis, sampleIntervalMs);

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
                memoryEffectiveMbByMod
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
        MinecraftClient client = MinecraftClient.getInstance();
        String taskManagerVersion = FabricLoader.getInstance().getModContainer("taskmanager")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        String minecraftVersion = FabricLoader.getInstance().getModContainer("minecraft")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        String loaderVersion = FabricLoader.getInstance().getModContainer("fabricloader")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        String cpuInfo = System.getenv("PROCESSOR_IDENTIFIER");
        if (cpuInfo == null || cpuInfo.isBlank()) {
            cpuInfo = System.getProperty("os.arch", "unknown");
        }
        SystemMetricsProfiler.Snapshot system = SystemMetricsProfiler.getInstance().getSnapshot();
        String gpuInfo = (system.gpuVendor() == null || system.gpuVendor().isBlank() ? "Unknown GPU" : system.gpuVendor())
                + " | " + (system.gpuRenderer() == null || system.gpuRenderer().isBlank() ? "Unknown renderer" : system.gpuRenderer());
        int guiScale = client != null ? client.options.getGuiScale().getValue() : -1;
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

    private Map<String, Object> buildMinecraftSettings(MinecraftClient client) {
        Map<String, Object> settings = new LinkedHashMap<>();
        if (client == null) return settings;
        settings.put("renderDistance", client.options.getViewDistance().getValue());
        settings.put("simulationDistance", client.options.getSimulationDistance().getValue());
        settings.put("entityDistanceScale", client.options.getEntityDistanceScaling().getValue());
        settings.put("guiScale", client.options.getGuiScale().getValue());
        settings.put("vsync", readOptionValue(client.options, "getEnableVsync"));
        settings.put("maxFramerate", readOptionValue(client.options, "getMaxFps"));
        return settings;
    }

    private Map<String, Object> buildGraphicsModSettings(MinecraftClient client) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("irisDetected", FabricLoader.getInstance().isModLoaded("iris"));
        settings.put("sodiumDetected", FabricLoader.getInstance().isModLoaded("sodium"));
        settings.put("shaderPack", detectShaderPackName());
        settings.put("chunkUpdateThreads", detectSodiumChunkThreads());
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

    private boolean isSharedAttributionBucket(String modId) {
        return modId != null && (modId.startsWith("shared/") || modId.startsWith("runtime/"));
    }

    private EffectiveCpuAttribution buildEffectiveCpuAttribution(Map<String, CpuSamplingProfiler.Snapshot> rawCpu, Map<String, ModTimingSnapshot> invokes) {
        LinkedHashMap<String, CpuSamplingProfiler.Snapshot> concrete = new LinkedHashMap<>();
        long sharedTotalSamples = 0L;
        long sharedClientSamples = 0L;
        long sharedRenderSamples = 0L;
        for (Map.Entry<String, CpuSamplingProfiler.Snapshot> entry : rawCpu.entrySet()) {
            String modId = entry.getKey();
            CpuSamplingProfiler.Snapshot sample = entry.getValue();
            if (isSharedAttributionBucket(modId)) {
                sharedTotalSamples += sample.totalSamples();
                sharedClientSamples += sample.clientSamples();
                sharedRenderSamples += sample.renderSamples();
            } else {
                concrete.put(modId, sample);
            }
        }
        if (concrete.isEmpty()) {
            long totalSamples = rawCpu.values().stream().mapToLong(CpuSamplingProfiler.Snapshot::totalSamples).sum();
            long totalRenderSamples = rawCpu.values().stream().mapToLong(CpuSamplingProfiler.Snapshot::renderSamples).sum();
            return new EffectiveCpuAttribution(new LinkedHashMap<>(rawCpu), Map.of(), Map.of(), totalSamples, totalRenderSamples);
        }

        Map<String, Double> totalWeights = buildCpuWeightMap(concrete, invokes, CpuSamplingProfiler.Snapshot::totalSamples);
        Map<String, Double> clientWeights = buildCpuWeightMap(concrete, invokes, CpuSamplingProfiler.Snapshot::clientSamples);
        Map<String, Double> renderWeights = buildCpuWeightMap(concrete, invokes, CpuSamplingProfiler.Snapshot::renderSamples);
        Map<String, Long> redistributedTotals = distributeLongProportionally(sharedTotalSamples, totalWeights);
        Map<String, Long> redistributedClients = distributeLongProportionally(sharedClientSamples, clientWeights);
        Map<String, Long> redistributedRenders = distributeLongProportionally(sharedRenderSamples, renderWeights);

        LinkedHashMap<String, CpuSamplingProfiler.Snapshot> display = new LinkedHashMap<>();
        for (Map.Entry<String, CpuSamplingProfiler.Snapshot> entry : concrete.entrySet()) {
            String modId = entry.getKey();
            CpuSamplingProfiler.Snapshot sample = entry.getValue();
            display.put(modId, new CpuSamplingProfiler.Snapshot(
                    sample.totalSamples() + redistributedTotals.getOrDefault(modId, 0L),
                    sample.clientSamples() + redistributedClients.getOrDefault(modId, 0L),
                    sample.renderSamples() + redistributedRenders.getOrDefault(modId, 0L)
            ));
        }

        long totalSamples = display.values().stream().mapToLong(CpuSamplingProfiler.Snapshot::totalSamples).sum();
        long totalRenderSamples = display.values().stream().mapToLong(CpuSamplingProfiler.Snapshot::renderSamples).sum();
        return new EffectiveCpuAttribution(display, redistributedTotals, redistributedRenders, totalSamples, totalRenderSamples);
    }

    private EffectiveGpuAttribution buildEffectiveGpuAttribution(Map<String, RenderPhaseProfiler.PhaseSnapshot> renderPhases, Map<String, CpuSamplingProfiler.Snapshot> rawCpu, Map<String, ModTimingSnapshot> invokes, boolean effectiveView) {
        EffectiveCpuAttribution effectiveCpu = buildEffectiveCpuAttribution(rawCpu, invokes);
        Map<String, CpuSamplingProfiler.Snapshot> renderSource = effectiveView ? effectiveCpu.displaySnapshots() : rawCpu;
        LinkedHashMap<String, Long> renderSamplesByMod = new LinkedHashMap<>();
        renderSource.forEach((modId, sample) -> {
            if (sample.renderSamples() > 0L) {
                renderSamplesByMod.put(modId, sample.renderSamples());
            }
        });

        LinkedHashMap<String, Long> directGpuByMod = new LinkedHashMap<>();
        long sharedGpuNanos = 0L;
        for (RenderPhaseProfiler.PhaseSnapshot phase : renderPhases.values()) {
            if (phase.gpuNanos() <= 0L) {
                continue;
            }
            String ownerMod = phase.ownerMod() == null || phase.ownerMod().isBlank() ? "shared/render" : phase.ownerMod();
            if (isSharedAttributionBucket(ownerMod)) {
                sharedGpuNanos += phase.gpuNanos();
            } else {
                directGpuByMod.merge(ownerMod, phase.gpuNanos(), Long::sum);
            }
        }

        if (!effectiveView) {
            if (sharedGpuNanos > 0L) {
                directGpuByMod.merge("shared/render", sharedGpuNanos, Long::sum);
                renderSamplesByMod.putIfAbsent("shared/render", 0L);
            }
            long totalGpuNanos = Math.max(1L, directGpuByMod.values().stream().mapToLong(Long::longValue).sum());
            long totalRenderSamples = Math.max(1L, renderSamplesByMod.values().stream().mapToLong(Long::longValue).sum());
            return new EffectiveGpuAttribution(directGpuByMod, renderSamplesByMod, Map.of(), Map.of(), totalGpuNanos, totalRenderSamples);
        }

        LinkedHashMap<String, Long> effectiveGpuByMod = new LinkedHashMap<>();
        renderSamplesByMod.keySet().forEach(modId -> effectiveGpuByMod.put(modId, directGpuByMod.getOrDefault(modId, 0L)));
        directGpuByMod.forEach(effectiveGpuByMod::putIfAbsent);
        Map<String, Double> weights = buildGpuWeightMap(renderSamplesByMod, effectiveGpuByMod);
        Map<String, Long> redistributedGpu = distributeLongProportionally(sharedGpuNanos, weights);
        redistributedGpu.forEach((modId, gpuNanos) -> effectiveGpuByMod.merge(modId, gpuNanos, Long::sum));
        effectiveGpuByMod.entrySet().removeIf(entry -> entry.getValue() <= 0L && renderSamplesByMod.getOrDefault(entry.getKey(), 0L) <= 0L);
        long totalGpuNanos = Math.max(1L, effectiveGpuByMod.values().stream().mapToLong(Long::longValue).sum());
        long totalRenderSamples = Math.max(1L, renderSamplesByMod.values().stream().mapToLong(Long::longValue).sum());
        return new EffectiveGpuAttribution(effectiveGpuByMod, renderSamplesByMod, redistributedGpu, effectiveCpu.redistributedRenderSamplesByMod(), totalGpuNanos, totalRenderSamples);
    }

    private EffectiveMemoryAttribution buildEffectiveMemoryAttribution(Map<String, Long> rawMemoryMods) {
        LinkedHashMap<String, Long> concrete = new LinkedHashMap<>();
        long sharedBytes = 0L;
        for (Map.Entry<String, Long> entry : rawMemoryMods.entrySet()) {
            if (isSharedAttributionBucket(entry.getKey())) {
                sharedBytes += entry.getValue();
            } else {
                concrete.put(entry.getKey(), entry.getValue());
            }
        }
        if (concrete.isEmpty()) {
            long totalBytes = rawMemoryMods.values().stream().mapToLong(Long::longValue).sum();
            return new EffectiveMemoryAttribution(new LinkedHashMap<>(rawMemoryMods), Map.of(), totalBytes);
        }
        Map<String, Double> weights = buildMemoryWeightMap(concrete);
        Map<String, Long> redistributed = distributeLongProportionally(sharedBytes, weights);
        LinkedHashMap<String, Long> display = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : concrete.entrySet()) {
            display.put(entry.getKey(), entry.getValue() + redistributed.getOrDefault(entry.getKey(), 0L));
        }
        long totalBytes = display.values().stream().mapToLong(Long::longValue).sum();
        return new EffectiveMemoryAttribution(display, redistributed, totalBytes);
    }

    private Map<String, Double> buildCpuWeightMap(Map<String, CpuSamplingProfiler.Snapshot> concrete, Map<String, ModTimingSnapshot> invokes, ToLongFunction<CpuSamplingProfiler.Snapshot> extractor) {
        LinkedHashMap<String, Double> weights = new LinkedHashMap<>();
        double total = 0.0;
        for (Map.Entry<String, CpuSamplingProfiler.Snapshot> entry : concrete.entrySet()) {
            double weight = Math.max(0L, extractor.applyAsLong(entry.getValue()));
            weights.put(entry.getKey(), weight);
            total += weight;
        }
        if (total > 0.0) {
            return weights;
        }
        double invokeTotal = 0.0;
        for (String modId : concrete.keySet()) {
            double weight = Math.max(0L, invokes.getOrDefault(modId, new ModTimingSnapshot(0, 0)).calls());
            weights.put(modId, weight);
            invokeTotal += weight;
        }
        if (invokeTotal > 0.0) {
            return weights;
        }
        for (String modId : concrete.keySet()) {
            weights.put(modId, 1.0);
        }
        return weights;
    }

    private Map<String, Double> buildMemoryWeightMap(Map<String, Long> concrete) {
        LinkedHashMap<String, Double> weights = new LinkedHashMap<>();
        double total = 0.0;
        for (Map.Entry<String, Long> entry : concrete.entrySet()) {
            double weight = Math.max(0L, entry.getValue());
            weights.put(entry.getKey(), weight);
            total += weight;
        }
        if (total > 0.0) {
            return weights;
        }
        for (String modId : concrete.keySet()) {
            weights.put(modId, 1.0);
        }
        return weights;
    }

    private Map<String, Double> buildGpuWeightMap(Map<String, Long> renderSamplesByMod, Map<String, Long> directGpuByMod) {
        LinkedHashMap<String, Double> weights = new LinkedHashMap<>();
        double total = 0.0;
        for (Map.Entry<String, Long> entry : renderSamplesByMod.entrySet()) {
            if (isSharedAttributionBucket(entry.getKey())) {
                continue;
            }
            double weight = Math.max(0L, entry.getValue());
            weights.put(entry.getKey(), weight);
            total += weight;
        }
        if (total > 0.0) {
            return weights;
        }
        for (Map.Entry<String, Long> entry : directGpuByMod.entrySet()) {
            if (isSharedAttributionBucket(entry.getKey())) {
                continue;
            }
            double weight = Math.max(0L, entry.getValue());
            weights.put(entry.getKey(), weight);
            total += weight;
        }
        if (total > 0.0) {
            return weights;
        }
        for (String modId : renderSamplesByMod.keySet()) {
            if (!isSharedAttributionBucket(modId)) {
                weights.put(modId, 1.0);
            }
        }
        if (weights.isEmpty()) {
            directGpuByMod.keySet().stream().filter(modId -> !isSharedAttributionBucket(modId)).forEach(modId -> weights.put(modId, 1.0));
        }
        return weights;
    }

    private Map<String, Long> distributeLongProportionally(long total, Map<String, Double> weights) {
        if (total <= 0L || weights.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        ArrayList<Map.Entry<String, Double>> entries = new ArrayList<>(weights.entrySet());
        double weightSum = entries.stream().mapToDouble(entry -> Math.max(0.0, entry.getValue())).sum();
        if (weightSum <= 0.0) {
            for (Map.Entry<String, Double> entry : entries) {
                result.put(entry.getKey(), 0L);
            }
            return result;
        }
        LinkedHashMap<String, Double> remainders = new LinkedHashMap<>();
        long assigned = 0L;
        for (Map.Entry<String, Double> entry : entries) {
            double exact = total * Math.max(0.0, entry.getValue()) / weightSum;
            long whole = (long) Math.floor(exact);
            result.put(entry.getKey(), whole);
            remainders.put(entry.getKey(), exact - whole);
            assigned += whole;
        }
        long remainder = total - assigned;
        if (remainder > 0L) {
            entries.sort((a, b) -> Double.compare(remainders.getOrDefault(b.getKey(), 0.0), remainders.getOrDefault(a.getKey(), 0.0)));
            for (int i = 0; i < remainder; i++) {
                String modId = entries.get(i % entries.size()).getKey();
                result.put(modId, result.getOrDefault(modId, 0L) + 1L);
            }
        }
        return result;
    }

    private Map<String, Double> buildCpuPercentByMod(Map<String, CpuSamplingProfiler.Snapshot> snapshots) {
        LinkedHashMap<String, Double> result = new LinkedHashMap<>();
        double total = Math.max(1L, snapshots.values().stream().mapToLong(CpuSamplingProfiler.Snapshot::totalSamples).sum());
        snapshots.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().totalSamples(), a.getValue().totalSamples()))
                .forEach(entry -> result.put(entry.getKey(), entry.getValue().totalSamples() * 100.0 / total));
        return result;
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

    private Map<String, Object> buildAttributionExport() {
        Map<String, Object> export = new LinkedHashMap<>();
        EffectiveCpuAttribution effectiveCpu = buildEffectiveCpuAttribution(currentSnapshot.cpuMods(), currentSnapshot.modInvokes());
        EffectiveGpuAttribution rawGpu = buildEffectiveGpuAttribution(currentSnapshot.renderPhases(), currentSnapshot.cpuMods(), currentSnapshot.modInvokes(), false);
        EffectiveGpuAttribution effectiveGpu = buildEffectiveGpuAttribution(currentSnapshot.renderPhases(), currentSnapshot.cpuMods(), currentSnapshot.modInvokes(), true);
        EffectiveMemoryAttribution effectiveMemory = buildEffectiveMemoryAttribution(currentSnapshot.memoryMods());

        Map<String, Object> cpu = new LinkedHashMap<>();
        cpu.put("rawTop", topCpuRows(currentSnapshot.cpuMods(), Math.max(1L, currentSnapshot.cpuMods().values().stream().mapToLong(CpuSamplingProfiler.Snapshot::totalSamples).sum())));
        cpu.put("effectiveTop", topCpuRows(effectiveCpu.displaySnapshots(), Math.max(1L, effectiveCpu.totalSamples())));
        cpu.put("redistributedSamplesByMod", effectiveCpu.redistributedSamplesByMod());
        export.put("cpu", cpu);

        Map<String, Object> gpu = new LinkedHashMap<>();
        gpu.put("rawTop", topGpuRows(rawGpu));
        gpu.put("effectiveTop", topGpuRows(effectiveGpu));
        gpu.put("redistributedGpuNanosByMod", effectiveGpu.redistributedGpuNanosByMod());
        gpu.put("redistributedRenderSamplesByMod", effectiveGpu.redistributedRenderSamplesByMod());
        export.put("gpu", gpu);

        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("rawTop", topMemoryRows(currentSnapshot.memoryMods()));
        memory.put("effectiveTop", topMemoryRows(effectiveMemory.displayBytes()));
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
                    row.put("owner", entry.getValue().ownerMod() == null || entry.getValue().ownerMod().isBlank() ? "shared/render" : entry.getValue().ownerMod());
                    row.put("cpuMs", entry.getValue().cpuNanos() / 1_000_000.0);
                    row.put("gpuMs", entry.getValue().gpuNanos() / 1_000_000.0);
                    row.put("cpuCalls", entry.getValue().cpuCalls());
                    row.put("gpuCalls", entry.getValue().gpuCalls());
                    return row;
                })
                .toList();
    }

    private Map<String, Object> buildSharedBucketBreakdownExport() {
        Map<String, Object> export = new LinkedHashMap<>();
        Map<String, Object> cpu = new LinkedHashMap<>();
        currentSnapshot.cpuDetails().entrySet().stream()
                .filter(entry -> isSharedAttributionBucket(entry.getKey()))
                .forEach(entry -> cpu.put(entry.getKey(), entry.getValue().topFrames()));
        export.put("cpu", cpu);

        Map<String, Object> gpu = new LinkedHashMap<>();
        currentSnapshot.renderPhases().entrySet().stream()
                .filter(entry -> {
                    String owner = entry.getValue().ownerMod() == null || entry.getValue().ownerMod().isBlank() ? "shared/render" : entry.getValue().ownerMod();
                    return isSharedAttributionBucket(owner);
                })
                .forEach(entry -> gpu.put(entry.getKey(), Map.of(
                        "owner", entry.getValue().ownerMod() == null || entry.getValue().ownerMod().isBlank() ? "shared/render" : entry.getValue().ownerMod(),
                        "cpuMs", entry.getValue().cpuNanos() / 1_000_000.0,
                        "gpuMs", entry.getValue().gpuNanos() / 1_000_000.0
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

    private List<Map<String, Object>> topCpuRows(Map<String, CpuSamplingProfiler.Snapshot> snapshots, long totalSamples) {
        return snapshots.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().totalSamples(), a.getValue().totalSamples()))
                .limit(8)
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("mod", entry.getKey());
                    row.put("samples", entry.getValue().totalSamples());
                    row.put("percent", entry.getValue().totalSamples() * 100.0 / Math.max(1L, totalSamples));
                    return row;
                })
                .toList();
    }

    private List<Map<String, Object>> topGpuRows(EffectiveGpuAttribution attribution) {
        return attribution.gpuNanosByMod().entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(8)
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("mod", entry.getKey());
                    row.put("gpuMs", entry.getValue() / 1_000_000.0);
                    row.put("percent", entry.getValue() * 100.0 / Math.max(1L, attribution.totalGpuNanos()));
                    row.put("renderSamples", attribution.renderSamplesByMod().getOrDefault(entry.getKey(), 0L));
                    return row;
                })
                .toList();
    }

    private List<Map<String, Object>> topMemoryRows(Map<String, Long> memoryByMod) {
        long total = Math.max(1L, memoryByMod.values().stream().mapToLong(Long::longValue).sum());
        return memoryByMod.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(8)
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("mod", entry.getKey());
                    row.put("memoryMb", entry.getValue() / (1024.0 * 1024.0));
                    row.put("percent", entry.getValue() * 100.0 / total);
                    return row;
                })
                .toList();
    }

    private List<Map<String, Object>> buildTopCpuModSummary() {
        EffectiveCpuAttribution effectiveCpu = buildEffectiveCpuAttribution(currentSnapshot.cpuMods(), currentSnapshot.modInvokes());
        long totalCpuSamples = Math.max(1L, effectiveCpu.totalSamples());
        return effectiveCpu.displaySnapshots().entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().totalSamples(), a.getValue().totalSamples()))
                .limit(5)
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("mod", entry.getKey());
                    row.put("samples", entry.getValue().totalSamples());
                    row.put("percentCpu", entry.getValue().totalSamples() * 100.0 / totalCpuSamples);
                    row.put("rawSamples", currentSnapshot.cpuMods().getOrDefault(entry.getKey(), new CpuSamplingProfiler.Snapshot(0, 0, 0)).totalSamples());
                    row.put("redistributedSamples", effectiveCpu.redistributedSamplesByMod().getOrDefault(entry.getKey(), 0L));
                    row.put("threadCount", currentSnapshot.cpuDetails().get(entry.getKey()) == null ? 0 : currentSnapshot.cpuDetails().get(entry.getKey()).sampledThreadCount());
                    return row;
                })
                .toList();
    }

    private List<Map<String, Object>> buildTopGpuModSummary() {
        EffectiveGpuAttribution effectiveGpu = buildEffectiveGpuAttribution(currentSnapshot.renderPhases(), currentSnapshot.cpuMods(), currentSnapshot.modInvokes(), true);
        long totalGpuNs = Math.max(1L, effectiveGpu.totalGpuNanos());
        return effectiveGpu.gpuNanosByMod().entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(5)
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("mod", entry.getKey());
                    row.put("gpuFrameTimeMsEstimate", entry.getValue() / 1_000_000.0);
                    row.put("percentGpuEstimate", entry.getValue() * 100.0 / totalGpuNs);
                    row.put("rawGpuFrameTimeMs", buildEffectiveGpuAttribution(currentSnapshot.renderPhases(), currentSnapshot.cpuMods(), currentSnapshot.modInvokes(), false).gpuNanosByMod().getOrDefault(entry.getKey(), 0L) / 1_000_000.0);
                    row.put("renderSamples", effectiveGpu.renderSamplesByMod().getOrDefault(entry.getKey(), 0L));
                    row.put("redistributedGpuNanos", effectiveGpu.redistributedGpuNanosByMod().getOrDefault(entry.getKey(), 0L));
                    row.put("threadCount", currentSnapshot.cpuDetails().get(entry.getKey()) == null ? 0 : currentSnapshot.cpuDetails().get(entry.getKey()).sampledThreadCount());
                    return row;
                })
                .toList();
    }

    private List<Map<String, Object>> buildTopMemoryModSummary() {
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
        return "Status: Healthy. System is " + systemBound + ". " + thermal + " " + memoryText + " " + logicText + " Parallelism Efficiency: " + system.parallelismEfficiency() + schedulingText + overscheduleText;
    }
    private void enforceSessionWindow(MinecraftClient client) {
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
                client.player.sendMessage(Text.literal("Task Manager: Session recorded. " + exportStatus), false);
                if (lastExportHtmlReport != null) {
                    Text openReport = Text.literal("[Open Session Report]")
                            .setStyle(Style.EMPTY
                                    .withColor(Formatting.AQUA)
                                    .withUnderline(true)
                                    .withClickEvent(new ClickEvent.OpenFile(lastExportHtmlReport.toAbsolutePath().toString())));
                    client.player.sendMessage(openReport, false);
                }
                if (lastExportDirectory != null) {
                    Text openFolder = Text.literal("[Open Session Logs Folder]")
                            .setStyle(Style.EMPTY
                                    .withColor(Formatting.GREEN)
                                    .withUnderline(true)
                                    .withClickEvent(new ClickEvent.OpenFile(lastExportDirectory.toAbsolutePath().toString())));
                    client.player.sendMessage(openFolder, false);
                }
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

    public List<Integer> getChunkActivityHistory(ChunkPos chunkPos) {
        if (chunkPos == null) {
            return List.of();
        }
        Deque<Integer> history = chunkActivityHistory.get(chunkKey(chunkPos.x, chunkPos.z));
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

    public List<String> getLatestLockSummaries() {
        return latestLockSummaries;
    }

    public String getCurrentBottleneckLabel() {
        return currentBottleneckLabel();
    }

    private List<HotChunkSnapshot> sampleHotChunks(MinecraftClient client) {
        if (client.world == null) {
            hotChunkHistory.clear();
            chunkActivityHistory.clear();
            return List.of();
        }

        record ChunkAggregate(int entityCount, int blockEntityCount, Map<String, Integer> entityClasses, Map<String, Integer> blockEntityClasses) {}
        Map<Long, int[]> counts = new LinkedHashMap<>();
        Map<Long, Map<String, Integer>> entityClasses = new LinkedHashMap<>();
        Map<Long, Map<String, Integer>> blockEntityClasses = new LinkedHashMap<>();
        for (Entity entity : client.world.getEntities()) {
            ChunkPos pos = entity.getChunkPos();
            long key = chunkKey(pos.x, pos.z);
            counts.computeIfAbsent(key, ignored -> new int[2])[0]++;
            entityClasses.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).merge(entity.getClass().getSimpleName(), 1, Integer::sum);
        }
        for (BlockEntity blockEntity : client.world.getBlockEntities()) {
            ChunkPos pos = new ChunkPos(blockEntity.getPos());
            long key = chunkKey(pos.x, pos.z);
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

    private List<RuleFinding> buildRuleFindings() {
        List<RuleFinding> findings = new ArrayList<>();
        SystemMetricsProfiler.Snapshot system = SystemMetricsProfiler.getInstance().getSnapshot();
        double latestFrameMs = FrameTimelineProfiler.getInstance().getLatestFrameNs() / 1_000_000.0;
        double serverTickMs = TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0;
        double clientTickMs = TickProfiler.getInstance().getAverageClientTickNs() / 1_000_000.0;
        double stutterScore = FrameTimelineProfiler.getInstance().getStutterScore();
        double heapUsedPct = currentSnapshot.memory().heapCommittedBytes() > 0
                ? currentSnapshot.memory().heapUsedBytes() * 100.0 / currentSnapshot.memory().heapCommittedBytes()
                : 0.0;

        if (system.gpuCoreLoadPercent() > 90.0 && latestFrameMs > ConfigManager.getFrameBudgetTargetFrameMs() && serverTickMs < 15.0) {
            findings.add(new RuleFinding(latestFrameMs > 33.0 && system.gpuCoreLoadPercent() > 97.0 ? "critical" : "warning", "gpu", "GPU appears saturated while logic stays healthy.", "measured",
                    "The render path is spending time on the GPU while server-side logic remains within budget.",
                    "Check heavy shader packs, high-resolution effects, and the GPU tab's hottest render phases.",
                    String.format(Locale.ROOT, "GPU %.0f%% | frame %.1f ms | server %.1f ms", system.gpuCoreLoadPercent(), latestFrameMs, serverTickMs)));
        }
        if (serverTickMs > 40.0) {
            findings.add(new RuleFinding(serverTickMs > 80.0 ? "critical" : "warning", "logic", String.format(Locale.ROOT, "Server tick is elevated at %.1f ms.", serverTickMs), "measured",
                    "Integrated-server work is exceeding a comfortable frame budget and will usually show up as simulation hitching.",
                    "Inspect the World tab for hot chunks, block entities, and thread wait activity around the same window.",
                    String.format(Locale.ROOT, "server %.1f ms | client %.1f ms | stutter %.1f", serverTickMs, clientTickMs, stutterScore)));
        }
        if (system.diskWriteBytesPerSecond() > 8L * 1024L * 1024L && latestFrameMs > 50.0) {
            findings.add(new RuleFinding(system.diskWriteBytesPerSecond() > 24L * 1024L * 1024L ? "critical" : "warning", "io", "Heavy disk writes overlap with a bad frame spike.", "measured",
                    "High write throughput is coinciding with a visible hitch and may point to saves, chunk flushes, or logging bursts.",
                    "Check the Disk tab and world activity for saves, region writes, or mods with frequent persistence.",
                    String.format(Locale.ROOT, "writes %s | frame %.1f ms", formatBytesPerSecond(system.diskWriteBytesPerSecond()), latestFrameMs)));
        }
        if (system.activeHighLoadThreads() > Math.max(1, system.estimatedPhysicalCores() / 2) && stutterScore > 10.0) {
            findings.add(new RuleFinding(system.activeHighLoadThreads() > Math.max(2, system.estimatedPhysicalCores()) ? "critical" : "warning", "threads", "Thread overscheduling warning: too many high-load threads are active for the estimated physical core budget.", "inferred",
                    "Multiple hot threads are competing for a limited physical-core budget during a stutter window.",
                    "Inspect the System tab's top threads and worker activity to see whether chunk builders or async workers are crowding the CPU.",
                    String.format(Locale.ROOT, "high-load threads %d | est. physical cores %d | stutter %.1f", system.activeHighLoadThreads(), system.estimatedPhysicalCores(), stutterScore)));
        }
        if (!latestHotChunks.isEmpty() && serverTickMs > 20.0) {
            HotChunkSnapshot hot = latestHotChunks.getFirst();
            findings.add(new RuleFinding("info", "chunks", String.format(Locale.ROOT, "Hot chunk %d,%d has %d entities and %d block entities.", hot.chunkX(), hot.chunkZ(), hot.entityCount(), hot.blockEntityCount()), "measured",
                    "A single chunk is standing out in the current window and may be central to the slowdown.",
                    "Select the chunk in the World tab and inspect entity density, block entities, and thread load together.",
                    String.format(Locale.ROOT, "activity %.1f | entities %d | block entities %d", hot.activityScore(), hot.entityCount(), hot.blockEntityCount())));
        }
        if (!latestEntityHotspots.isEmpty()) {
            EntityHotspot hotspot = latestEntityHotspots.getFirst();
            if (!"none".equals(hotspot.heuristic())) {
                findings.add(new RuleFinding(hotspot.count() >= 100 ? "critical" : "warning", "entities", hotspot.className() + " is dominating recent entity cost signals: " + hotspot.heuristic(), "inferred",
                        "Recent world samples point to one entity family as the strongest source of per-chunk entity pressure.",
                        "Inspect mob AI density, farms, and clustered spawns in the World tab near the hot chunk.",
                        String.format(Locale.ROOT, "%s x%d", hotspot.className(), hotspot.count())));
            }
        }
        if (!latestBlockEntityHotspots.isEmpty()) {
            BlockEntityHotspot hotspot = latestBlockEntityHotspots.getFirst();
            if (hotspot.count() >= 20) {
                findings.add(new RuleFinding(hotspot.count() >= 60 ? "critical" : "warning", "block-entities", hotspot.className() + " is dense across loaded chunks and may be ticking heavily.", "inferred",
                        "A block entity class is showing up frequently enough to plausibly drive ticking or storage pressure.",
                        "Open the Block Entities mini-tab and inspect the selected chunk plus the global hotspot list.",
                        String.format(Locale.ROOT, "%s x%d | %s", hotspot.className(), hotspot.count(), hotspot.heuristic())));
            }
        }
        if (!latestLockSummaries.isEmpty()) {
            findings.add(new RuleFinding("info", "locks", latestLockSummaries.getFirst(), "measured",
                    "A thread spent time blocked or waiting in the current window.",
                    "Use the System tab to check the owning thread and see whether the wait lines up with chunk IO or background workers.",
                    latestLockSummaries.getFirst()));
            boolean chunkIoLock = latestLockSummaries.stream()
                    .map(summary -> summary.toLowerCase(Locale.ROOT))
                    .anyMatch(summary -> summary.contains("region") || summary.contains("chunk") || summary.contains("poi") || summary.contains("anvil") || summary.contains("storage"));
            if (chunkIoLock && (serverTickMs > 20.0 || latestFrameMs > 25.0)) {
                findings.add(new RuleFinding((serverTickMs > 50.0 || latestFrameMs > 40.0) ? "critical" : "warning", "chunk-io", "Threads are waiting on chunk or region style locks during a slow window.", "inferred",
                        "The lock names look chunk-storage related and overlap with a visible slowdown.",
                        "Check async chunk mods, world storage activity, and the Disk tab for matching spikes.",
                        String.format(Locale.ROOT, "server %.1f ms | frame %.1f ms | lock count %d", serverTickMs, latestFrameMs, latestLockSummaries.size())));
            }
        }
        if (system.bytesReceivedPerSecond() > 512L * 1024L && latestFrameMs > 20.0) {
            findings.add(new RuleFinding("info", "network", "A network burst overlaps with a slower frame window.", "measured",
                    "Inbound traffic is elevated enough to plausibly disturb the client if packet handling or chunk delivery is busy.",
                    "Inspect the Network tab's packet types and recent spike bookmarks.",
                    String.format(Locale.ROOT, "inbound %s | packet latency %.1f ms", formatBytesPerSecond(system.bytesReceivedPerSecond()), system.packetProcessingLatencyMs())));
        }
        if ((system.chunksGenerating() > 0 || system.chunksMeshing() > 0 || system.chunksUploading() > 0) && (latestFrameMs > 20.0 || serverTickMs > 20.0)) {
            findings.add(new RuleFinding("info", "chunk-pipeline", "Chunk generation, meshing, or upload work is active during the current slow window.", "measured",
                    "World streaming work is non-idle and may be contributing to a hitch, especially while moving quickly or exploring new terrain.",
                    "Check the World tab and render metrics for generation, meshing, upload, and lighting pressure.",
                    String.format(Locale.ROOT, "gen %d | mesh %d | upload %d | lights %d", system.chunksGenerating(), system.chunksMeshing(), system.chunksUploading(), system.lightsUpdatePending())));
        }
        if (heapUsedPct > 85.0) {
            findings.add(new RuleFinding("info", "memory", "Heap usage is high relative to committed memory.", "measured",
                    "Live heap usage is near the current committed ceiling, which can increase GC pressure or mask leaks.",
                    "Inspect the Memory tab for dominant mods and shared JVM buckets, especially if GC pauses are appearing too.",
                    String.format(Locale.ROOT, "heap %.0f%% | used %s", heapUsedPct, formatBytesMb(currentSnapshot.memory().heapUsedBytes()))));
        }
        if (currentSnapshot.memory().gcPauseDurationMs() > 0) {
            findings.add(new RuleFinding("info", "gc", "Recent GC pause detected: " + currentSnapshot.memory().gcType() + " " + currentSnapshot.memory().gcPauseDurationMs() + " ms.", "measured",
                    "A garbage-collection pause occurred recently and may explain a hitch if it aligns with frame or tick spikes.",
                    "Correlate the pause with frame-time spikes and high heap usage in the Timeline and Memory tabs.",
                    String.format(Locale.ROOT, "%s | pause %d ms", currentSnapshot.memory().gcType(), currentSnapshot.memory().gcPauseDurationMs())));
        }
        if (system.cpuTemperatureC() < 0 && system.gpuTemperatureC() < 0) {
            findings.add(new RuleFinding("info", "sensors", "Temperature sensors are unavailable on this machine/provider combination; falling back to load-only telemetry.", "unavailable",
                    "The profiler can still report utilization, but package/core temperatures are not currently exposed by any detected provider.",
                    "Open the System tab's Sensors panel to see provider attempts and the last bridge error.",
                    system.cpuTemperatureUnavailableReason()));
        } else if (system.cpuTemperatureC() >= 85.0 || system.gpuTemperatureC() >= 85.0) {
            findings.add(new RuleFinding((system.cpuTemperatureC() >= 92.0 || system.gpuTemperatureC() >= 90.0) ? "critical" : "warning", "thermals", "A CPU or GPU temperature is entering a throttling-prone range.", "measured",
                    "Sustained temperatures in the mid-80s or higher can cause clocks to drop and make spikes harder to explain from software alone.",
                    "Check cooling, fan curves, and whether the slowdown lines up with a thermal ramp in exported sessions.",
                    String.format(Locale.ROOT, "CPU %s | GPU %s", system.cpuTemperatureC() >= 0 ? String.format(Locale.ROOT, "%.1f C", system.cpuTemperatureC()) : "N/A", system.gpuTemperatureC() >= 0 ? String.format(Locale.ROOT, "%.1f C", system.gpuTemperatureC()) : "N/A")));
        }
        findings.sort((a, b) -> Integer.compare(severityRank(b.severity()), severityRank(a.severity())));
        return findings;
    }

    private int severityRank(String severity) {
        return switch (severity == null ? "info" : severity.toLowerCase(Locale.ROOT)) {
            case "critical" -> 3;
            case "error" -> 2;
            case "warning" -> 1;
            default -> 0;
        };
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

    private List<EntityHotspot> sampleEntityHotspots(MinecraftClient client) {
        if (client.world == null) {
            return List.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Entity entity : client.world.getEntities()) {
            counts.merge(entity.getType().toString(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(8)
                .map(entry -> new EntityHotspot(entry.getKey(), entry.getValue(), classifyEntityHeuristic(entry.getKey())))
                .toList();
    }

    private List<BlockEntityHotspot> sampleBlockEntityHotspots(MinecraftClient client) {
        if (client.world == null) {
            return List.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (BlockEntity blockEntity : client.world.getBlockEntities()) {
            counts.merge(blockEntity.getClass().getSimpleName(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(8)
                .map(entry -> new BlockEntityHotspot(entry.getKey(), entry.getValue(), classifyBlockEntityHeuristic(entry.getKey())))
                .toList();
    }

    private List<String> buildLockSummaries(SystemMetricsProfiler.Snapshot system) {
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

    private String classifyEntityHeuristic(String className) {
        String lower = className.toLowerCase(Locale.ROOT);
        if (lower.contains("villager") || lower.contains("bee") || lower.contains("piglin") || lower.contains("warden") || lower.contains("zombie") || lower.contains("creeper") || lower.contains("animal")) {
            return "AI/pathfinding-heavy mob cluster";
        }
        if (lower.contains("item") || lower.contains("experience_orb") || lower.contains("projectile") || lower.contains("arrow")) {
            return "High transient entity count";
        }
        if (lower.contains("boat") || lower.contains("minecart")) {
            return "Collision-heavy vehicle cluster";
        }
        return "none";
    }

    private String classifyBlockEntityHeuristic(String className) {
        String lower = className.toLowerCase(Locale.ROOT);
        if (lower.contains("hopper") || lower.contains("pipe") || lower.contains("conveyor")) {
            return "Inventory transfer / item routing";
        }
        if (lower.contains("chest") || lower.contains("storage") || lower.contains("barrel")) {
            return "Storage dense chunk";
        }
        if (lower.contains("spawner") || lower.contains("beacon") || lower.contains("furnace") || lower.contains("machine")) {
            return "Ticking machine / utility block entity";
        }
        return "General block entity density";
    }



    private void captureStutterJumpSnapshot(MinecraftClient client) {
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

    private String sampleCurrentBiome(MinecraftClient client) {
        try {
            if (client == null || client.world == null || client.player == null) {
                return "unknown";
            }
            RegistryEntry<?> biome = client.world.getBiome(client.player.getBlockPos());
            return biome.getKey().map(key -> key.getValue().toString()).orElse("unknown");
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private List<String> sampleClosestEntities(MinecraftClient client, int limit) {
        if (client == null || client.world == null || client.player == null) {
            return List.of();
        }
        java.util.List<Entity> entities = new java.util.ArrayList<>();
        for (Entity entity : client.world.getEntities()) {
            if (entity != client.player) {
                entities.add(entity);
            }
        }
        return entities.stream()
                .sorted((a, b) -> Double.compare(a.squaredDistanceTo(client.player), b.squaredDistanceTo(client.player)))
                .limit(limit)
                .map(entity -> entity.getName().getString())
                .toList();
    }

    private List<Map<String, String>> buildRedFlagThresholds() {
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(redFlag("Sync-Lock Latency", "Catches mod conflicts.", "> 10ms"));
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


    private EntityCounts sampleEntityCounts(MinecraftClient client) {
        if (client.world == null) {
            return EntityCounts.empty();
        }

        int total = 0;
        int living = 0;
        for (Entity entity : client.world.getEntities()) {
            total++;
            if (entity instanceof LivingEntity) {
                living++;
            }
        }

        int blockEntities = client.world.getBlockEntities().size();
        return new EntityCounts(total, living, blockEntities);
    }

    private ChunkCounts sampleChunkCounts(MinecraftClient client) {
        if (client.worldRenderer == null) {
            return ChunkCounts.empty();
        }
        try {
            String debug = client.worldRenderer.getChunksDebugString();
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
        } catch (NullPointerException e) {
            return ChunkCounts.empty();
        }
    }

    private Map<String, CpuSamplingProfiler.Snapshot> aggregateCpuWindows() {
        Map<String, long[]> totals = new LinkedHashMap<>();
        for (Map<String, CpuSamplingProfiler.Snapshot> window : cpuWindows) {
            window.forEach((mod, snapshot) -> {
                long[] value = totals.computeIfAbsent(mod, ignored -> new long[3]);
                value[0] += snapshot.totalSamples();
                value[1] += snapshot.clientSamples();
                value[2] += snapshot.renderSamples();
            });
        }

        Map<String, CpuSamplingProfiler.Snapshot> result = new LinkedHashMap<>();
        totals.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .forEach(entry -> result.put(entry.getKey(), new CpuSamplingProfiler.Snapshot(entry.getValue()[0], entry.getValue()[1], entry.getValue()[2])));
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
        for (Map<String, RenderPhaseProfiler.PhaseSnapshot> window : renderWindows) {
            window.forEach((phase, snapshot) -> {
                long[] value = totals.computeIfAbsent(phase, ignored -> new long[4]);
                value[0] += snapshot.cpuNanos();
                value[1] += snapshot.cpuCalls();
                value[2] += snapshot.gpuNanos();
                value[3] += snapshot.gpuCalls();
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
                                .orElse("shared/render")
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

    private void clearRollingWindows() {
        cpuWindows.clear();
        cpuDetailWindows.clear();
        modWindows.clear();
        renderWindows.clear();
        spikes.clear();
        sessionHistory.clear();
        hotChunkHistory.clear();
        chunkActivityHistory.clear();
        latestHotChunks = List.of();
        latestEntityHotspots = List.of();
        latestBlockEntityHotspots = List.of();
        latestLockSummaries = List.of();
        latestRuleFindings = List.of();
        stutterJumpSnapshots.clear();
        lastStutterScore = 0.0;
        NetworkPacketProfiler.getInstance().reset();
        ThreadLoadProfiler.getInstance().reset();
        lastSeenFrameSequence = 0;
        sessionLoggingStartedAtMillis = 0L;
        sessionLogging = false;
        sessionRecorded = false;
        sessionRecordedAtMillis = 0L;
        sessionMissedSamples = 0;
        sessionMaxSampleGapMillis = 0L;
        sessionExpectedSampleIntervalMillis = 50L;
    }
}





























