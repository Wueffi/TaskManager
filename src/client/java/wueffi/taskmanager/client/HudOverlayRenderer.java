package wueffi.taskmanager.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import wueffi.taskmanager.client.util.ConfigManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Supplier;

public final class HudOverlayRenderer {

    private static final int BG = 0xC8141418;
    private static final int BORDER = 0xAA3A3F46;
    private static final int HEADER = 0xFFEEF2F6;
    private static final int TEXT = 0xFFD7DDE4;
    private static final int DIM = 0xFF97A3AF;
    private static final int ACCENT = 0xFF70C7A7;
    private static final int FLOW_IN = 0xFF5EA9FF;
    private static final int FLOW_OUT = 0xFFFFC857;
    private static final int WARN = 0xFFFFC857;
    private static final int ERROR = 0xFFFF9F43;
    private static final int CRITICAL = 0xFFFF6B6B;
    private static final int PADDING = 8;
    private static final int GAP = 8;
    private static final int HEADER_HEIGHT = 16;
    private static final int ROW_HEIGHT = 12;
    private static final int SINGLE_COLUMN_WIDTH = 250;
    private static final int TWO_COLUMN_WIDTH = 176;
    private static final int THREE_COLUMN_WIDTH = 138;
    private static final int LABEL_WIDTH = 54;
    private static final int LABEL_VALUE_GAP = 8;
    private static final int VALUE_RIGHT_PADDING = 4;
    private static final int MAX_MEMORY_RATE_SAMPLES = 24;
    private static final int MAX_DISPLAY_CACHE_ENTRIES = 192;
    private static final int MAX_RATE_CACHE_ENTRIES = 128;
    private static final Map<String, DisplayCacheEntry> displayCache = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedDeque<String> displayCacheOrder = new ConcurrentLinkedDeque<>();
    private static final ConcurrentLinkedDeque<MemoryRateSample> memoryRateSamples = new ConcurrentLinkedDeque<>();
    private static final Map<String, RateSample> rateSamples = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedDeque<String> rateSampleOrder = new ConcurrentLinkedDeque<>();
    private static final Map<String, SensorRateSample> sensorRateSamples = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedDeque<String> sensorRateSampleOrder = new ConcurrentLinkedDeque<>();
    private static HudLayoutCache cachedLayout;
    private static long currentHudNowMillis;

    private HudOverlayRenderer() {
    }

    public static void render(DrawContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.hudHidden || client.currentScreen instanceof TaskManagerScreen) {
            return;
        }

        long renderNow = System.currentTimeMillis();
        currentHudNowMillis = renderNow;
        try {
            ProfilerManager profilerManager = ProfilerManager.getInstance();
            renderPerformanceAlertBanner(ctx, client, profilerManager);
            if (!ConfigManager.isHudEnabled()) {
                return;
            }
            ProfilerManager.ProfilerSnapshot snapshot = profilerManager.getCurrentSnapshot();
            FrameTimelineProfiler frame = FrameTimelineProfiler.getInstance();
            MemoryProfiler.Snapshot memory = snapshot.memory();
            SystemMetricsProfiler.Snapshot system = snapshot.systemMetrics();
            refreshDisplayedMetrics(frame, memory, snapshot, system);
            ProfilerManager.RuleFinding highestFinding = highestFinding(profilerManager);
            double latestFrameMs = frame.getLatestFrameNs() / 1_000_000.0;
            long recentSpikeAge = snapshot.spikes().isEmpty() ? Long.MAX_VALUE : Math.max(0L, renderNow - snapshot.spikes().get(0).capturedAtEpochMillis());
            boolean actionableWarning = hasActionableWarning(snapshot, highestFinding, latestFrameMs, recentSpikeAge);
            int alertColor = severityColor(highestFinding == null ? null : highestFinding.severity(), actionableWarning);

            if (!shouldRenderHud(snapshot, highestFinding, latestFrameMs, recentSpikeAge)) {
                return;
            }

            HudLayoutCache layout = prepareHudLayout(client, profilerManager, snapshot, frame, memory, system, highestFinding, latestFrameMs, actionableWarning, alertColor);
            TextRenderer textRenderer = client.textRenderer;
            int backgroundColor = applyHudTransparency(BG);
            int borderFillColor = applyHudTransparency(layout.borderColor());
            int dividerColor = applyHudTransparency(0x443A3F46);
            ctx.fill(layout.x(), layout.y(), layout.x() + layout.width(), layout.y() + layout.height(), backgroundColor);
            ctx.fill(layout.x(), layout.y(), layout.x() + layout.width(), layout.y() + 1, borderFillColor);
            ctx.fill(layout.x(), layout.y(), layout.x() + 1, layout.y() + layout.height(), borderFillColor);
            ctx.fill(layout.x() + layout.width() - 1, layout.y(), layout.x() + layout.width(), layout.y() + layout.height(), borderFillColor);
            ctx.fill(layout.x(), layout.y() + layout.height() - 1, layout.x() + layout.width(), layout.y() + layout.height(), borderFillColor);
            ctx.fill(layout.x(), layout.y() + HEADER_HEIGHT, layout.x() + layout.width(), layout.y() + HEADER_HEIGHT + 1, dividerColor);

            ctx.drawText(textRenderer, "Task Manager", layout.x() + PADDING, layout.y() + 5, actionableWarning ? HEADER : DIM, false);
            String modeText = snapshot.mode().name().replace('_', ' ');
            ctx.drawText(textRenderer, modeText, layout.x() + layout.width() - PADDING - textRenderer.getWidth(modeText), layout.y() + 5, actionableWarning ? alertColor : DIM, false);

            int rowY = layout.y() + HEADER_HEIGHT + 6;
            for (Row row : layout.rows()) {
                if (row.fullWidth()) {
                    drawEntry(ctx, textRenderer, layout.x() + PADDING, rowY, layout.contentWidth(), row.entries().getFirst(), true);
                } else {
                    for (int i = 0; i < row.entries().size(); i++) {
                        int cellX = layout.x() + PADDING + i * (layout.cellWidth() + GAP);
                        drawEntry(ctx, textRenderer, cellX, rowY, layout.cellWidth(), row.entries().get(i), false);
                    }
                }
                rowY += ROW_HEIGHT;
            }
        } finally {
            currentHudNowMillis = 0L;
        }
    }

    private static void renderPerformanceAlertBanner(DrawContext ctx, MinecraftClient client, ProfilerManager profilerManager) {
        ProfilerManager.PerformanceAlert alert = profilerManager.getLatestPerformanceAlert();
        if (alert == null) {
            return;
        }
        TextRenderer textRenderer = client.textRenderer;
        String label = alert.label() + " alert";
        String message = textRenderer.trimToWidth(alert.message(), Math.max(220, client.getWindow().getScaledWidth() - 80));
        int width = Math.min(client.getWindow().getScaledWidth() - 24, Math.max(240, textRenderer.getWidth(message) + 24));
        int x = Math.max(12, (client.getWindow().getScaledWidth() - width) / 2);
        int y = 10;
        int bg = applyHudTransparency(profilerManager.isPerformanceAlertFlashActive() ? 0xCC5B1717 : 0xB0331A1A);
        int border = severityColor(alert.severity(), true);
        ctx.fill(x, y, x + width, y + 30, bg);
        ctx.fill(x, y, x + width, y + 1, border);
        ctx.fill(x, y + 29, x + width, y + 30, border);
        ctx.fill(x, y, x + 1, y + 30, border);
        ctx.fill(x + width - 1, y, x + width, y + 30, border);
        ctx.drawText(textRenderer, label, x + 8, y + 5, HEADER, false);
        ctx.drawText(textRenderer, message, x + 8, y + 17, TEXT, false);
    }

    private static HudLayoutCache prepareHudLayout(MinecraftClient client, ProfilerManager profilerManager, ProfilerManager.ProfilerSnapshot snapshot, FrameTimelineProfiler frame, MemoryProfiler.Snapshot memory, SystemMetricsProfiler.Snapshot system, ProfilerManager.RuleFinding highestFinding, double latestFrameMs, boolean actionableWarning, int alertColor) {
        int screenW = client.getWindow().getScaledWidth();
        int screenH = client.getWindow().getScaledHeight();
        int columns = ConfigManager.getHudLayoutMode().columns();
        int configHash = computeHudConfigHash();
        if (cachedLayout != null
                && cachedLayout.snapshot() == snapshot
                && cachedLayout.configHash() == configHash
                && cachedLayout.screenW() == screenW
                && cachedLayout.screenH() == screenH
                && cachedLayout.columns() == columns
                && cachedLayout.actionableWarning() == actionableWarning) {
            return cachedLayout;
        }

        List<Entry> entries = new ArrayList<>();
        if (ConfigManager.getHudConfigMode() == ConfigManager.HudConfigMode.PRESET) {
            if (ConfigManager.getHudPreset() == ConfigManager.HudPreset.COMPACT) {
                buildCompactEntries(entries, snapshot, frame, memory, system);
            } else {
                buildPresetFullEntries(entries, snapshot, frame, memory, system);
            }
        } else {
            buildCustomEntries(entries, snapshot, frame, memory, system);
        }

        Entry autoFocusEntry = ConfigManager.isHudAutoFocusAlertRow() ? buildAutoFocusEntry(profilerManager, snapshot, highestFinding, latestFrameMs, alertColor) : null;
        if (autoFocusEntry != null) {
            entries.add(0, autoFocusEntry);
        }

        boolean showExpandedDetails = !ConfigManager.isHudExpandedOnWarning() || actionableWarning;
        if (showExpandedDetails) {
            appendExpandedDetails(entries, profilerManager, snapshot, highestFinding, alertColor, autoFocusEntry != null);
        }

        int maxContentWidth = Math.max(160, screenW - 16 - (PADDING * 2));
        int cellWidth = getCellWidth(columns, maxContentWidth);
        List<Entry> layoutEntries = normalizeEntriesForColumns(entries, client.textRenderer, columns, cellWidth, maxContentWidth);
        List<Row> rows = buildRows(layoutEntries, columns);
        int contentWidth = Math.min(maxContentWidth, columns == 1 ? cellWidth : (columns * cellWidth) + ((columns - 1) * GAP));
        int width = contentWidth + (PADDING * 2);
        int height = HEADER_HEIGHT + PADDING + (rows.size() * ROW_HEIGHT) + PADDING;
        int borderColor = actionableWarning ? alertColor : BORDER;

        int x = 8;
        int y = 8;
        switch (ConfigManager.getHudPosition()) {
            case TOP_RIGHT -> x = screenW - width - 8;
            case BOTTOM_LEFT -> y = screenH - height - 8;
            case BOTTOM_RIGHT -> {
                x = screenW - width - 8;
                y = screenH - height - 8;
            }
            default -> {
            }
        }

        cachedLayout = new HudLayoutCache(snapshot, configHash, screenW, screenH, columns, actionableWarning, rows, contentWidth, cellWidth, width, height, x, y, borderColor);
        return cachedLayout;
    }

    private static long hudNow() {
        return currentHudNowMillis > 0L ? currentHudNowMillis : System.currentTimeMillis();
    }

    private static int computeHudConfigHash() {
        return Objects.hash(
                ConfigManager.getHudConfigMode(),
                ConfigManager.getHudPreset(),
                ConfigManager.getHudLayoutMode(),
                ConfigManager.getHudPosition(),
                ConfigManager.getHudTransparencyPercent(),
                ConfigManager.isHudAutoFocusAlertRow(),
                ConfigManager.isHudExpandedOnWarning(),
                ConfigManager.isHudShowFps(),
                ConfigManager.isHudShowFrame(),
                ConfigManager.isHudShowTicks(),
                ConfigManager.isHudShowUtilization(),
                ConfigManager.isHudShowLogic(),
                ConfigManager.isHudShowBackground(),
                ConfigManager.isHudShowParallelism(),
                ConfigManager.isHudShowFrameBudget(),
                ConfigManager.isHudShowMemory(),
                ConfigManager.isHudShowVram(),
                ConfigManager.isHudShowNetwork(),
                ConfigManager.isHudShowChunkActivity(),
                ConfigManager.isHudShowWorld(),
                ConfigManager.isHudShowDiskIo(),
                ConfigManager.isHudShowInputLatency(),
                ConfigManager.isHudShowSession(),
                ConfigManager.isHudShowTemperatures(),
                ConfigManager.isHudBudgetColorMode());
    }

    private static void buildCompactEntries(List<Entry> entries, ProfilerManager.ProfilerSnapshot snapshot, FrameTimelineProfiler frame, MemoryProfiler.Snapshot memory, SystemMetricsProfiler.Snapshot system) {
        entries.add(new Entry("FPS", displayedFpsText(frame), HEADER, false));
        entries.add(new Entry("Frame Budget", displayedMetric("frame.budget", () -> frameBudgetText(frame)), frameBudgetColor(frame), true));
        entries.add(new Entry("CPU", formatUtilAndTemp(system, true), utilizationColor(system, true), false));
        entries.add(new Entry("GPU", formatUtilAndTemp(system, false), utilizationColor(system, false), false));
        entries.add(new Entry("Memory", displayedMemoryText(memory), memoryColor(memory), false));
        entries.add(new Entry("VRAM", displayedMetric("vram", () -> vramText(system)), vramColor(system), false));
        if (frame.getSelfCostAvgMs() > 0.1) {
            entries.add(new Entry("Profiler", displayedMetric("profiler.cost", () -> String.format(Locale.ROOT, "%.2f ms", frame.getSelfCostAvgMs())), DIM, false));
        }
        if (snapshot.sessionLogging()) {
            entries.add(new Entry("Session", displayedMetric("session", () -> formatDuration(snapshot.sessionLoggingElapsedMillis()) + " / " + formatDuration(ConfigManager.getSessionDurationSeconds() * 1000L)), WARN, false));
        }
    }

    private static void buildPresetFullEntries(List<Entry> entries, ProfilerManager.ProfilerSnapshot snapshot, FrameTimelineProfiler frame, MemoryProfiler.Snapshot memory, SystemMetricsProfiler.Snapshot system) {
        entries.add(new Entry("FPS", displayedFpsText(frame), HEADER, false));
        entries.add(new Entry("FPS Lows", displayedLowFpsText(frame), HEADER, false));
        entries.add(new Entry("Frame Budget", displayedMetric("frame.budget", () -> frameBudgetText(frame)), frameBudgetColor(frame), false));
        entries.add(new Entry("Frame", displayedMetric("frame.stats", () -> compactFrameText(frame)), frameBudgetColor(frame), false));
        entries.add(new Entry("Client Tick", displayedMetric("tick.client", () -> tickText("tick.client", TickProfiler.getInstance().getAverageClientTickNs() / 1_000_000.0)), tickColor(TickProfiler.getInstance().getAverageClientTickNs() / 1_000_000.0, false), false));
        entries.add(new Entry("Server Tick", displayedMetric("tick.server", () -> tickText("tick.server", TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0)), tickColor(TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0, true), false));
        entries.add(new Entry("CPU", formatUtilAndTemp(system, true), utilizationColor(system, true), false));
        entries.add(new Entry("GPU", formatUtilAndTemp(system, false), utilizationColor(system, false), false));
        entries.add(new Entry("Input Latency", displayedMetric("input.latency", () -> inputLatencyText(system)), inputLatencyColor(system), false));
        entries.add(new Entry("Memory", displayedMemoryText(memory), memoryColor(memory), false));
        entries.add(new Entry("VRAM", displayedMetric("vram", () -> vramText(system)), vramColor(system), false));
        if (frame.getSelfCostAvgMs() > 0.1) {
            entries.add(new Entry("Profiler", displayedMetric("profiler.cost", () -> String.format(Locale.ROOT, "%.2f ms", frame.getSelfCostAvgMs())), DIM, false));
        }
        entries.add(networkEntry(system));
        entries.add(new Entry("Chunk Activity", displayedMetric("chunk.activity", () -> chunkActivityText(system)), chunkActivityColor(system), true));
        entries.add(new Entry("Entities", displayedMetric("world.entities", () -> worldEntitiesText(snapshot)), DIM, false));
        entries.add(new Entry("Chunks", displayedMetric("world.chunks", () -> worldChunksText(snapshot)), DIM, false));
        entries.add(diskEntry(system));
        if (snapshot.sessionLogging()) {
            entries.add(new Entry("Session", displayedMetric("session", () -> formatDuration(snapshot.sessionLoggingElapsedMillis()) + " / " + formatDuration(ConfigManager.getSessionDurationSeconds() * 1000L)), WARN, false));
        }
    }

    private static void buildCustomEntries(List<Entry> entries, ProfilerManager.ProfilerSnapshot snapshot, FrameTimelineProfiler frame, MemoryProfiler.Snapshot memory, SystemMetricsProfiler.Snapshot system) {
        if (ConfigManager.isHudShowFps()) {
            entries.add(new Entry("FPS", displayedFpsText(frame), HEADER, false));
            entries.add(new Entry("FPS Lows", displayedLowFpsText(frame), HEADER, false));
        }
        if (ConfigManager.isHudShowFrame()) {
            entries.add(new Entry("Frame", displayedMetric("frame.stats", () -> compactFrameText(frame)), TEXT, false));
        }
        if (ConfigManager.isHudShowTicks()) {
            entries.add(new Entry("Client Tick", displayedMetric("tick.client", () -> tickText("tick.client", TickProfiler.getInstance().getAverageClientTickNs() / 1_000_000.0)), TEXT, false));
            entries.add(new Entry("Server Tick", displayedMetric("tick.server", () -> tickText("tick.server", TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0)), TEXT, false));
        }
        if (ConfigManager.isHudShowUtilization()) {
            entries.add(new Entry("CPU", formatUtilAndTemp(system, true), utilizationColor(system, true), false));
            entries.add(new Entry("GPU", formatUtilAndTemp(system, false), utilizationColor(system, false), false));
            if (ConfigManager.isHudShowLogic()) {
                entries.add(new Entry("Main Logic", displayedMetric("logic.main", () -> shorten(system.mainLogicSummary().replace("Main Logic: ", ""), 36)), DIM, true));
            }
            if (ConfigManager.isHudShowBackground()) {
                entries.add(new Entry("Background", displayedMetric("logic.background", () -> shorten(system.backgroundSummary().replace("Background: ", ""), 36)), DIM, true));
            }
        }
        if (ConfigManager.isHudShowParallelism()) {
            String parallelText = system.cpuParallelismFlag();
            if (system.activeHighLoadThreads() > Math.max(1, system.estimatedPhysicalCores() / 2) && snapshot.stutterScore() > 10.0) {
                parallelText = "Thread overscheduling";
            }
            String displayedParallelText = parallelText;
            entries.add(new Entry("Parallel", displayedMetric("parallel", () -> displayedParallelText), WARN, true));
        }
        if (ConfigManager.isHudShowFrameBudget()) {
            entries.add(new Entry("Frame Budget", displayedMetric("frame.budget", () -> frameBudgetText(frame)), frameBudgetColor(frame), true));
        }
        if (ConfigManager.isHudShowMemory()) {
            entries.add(new Entry("Memory", displayedMemoryText(memory), memoryColor(memory), false));
        }
        if (ConfigManager.isHudShowVram()) {
            entries.add(new Entry("VRAM", displayedMetric("vram", () -> vramText(system)), vramColor(system), false));
        }
        if (frame.getSelfCostAvgMs() > 0.1) {
            entries.add(new Entry("Profiler", displayedMetric("profiler.cost", () -> String.format(Locale.ROOT, "%.2f ms", frame.getSelfCostAvgMs())), DIM, false));
        }
        if (ConfigManager.isHudShowNetwork()) {
            entries.add(networkEntry(system));
        }
        if (ConfigManager.isHudShowChunkActivity()) {
            entries.add(new Entry("Chunk Activity", displayedMetric("chunk.activity", () -> chunkActivityText(system)), chunkActivityColor(system), true));
        }
        if (ConfigManager.isHudShowWorld()) {
            entries.add(new Entry("Entities", displayedMetric("world.entities", () -> worldEntitiesText(snapshot)), DIM, false));
            entries.add(new Entry("Chunks", displayedMetric("world.chunks", () -> worldChunksText(snapshot)), DIM, false));
        }
        if (ConfigManager.isHudShowDiskIo()) {
            entries.add(diskEntry(system));
        }
        if (ConfigManager.isHudShowInputLatency()) {
            entries.add(new Entry("Input Latency", displayedMetric("input.latency", () -> inputLatencyText(system)), inputLatencyColor(system), false));
        }
        if (ConfigManager.isHudShowSession() && snapshot.sessionLogging()) {
            entries.add(new Entry("Session", displayedMetric("session", () -> formatDuration(snapshot.sessionLoggingElapsedMillis()) + " / " + formatDuration(ConfigManager.getSessionDurationSeconds() * 1000L)), WARN, false));
        }
    }

    private static void appendExpandedDetails(List<Entry> entries, ProfilerManager profilerManager, ProfilerManager.ProfilerSnapshot snapshot, ProfilerManager.RuleFinding highestFinding, int alertColor, boolean hasAutoFocusRow) {
        long warningCount = profilerManager.getLatestRuleFindings().stream().filter(finding -> severityRank(finding.severity()) >= 1).count();
        if (warningCount > 0 && !hasAutoFocusRow) {
            entries.add(new Entry("Alert", displayedMetric("alert", () -> warningCount + " active | stutter " + format1(snapshot.stutterScore())), alertColor, true));
            if (highestFinding != null) {
                entries.add(new Entry("Why", displayedMetric("alert.why", () -> shorten(highestFinding.category() + ": " + highestFinding.message(), 64)), alertColor, true));
            }
        }
        if (!snapshot.spikes().isEmpty()) {
            ProfilerManager.SpikeCapture latestSpike = snapshot.spikes().get(0);
            entries.add(new Entry("Spike", displayedMetric("spike", () -> format1(latestSpike.frameDurationMs()) + " ms | " + shorten(latestSpike.likelyBottleneck(), 32)), WARN, true));
        }
        String bottleneck = profilerManager.getCurrentBottleneckLabel();
        if (bottleneck != null && !bottleneck.isBlank()) {
            entries.add(new Entry("Focus", displayedMetric("focus", () -> shorten(bottleneck, 48)), highestFinding != null ? alertColor : DIM, true));
        }
    }

    private static boolean shouldRenderHud(ProfilerManager.ProfilerSnapshot snapshot, ProfilerManager.RuleFinding highestFinding, double latestFrameMs, long recentSpikeAge) {
        return switch (ConfigManager.getHudTriggerMode()) {
            case ALWAYS -> true;
            case SPIKES_ONLY -> latestFrameMs >= Math.max(40.0, targetFrameBudgetMs() * 2.0) || recentSpikeAge <= 5000L || snapshot.stutterScore() >= 10.0 || (highestFinding != null && severityRank(highestFinding.severity()) >= 2);
            case WARNINGS_ONLY -> highestFinding != null && severityRank(highestFinding.severity()) >= 1;
        };
    }

    private static boolean hasActionableWarning(ProfilerManager.ProfilerSnapshot snapshot, ProfilerManager.RuleFinding highestFinding, double latestFrameMs, long recentSpikeAge) {
        return latestFrameMs >= Math.max(40.0, targetFrameBudgetMs() * 2.0)
                || recentSpikeAge <= 5000L
                || snapshot.stutterScore() >= 10.0
                || (highestFinding != null && severityRank(highestFinding.severity()) >= 1);
    }

    private static ProfilerManager.RuleFinding highestFinding(ProfilerManager profilerManager) {
        return profilerManager.getLatestRuleFindings().stream()
                .sorted((a, b) -> Integer.compare(severityRank(b.severity()), severityRank(a.severity())))
                .findFirst()
                .orElse(null);
    }

    private static List<Row> buildRows(List<Entry> entries, int columns) {
        List<Row> rows = new ArrayList<>();
        List<Entry> current = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.fullWidth()) {
                if (!current.isEmpty()) {
                    rows.add(new Row(List.copyOf(current), false));
                    current.clear();
                }
                rows.add(new Row(List.of(entry), true));
                continue;
            }
            current.add(entry);
            if (current.size() == columns) {
                rows.add(new Row(List.copyOf(current), false));
                current.clear();
            }
        }
        if (!current.isEmpty()) {
            rows.add(new Row(List.copyOf(current), false));
        }
        return rows;
    }

    private static int getCellWidth(int columns, int maxContentWidth) {
        int preferredWidth = switch (columns) {
            case 1 -> SINGLE_COLUMN_WIDTH;
            case 2 -> TWO_COLUMN_WIDTH;
            default -> THREE_COLUMN_WIDTH;
        };
        if (columns <= 1) {
            return Math.min(preferredWidth, maxContentWidth);
        }
        int available = Math.max(96, maxContentWidth - ((columns - 1) * GAP));
        return Math.max(96, Math.min(preferredWidth, available / columns));
    }

    private static List<Entry> normalizeEntriesForColumns(List<Entry> entries, TextRenderer textRenderer, int columns, int cellWidth, int contentWidth) {
        if (columns <= 1) {
            return entries;
        }
        List<Entry> normalized = new ArrayList<>(entries.size());
        int fullWidthLimit = Math.max(80, contentWidth - VALUE_RIGHT_PADDING);
        int cellLimit = Math.max(64, cellWidth - VALUE_RIGHT_PADDING);
        for (Entry entry : entries) {
            if (entry.fullWidth()) {
                normalized.add(entry);
                continue;
            }
            int estimatedWidth = estimateEntryWidth(textRenderer, entry, false);
            if (estimatedWidth > cellLimit && estimateEntryWidth(textRenderer, entry, true) <= fullWidthLimit) {
                normalized.add(new Entry(entry.label(), entry.value(), entry.color(), true));
                continue;
            }
            normalized.add(entry);
        }
        return normalized;
    }

    private static int estimateEntryWidth(TextRenderer textRenderer, Entry entry, boolean fullWidth) {
        int preferredLabelWidth = Math.max(LABEL_WIDTH, textRenderer.getWidth(entry.label()) + LABEL_VALUE_GAP);
        int maxLabelWidth = Math.max(LABEL_WIDTH, fullWidth ? 120 : 92);
        int labelWidth = Math.min(preferredLabelWidth, maxLabelWidth);
        return labelWidth + textRenderer.getWidth(entry.value()) + VALUE_RIGHT_PADDING;
    }

    private static void drawEntry(DrawContext ctx, TextRenderer textRenderer, int x, int y, int width, Entry entry, boolean fullWidth) {
        int labelColor = entry.color() == CRITICAL ? HEADER : DIM;
        int maxLabelWidth = Math.max(LABEL_WIDTH, fullWidth ? width / 3 : width / 2);
        String label = trimWithEllipsis(textRenderer, entry.label(), maxLabelWidth);
        ctx.drawText(textRenderer, label, x, y, labelColor, false);
        int preferredLabelWidth = Math.max(LABEL_WIDTH, textRenderer.getWidth(label) + LABEL_VALUE_GAP);
        int labelWidth = Math.min(preferredLabelWidth, maxLabelWidth);
        int valueX = x + labelWidth;
        int valueWidth = Math.max(24, width - (valueX - x) - VALUE_RIGHT_PADDING);
        if (entry.segments() != null && !entry.segments().isEmpty()) {
            drawSegmentedValue(ctx, textRenderer, valueX, y, valueWidth, entry.segments(), entry.color());
            return;
        }
        String value = trimWithEllipsis(textRenderer, entry.value(), valueWidth);
        ctx.drawText(textRenderer, value, valueX, y, entry.color(), false);
    }

    private static void drawSegmentedValue(DrawContext ctx, TextRenderer textRenderer, int x, int y, int width, List<ValueSegment> segments, int fallbackColor) {
        int usedWidth = 0;
        for (int i = 0; i < segments.size(); i++) {
            int remainingWidth = width - usedWidth;
            if (remainingWidth <= 0) {
                return;
            }
            ValueSegment segment = segments.get(i);
            String text = segment.text();
            if (text == null || text.isEmpty()) {
                continue;
            }
            boolean last = i == segments.size() - 1;
            String shown = last ? trimWithEllipsis(textRenderer, text, remainingWidth) : textRenderer.trimToWidth(text, remainingWidth);
            if (shown.isEmpty()) {
                return;
            }
            ctx.drawText(textRenderer, shown, x + usedWidth, y, segment.color(), false);
            usedWidth += textRenderer.getWidth(shown);
            if (!shown.equals(text)) {
                return;
            }
        }
        if (usedWidth == 0) {
            ctx.drawText(textRenderer, trimWithEllipsis(textRenderer, "n/a", width), x, y, fallbackColor, false);
        }
    }

    private static String trimWithEllipsis(TextRenderer textRenderer, String value, int width) {
        if (value == null || value.isEmpty() || width <= 0) {
            return "";
        }
        if (textRenderer.getWidth(value) <= width) {
            return value;
        }
        String ellipsis = "...";
        int ellipsisWidth = textRenderer.getWidth(ellipsis);
        if (ellipsisWidth >= width) {
            return textRenderer.trimToWidth(ellipsis, width);
        }
        return textRenderer.trimToWidth(value, Math.max(0, width - ellipsisWidth)) + ellipsis;
    }

    private static String displayedFpsText(FrameTimelineProfiler frame) {
        return displayedMetric("fps.primary", () -> {
            long now = hudNow();
            return format0(frame.getCurrentFps()) + " now | " + format0(frame.getAverageFps()) + " avg" + rateSuffix("fps", frame.getCurrentFps(), now, "fps/s", ConfigManager.isHudShowFpsRateOfChange());
        });
    }

    private static String displayedLowFpsText(FrameTimelineProfiler frame) {
        return displayedMetric("fps.lows", () -> "1% " + format0(frame.getOnePercentLowFps()) + " | 0.1% " + format0(frame.getPointOnePercentLowFps()));
    }

    private static String displayedMemoryText(MemoryProfiler.Snapshot memory) {
        return displayedMetric("memory.primary", () -> formatMemoryDisplay(memory, hudNow()));
    }

    private static void refreshDisplayedMetrics(FrameTimelineProfiler frame, MemoryProfiler.Snapshot memory, ProfilerManager.ProfilerSnapshot snapshot, SystemMetricsProfiler.Snapshot system) {
        displayedFpsText(frame);
        displayedLowFpsText(frame);
        displayedMemoryText(memory);
        displayedMetric("frame.stats", () -> compactFrameText(frame));
        displayedMetric("frame.budget", () -> frameBudgetText(frame));
        displayedMetric("tick.client", () -> tickText("tick.client", TickProfiler.getInstance().getAverageClientTickNs() / 1_000_000.0));
        displayedMetric("tick.server", () -> tickText("tick.server", TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0));
        displayedMetric("vram", () -> vramText(system));
        displayedMetric("network", () -> networkText(system));
        displayedMetric("chunk.activity", () -> chunkActivityText(system));
        displayedMetric("world.entities", () -> worldEntitiesText(snapshot));
        displayedMetric("world.chunks", () -> worldChunksText(snapshot));
        displayedMetric("disk.io", () -> diskIoText(system));
        displayedMetric("input.latency", () -> inputLatencyText(system));
        displayedMetric("logic.main", () -> shorten(system.mainLogicSummary().replace("Main Logic: ", ""), 36));
        displayedMetric("logic.background", () -> shorten(system.backgroundSummary().replace("Background: ", ""), 36));
        displayedMetric("session", () -> formatDuration(snapshot.sessionLoggingElapsedMillis()) + " / " + formatDuration(ConfigManager.getSessionDurationSeconds() * 1000L));
    }

    private static String displayedMetric(String key, Supplier<String> supplier) {
        long now = hudNow();
        DisplayCacheEntry cached = displayCache.get(key);
        if (cached != null && !shouldRefreshDisplayedMetric(now, cached.updatedAtMillis(), ConfigManager.getMetricsUpdateIntervalMs())) {
            return cached.value();
        }
        String value = supplier.get();
        displayCache.put(key, new DisplayCacheEntry(now, value));
        recordCacheKey(displayCacheOrder, key, MAX_DISPLAY_CACHE_ENTRIES, displayCache);
        return value;
    }

    static boolean shouldRefreshDisplayedMetric(long nowMillis, long lastUpdatedMillis, int intervalMillis) {
        return lastUpdatedMillis == 0L || nowMillis - lastUpdatedMillis >= intervalMillis;
    }

    private static String compactFrameText(FrameTimelineProfiler frame) {
        double avgFrameMs = frame.getAverageFrameNs() / 1_000_000.0;
        return format1(avgFrameMs) + " avg | " + format1(frame.getMaxFrameNs() / 1_000_000.0) + " max" + rateSuffix("frame", avgFrameMs, hudNow(), "ms/s", ConfigManager.isHudShowFrameRateOfChange());
    }

    private static String formatMemoryDisplay(MemoryProfiler.Snapshot memory, long now) {
        long usedBytes = Math.max(0L, memory.heapUsedBytes());
        long maxBytes = Math.max(usedBytes, memory.heapMaxBytes() > 0 ? memory.heapMaxBytes() : memory.heapCommittedBytes());
        memoryRateSamples.addLast(new MemoryRateSample(now, usedBytes));
        trimDeque(memoryRateSamples, MAX_MEMORY_RATE_SAMPLES);
        long cutoff = now - 4000L;
        while (memoryRateSamples.size() > 2 && memoryRateSamples.peekFirst() != null && memoryRateSamples.peekFirst().capturedAtMillis() < cutoff) {
            memoryRateSamples.pollFirst();
        }

        String base = formatMegabytes(usedBytes) + "/" + formatMegabytes(maxBytes) + " MB";
        if (!ConfigManager.isHudShowMemoryAllocationRate()) {
            return base;
        }
        double rateMbPerSecond = computeAverageAllocationRateMbPerSecond();
        if ((Double.isNaN(rateMbPerSecond) || rateMbPerSecond < 0.05) && !ConfigManager.isHudShowZeroRateOfChange()) {
            return base;
        }
        return base + " (alloc " + allocationRateText(rateMbPerSecond) + ")";
    }

    private static String formatUtilAndTemp(SystemMetricsProfiler.Snapshot system, boolean cpu) {
        long now = hudNow();
        String sensorKey = cpu ? "cpu" : "gpu";
        double loadPercent = cpu ? system.cpuCoreLoadPercent() : system.gpuCoreLoadPercent();
        double temperatureC = cpu ? system.cpuTemperatureC() : system.gpuTemperatureC();
        String load = loadPercent >= 0.0 ? format0(loadPercent) + "%" : "n/a";
        String loadRateSuffix = ConfigManager.isHudShowUtilizationRateOfChange()
                ? heldSensorRateSuffix(sensorKey + ".load", loadPercent, now, "%/s")
                : "";
        if (!ConfigManager.isHudShowTemperatures()) {
            return load + appendSuffix(loadRateSuffix);
        }
        String temp = cpu
                ? (temperatureC >= 0.0 ? format0(temperatureC) + "C" : "n/a")
                : TelemetryTextFormatter.formatGpuTemperatureCompact(system);
        if (!ConfigManager.isHudShowUtilizationRateOfChange()) {
            return load + " / " + temp;
        }
        String tempRateSuffix = temperatureC < 0.0 ? "" : heldSensorRateSuffix(sensorKey + ".temp", temperatureC, now, "C/s");
        String combined = joinRateParts(loadRateSuffix, tempRateSuffix);
        return load + " / " + temp + appendSuffix(combined);
    }

    private static Entry buildAutoFocusEntry(ProfilerManager profilerManager, ProfilerManager.ProfilerSnapshot snapshot, ProfilerManager.RuleFinding highestFinding, double latestFrameMs, int alertColor) {
        if (highestFinding != null) {
            return new Entry("Focus", displayedMetric("focus.auto.finding", () -> shorten(highestFinding.category() + ": " + highestFinding.message(), 72)), alertColor, true);
        }
        if (!snapshot.spikes().isEmpty()) {
            ProfilerManager.SpikeCapture latestSpike = snapshot.spikes().get(0);
            return new Entry("Focus", displayedMetric("focus.auto.spike", () -> "Spike " + format1(latestSpike.frameDurationMs()) + " ms | " + shorten(latestSpike.likelyBottleneck(), 40)), alertColor, true);
        }
        String bottleneck = profilerManager.getCurrentBottleneckLabel();
        if (bottleneck != null && !bottleneck.isBlank()) {
            return new Entry("Focus", displayedMetric("focus.auto.bottleneck", () -> shorten(bottleneck, 72)), alertColor, true);
        }
        double targetFrameMs = targetFrameBudgetMs();
        if (latestFrameMs > targetFrameMs) {
            return new Entry("Focus", displayedMetric("focus.auto.budget", () -> "Frame budget exceeded by " + format1(Math.max(0.0, latestFrameMs - targetFrameMs)) + " ms"), alertColor, true);
        }
        return null;
    }

    private static String frameBudgetText(FrameTimelineProfiler frame) {
        long now = hudNow();
        double currentFrameMs = frame.getLatestFrameNs() / 1_000_000.0;
        double targetFrameMs = targetFrameBudgetMs();
        double overBudgetMs = currentFrameMs - targetFrameMs;
        String budgetState = overBudgetMs > 0.0 ? "+" + format1(overBudgetMs) + " over" : format1(Math.abs(overBudgetMs)) + " headroom";
        return format1(currentFrameMs) + "/" + format1(targetFrameMs) + " ms | " + budgetState + rateSuffix("frame.budget", currentFrameMs, now, "ms/s", ConfigManager.isHudShowFrameRateOfChange());
    }

    private static String vramText(SystemMetricsProfiler.Snapshot system) {
        long now = hudNow();
        if (system.vramUsedBytes() < 0 || system.vramTotalBytes() <= 0) {
            return "n/a";
        }
        String base = compactBytes(system.vramUsedBytes()) + "/" + compactBytes(system.vramTotalBytes());
        if (system.vramPagingActive() && system.vramPagingBytes() > 0) {
            base += " | paging " + compactBytes(system.vramPagingBytes());
        }
        if (!ConfigManager.isHudShowVramRateOfChange()) {
            return base;
        }
        return base + appendSuffix(optionalByteRateSuffix("vram.used", system.vramUsedBytes(), now, "/s"));
    }

    private static String networkText(SystemMetricsProfiler.Snapshot system) {
        long now = hudNow();
        String down = compactIo(system.bytesReceivedPerSecond());
        String up = compactIo(system.bytesSentPerSecond());
        String base = "D " + down + " | U " + up;
        if (!ConfigManager.isHudShowNetworkRateOfChange()) {
            return base;
        }
        String downRate = optionalByteRateSuffix("network.down", system.bytesReceivedPerSecond(), now, "/s/s");
        String upRate = optionalByteRateSuffix("network.up", system.bytesSentPerSecond(), now, "/s/s");
        return base + appendSuffix(joinRateParts(downRate, upRate));
    }

    private static String chunkActivityText(SystemMetricsProfiler.Snapshot system) {
        long now = hudNow();
        String base = "G " + system.chunksGenerating() + " | M " + system.chunksMeshing() + " | U " + system.chunksUploading();
        if (!ConfigManager.isHudShowChunkActivityRateOfChange()) {
            return base;
        }
        String generatingRate = optionalRateSuffix("chunks.gen", system.chunksGenerating(), now, "/s");
        String meshingRate = optionalRateSuffix("chunks.mesh", system.chunksMeshing(), now, "/s");
        String uploadRate = optionalRateSuffix("chunks.upload", system.chunksUploading(), now, "/s");
        String combined = joinRateParts(joinRateParts(generatingRate, meshingRate), uploadRate);
        return base + appendSuffix(combined);
    }

    private static String inputLatencyText(SystemMetricsProfiler.Snapshot system) {
        double latencyMs = system.mouseInputLatencyMs();
        if (latencyMs < 0.0) {
            return "n/a";
        }
        return format1(latencyMs) + " ms" + rateSuffix("input.latency", latencyMs, hudNow(), "ms/s", ConfigManager.isHudShowInputLatencyRateOfChange());
    }

    private static int frameBudgetColor(FrameTimelineProfiler frame) {
        return budgetColorForFrame(frame.getLatestFrameNs() / 1_000_000.0);
    }

    private static int tickColor(double millis, boolean server) {
        if (!ConfigManager.isHudBudgetColorMode()) {
            return TEXT;
        }
        double warnThreshold = server ? 30.0 : targetFrameBudgetMs();
        double errorThreshold = server ? 50.0 : 25.0;
        double criticalThreshold = server ? 75.0 : 40.0;
        return severityColorForValue(millis, warnThreshold, errorThreshold, criticalThreshold, TEXT);
    }

    private static int utilizationColor(SystemMetricsProfiler.Snapshot system, boolean cpu) {
        if (!ConfigManager.isHudBudgetColorMode()) {
            return ACCENT;
        }
        double load = cpu ? system.cpuCoreLoadPercent() : system.gpuCoreLoadPercent();
        double temperature = cpu ? system.cpuTemperatureC() : system.gpuTemperatureC();
        int loadColor = severityColorForValue(load, 80.0, 92.0, 98.0, ACCENT);
        int tempColor = severityColorForValue(temperature, 75.0, 85.0, 92.0, ACCENT);
        return maxSeverityColor(loadColor, tempColor, ACCENT);
    }

    private static int memoryColor(MemoryProfiler.Snapshot memory) {
        if (!ConfigManager.isHudBudgetColorMode()) {
            return DIM;
        }
        long maxBytes = memory.heapMaxBytes() > 0 ? memory.heapMaxBytes() : memory.heapCommittedBytes();
        double usage = maxBytes > 0 ? (memory.heapUsedBytes() * 100.0 / maxBytes) : 0.0;
        return severityColorForValue(usage, 75.0, 88.0, 96.0, DIM);
    }

    private static int vramColor(SystemMetricsProfiler.Snapshot system) {
        if (!ConfigManager.isHudBudgetColorMode()) {
            return DIM;
        }
        if (system.vramPagingActive()) {
            return ERROR;
        }
        double usage = system.vramTotalBytes() > 0 ? (system.vramUsedBytes() * 100.0 / system.vramTotalBytes()) : 0.0;
        return severityColorForValue(usage, 75.0, 88.0, 96.0, DIM);
    }

    private static int networkColor(SystemMetricsProfiler.Snapshot system) {
        if (!ConfigManager.isHudBudgetColorMode()) {
            return DIM;
        }
        double packetLatency = system.packetProcessingLatencyMs();
        return severityColorForValue(packetLatency, 30.0, 75.0, 140.0, DIM);
    }

    private static int chunkActivityColor(SystemMetricsProfiler.Snapshot system) {
        if (!ConfigManager.isHudBudgetColorMode()) {
            return DIM;
        }
        double total = system.chunksGenerating() + system.chunksMeshing() + system.chunksUploading();
        return severityColorForValue(total, 2.0, 5.0, 8.0, DIM);
    }

    private static int inputLatencyColor(SystemMetricsProfiler.Snapshot system) {
        if (!ConfigManager.isHudBudgetColorMode()) {
            return DIM;
        }
        return severityColorForValue(system.mouseInputLatencyMs(), 20.0, 40.0, 80.0, DIM);
    }

    private static int budgetColorForFrame(double frameMs) {
        if (!ConfigManager.isHudBudgetColorMode()) {
            return TEXT;
        }
        double targetFrameMs = targetFrameBudgetMs();
        return severityColorForValue(frameMs, targetFrameMs, targetFrameMs * 1.5, Math.max(40.0, targetFrameMs * 2.5), TEXT);
    }

    private static double targetFrameBudgetMs() {
        return ConfigManager.getFrameBudgetTargetFrameMs();
    }

    private static int severityColorForValue(double value, double warnThreshold, double errorThreshold, double criticalThreshold, int normalColor) {
        if (!Double.isFinite(value) || value < 0.0) {
            return normalColor;
        }
        if (value >= criticalThreshold) {
            return CRITICAL;
        }
        if (value >= errorThreshold) {
            return ERROR;
        }
        if (value >= warnThreshold) {
            return WARN;
        }
        return normalColor;
    }

    private static int maxSeverityColor(int first, int second, int normalColor) {
        return severityRankForColor(first, normalColor) >= severityRankForColor(second, normalColor) ? first : second;
    }

    private static int severityRankForColor(int color, int normalColor) {
        if (color == CRITICAL) return 3;
        if (color == ERROR) return 2;
        if (color == WARN) return 1;
        return color == normalColor ? 0 : 0;
    }

    private static String optionalByteRateSuffix(String key, long currentValue, long now, String units) {
        RateSample previous = rateSamples.get(key);
        if (previous == null) {
            String initial = ConfigManager.isHudShowZeroRateOfChange() ? "~0 B" + units : "";
            rateSamples.put(key, new RateSample(now, currentValue, initial));
            recordCacheKey(rateSampleOrder, key, MAX_RATE_CACHE_ENTRIES, rateSamples);
            return initial;
        }
        long elapsedMillis = now - previous.capturedAtMillis();
        if (elapsedMillis < 45L) {
            return previous.displaySuffix();
        }
        double deltaPerSecond = (currentValue - previous.value()) * 1000.0 / Math.max(1L, elapsedMillis);
        String suffix = ((!Double.isFinite(deltaPerSecond) || Math.abs(deltaPerSecond) < 1.0) && !ConfigManager.isHudShowZeroRateOfChange())
                ? ""
                : signedByteRate(deltaPerSecond, units);
        rateSamples.put(key, new RateSample(now, currentValue, suffix));
        recordCacheKey(rateSampleOrder, key, MAX_RATE_CACHE_ENTRIES, rateSamples);
        return suffix;
    }

    private static String signedByteRate(double value, String units) {
        if (!Double.isFinite(value) || Math.abs(value) < 1.0) {
            return "~0 B" + units;
        }
        return (value > 0.0 ? "+" : "-") + compactBytes(Math.round(Math.abs(value))) + units;
    }

    private static String compactBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return format1(bytes / 1024.0) + " KB";
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return format1(bytes / (1024.0 * 1024.0)) + " MB";
        }
        return format1(bytes / (1024.0 * 1024.0 * 1024.0)) + " GB";
    }

    private static String millisText(double millis) {
        return format1(millis) + " ms";
    }

    private static String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return pad2(minutes) + ":" + pad2(seconds);
    }

    private static String pad2(long value) {
        return value < 10 ? "0" + value : Long.toString(value);
    }

    private static String format0(double value) {
        return Long.toString(Math.round(value));
    }

    private static String format1(double value) {
        long scaled = Math.round(value * 10.0);
        long whole = scaled / 10L;
        long decimal = Math.abs(scaled % 10L);
        return whole + "." + decimal;
    }

    private static String formatMegabytes(long bytes) {
        return format0(bytes / (1024.0 * 1024.0));
    }

    private static double computeAverageAllocationRateMbPerSecond() {
        if (memoryRateSamples.size() < 2) {
            return Double.NaN;
        }
        long totalAllocatedBytes = 0L;
        long totalElapsedMillis = 0L;
        MemoryRateSample previous = null;
        for (MemoryRateSample sample : memoryRateSamples) {
            if (previous != null) {
                long elapsedMillis = Math.max(1L, sample.capturedAtMillis() - previous.capturedAtMillis());
                long allocatedBytes = Math.max(0L, sample.usedBytes() - previous.usedBytes());
                totalAllocatedBytes += allocatedBytes;
                totalElapsedMillis += elapsedMillis;
            }
            previous = sample;
        }
        if (totalElapsedMillis <= 0L) {
            return Double.NaN;
        }
        return (totalAllocatedBytes / (1024.0 * 1024.0)) * 1000.0 / totalElapsedMillis;
    }

    private static String allocationRateText(double rateMbPerSecond) {
        if (!Double.isFinite(rateMbPerSecond) || rateMbPerSecond < 0.05) {
            return "0.0 MB/s";
        }
        return format1(Math.max(0.0, rateMbPerSecond)) + " MB/s";
    }

    private static String signedSensorRate(double value, String units) {
        if (!Double.isFinite(value) || Math.abs(value) < 0.05) {
            return "~0 " + units;
        }
        return (value > 0.0 ? "+" : "-") + format1(Math.abs(value)) + " " + units;
    }

    private static String worldEntitiesText(ProfilerManager.ProfilerSnapshot snapshot) {
        long now = hudNow();
        return Integer.toString(snapshot.entityCounts().totalEntities()) + rateSuffix("world.entities", snapshot.entityCounts().totalEntities(), now, "/s", ConfigManager.isHudShowWorldRateOfChange());
    }

    private static String worldChunksText(ProfilerManager.ProfilerSnapshot snapshot) {
        long now = hudNow();
        String loaded = Long.toString(snapshot.chunkCounts().loadedChunks());
        String rendered = Long.toString(snapshot.chunkCounts().renderedChunks());
        if (!ConfigManager.isHudShowWorldRateOfChange()) {
            return loaded + "/" + rendered;
        }
        String loadedRate = optionalRateSuffix("world.chunks.loaded", snapshot.chunkCounts().loadedChunks(), now, "/s");
        String renderedRate = optionalRateSuffix("world.chunks.rendered", snapshot.chunkCounts().renderedChunks(), now, "/s");
        String combined = joinRateParts(loadedRate, renderedRate);
        return loaded + "/" + rendered + appendSuffix(combined);
    }

    private static String diskIoText(SystemMetricsProfiler.Snapshot system) {
        long now = hudNow();
        String read = compactIo(system.diskReadBytesPerSecond());
        String write = compactIo(system.diskWriteBytesPerSecond());
        if (!ConfigManager.isHudShowDiskIoRateOfChange()) {
            return "R " + read + " | W " + write;
        }
        String readRate = optionalRateSuffix("disk.read", system.diskReadBytesPerSecond(), now, "B/s/s");
        String writeRate = optionalRateSuffix("disk.write", system.diskWriteBytesPerSecond(), now, "B/s/s");
        String combined = joinRateParts(readRate, writeRate);
        return "R " + read + " | W " + write + appendSuffix(combined);
    }

    private static Entry networkEntry(SystemMetricsProfiler.Snapshot system) {
        return new Entry("Network", displayedMetric("network", () -> networkText(system)), networkColor(system), true, networkSegments(system));
    }

    private static Entry diskEntry(SystemMetricsProfiler.Snapshot system) {
        return new Entry("Disk I/O", displayedMetric("disk.io", () -> diskIoText(system)), DIM, true, diskSegments(system));
    }

    private static List<ValueSegment> networkSegments(SystemMetricsProfiler.Snapshot system) {
        return List.of(
                new ValueSegment("IN ", FLOW_IN),
                new ValueSegment(compactIo(system.bytesReceivedPerSecond()), FLOW_IN),
                new ValueSegment(" | ", TEXT),
                new ValueSegment("OUT ", FLOW_OUT),
                new ValueSegment(compactIo(system.bytesSentPerSecond()), FLOW_OUT),
                new ValueSegment(networkRateSuffixText(system), DIM)
        );
    }

    private static List<ValueSegment> diskSegments(SystemMetricsProfiler.Snapshot system) {
        return List.of(
                new ValueSegment("R ", FLOW_IN),
                new ValueSegment(compactIo(system.diskReadBytesPerSecond()), FLOW_IN),
                new ValueSegment(" | ", TEXT),
                new ValueSegment("W ", FLOW_OUT),
                new ValueSegment(compactIo(system.diskWriteBytesPerSecond()), FLOW_OUT),
                new ValueSegment(diskRateSuffixText(system), DIM)
        );
    }

    private static String networkRateSuffixText(SystemMetricsProfiler.Snapshot system) {
        if (!ConfigManager.isHudShowNetworkRateOfChange()) {
            return "";
        }
        long now = hudNow();
        String downRate = optionalByteRateSuffix("network.down", system.bytesReceivedPerSecond(), now, "/s/s");
        String upRate = optionalByteRateSuffix("network.up", system.bytesSentPerSecond(), now, "/s/s");
        return appendSuffix(joinRateParts(downRate, upRate));
    }

    private static String diskRateSuffixText(SystemMetricsProfiler.Snapshot system) {
        if (!ConfigManager.isHudShowDiskIoRateOfChange()) {
            return "";
        }
        long now = hudNow();
        String readRate = optionalRateSuffix("disk.read", system.diskReadBytesPerSecond(), now, "B/s/s");
        String writeRate = optionalRateSuffix("disk.write", system.diskWriteBytesPerSecond(), now, "B/s/s");
        return appendSuffix(joinRateParts(readRate, writeRate));
    }

    private static String tickText(String key, double millis) {
        return format1(millis) + " ms" + rateSuffix(key, millis, hudNow(), "ms/s", ConfigManager.isHudShowTickRateOfChange());
    }

    private static String rateSuffix(String key, double currentValue, long now, String units, boolean enabled) {
        if (!enabled) {
            return "";
        }
        String rate = optionalRateSuffix(key, currentValue, now, units);
        return appendSuffix(rate);
    }

    private static String optionalRateSuffix(String key, double currentValue, long now, String units) {
        RateSample previous = rateSamples.get(key);
        if (previous == null) {
            String initial = ConfigManager.isHudShowZeroRateOfChange() ? "~0 " + units : "";
            rateSamples.put(key, new RateSample(now, currentValue, initial));
            recordCacheKey(rateSampleOrder, key, MAX_RATE_CACHE_ENTRIES, rateSamples);
            return initial;
        }

        long elapsedMillis = now - previous.capturedAtMillis();
        if (elapsedMillis < 45L) {
            return previous.displaySuffix();
        }

        double deltaPerSecond = (currentValue - previous.value()) * 1000.0 / Math.max(1L, elapsedMillis);
        String suffix = ((!Double.isFinite(deltaPerSecond) || Math.abs(deltaPerSecond) < 0.05) && !ConfigManager.isHudShowZeroRateOfChange())
                ? ""
                : signedDynamicRate(deltaPerSecond, units);
        rateSamples.put(key, new RateSample(now, currentValue, suffix));
        recordCacheKey(rateSampleOrder, key, MAX_RATE_CACHE_ENTRIES, rateSamples);
        return suffix;
    }

    private static String signedDynamicRate(double value, String units) {
        if (!Double.isFinite(value) || Math.abs(value) < 0.05) {
            return "~0 " + units;
        }
        return (value > 0.0 ? "+" : "-") + format1(Math.abs(value)) + " " + units;
    }

    private static String heldSensorRateSuffix(String key, double currentValue, long now, String units) {
        SensorRateSample previous = sensorRateSamples.get(key);
        if (!Double.isFinite(currentValue) || currentValue < 0.0) {
            if (previous == null) {
                return ConfigManager.isHudShowZeroRateOfChange() ? "~0 " + units : "";
            }
            return previous.displaySuffix();
        }
        if (previous == null) {
            String initial = ConfigManager.isHudShowZeroRateOfChange() ? "~0 " + units : "";
            sensorRateSamples.put(key, new SensorRateSample(now, currentValue, now, currentValue, initial));
            recordCacheKey(sensorRateSampleOrder, key, MAX_RATE_CACHE_ENTRIES, sensorRateSamples);
            return initial;
        }

        if (Math.abs(currentValue - previous.lastObservedValue()) < 0.05) {
            sensorRateSamples.put(key, previous.withObserved(now, currentValue));
            recordCacheKey(sensorRateSampleOrder, key, MAX_RATE_CACHE_ENTRIES, sensorRateSamples);
            return previous.displaySuffix();
        }

        long elapsedMillis = Math.max(1L, now - previous.lastChangeAtMillis());
        double deltaPerSecond = (currentValue - previous.lastChangeValue()) * 1000.0 / elapsedMillis;
        String suffix = ((!Double.isFinite(deltaPerSecond) || Math.abs(deltaPerSecond) < 0.05) && !ConfigManager.isHudShowZeroRateOfChange())
                ? ""
                : signedDynamicRate(deltaPerSecond, units);
        sensorRateSamples.put(key, new SensorRateSample(now, currentValue, now, currentValue, suffix));
        recordCacheKey(sensorRateSampleOrder, key, MAX_RATE_CACHE_ENTRIES, sensorRateSamples);
        return suffix;
    }

    private static <T> void recordCacheKey(ConcurrentLinkedDeque<String> order, String key, int maxEntries, Map<String, T> cache) {
        order.remove(key);
        order.addLast(key);
        while (cache.size() > maxEntries) {
            String eldest = order.pollFirst();
            if (eldest == null) {
                break;
            }
            cache.remove(eldest);
        }
    }

    private static <T> void trimDeque(ConcurrentLinkedDeque<T> deque, int maxEntries) {
        while (deque.size() > maxEntries) {
            deque.pollFirst();
        }
    }

    private static String joinRateParts(String first, String second) {
        if ((first == null || first.isBlank()) && (second == null || second.isBlank())) {
            return "";
        }
        if (first == null || first.isBlank()) {
            return second;
        }
        if (second == null || second.isBlank()) {
            return first;
        }
        return first + " / " + second;
    }

    private static String appendSuffix(String suffix) {
        return suffix == null || suffix.isBlank() ? "" : " (" + suffix + ")";
    }

    private static String compactIo(long bytesPerSecond) {
        if (bytesPerSecond < 0) {
            return "n/a";
        }
        if (bytesPerSecond >= 1024L * 1024L) {
            return format1(bytesPerSecond / (1024.0 * 1024.0)) + " MB/s";
        }
        if (bytesPerSecond >= 1024L) {
            return format1(bytesPerSecond / 1024.0) + " KB/s";
        }
        return bytesPerSecond + " B/s";
    }

    private static String shorten(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "n/a";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private static int severityRank(String severity) {
        return switch (severity == null ? "info" : severity.toLowerCase()) {
            case "critical" -> 3;
            case "error" -> 2;
            case "warning" -> 1;
            default -> 0;
        };
    }

    private static int severityColor(String severity, boolean actionableWarning) {
        return switch (severityRank(severity)) {
            case 3 -> CRITICAL;
            case 2 -> ERROR;
            case 1 -> WARN;
            default -> actionableWarning ? WARN : DIM;
        };
    }

    private static int applyHudTransparency(int color) {
        int alpha = (color >>> 24) & 0xFF;
        int scaledAlpha = alpha * ConfigManager.getHudTransparencyPercent() / 100;
        return (color & 0x00FFFFFF) | (scaledAlpha << 24);
    }

    private record Entry(String label, String value, int color, boolean fullWidth, List<ValueSegment> segments) {
        private Entry(String label, String value, int color, boolean fullWidth) {
            this(label, value, color, fullWidth, null);
        }
    }

    private record Row(List<Entry> entries, boolean fullWidth) {
    }

    private record MemoryRateSample(long capturedAtMillis, long usedBytes) {
    }

    private record DisplayCacheEntry(long updatedAtMillis, String value) {
    }

    private record HudLayoutCache(ProfilerManager.ProfilerSnapshot snapshot, int configHash, int screenW, int screenH, int columns, boolean actionableWarning, List<Row> rows, int contentWidth, int cellWidth, int width, int height, int x, int y, int borderColor) {
    }

    private record ValueSegment(String text, int color) {
    }

    private record RateSample(long capturedAtMillis, double value, String displaySuffix) {
    }

    private record SensorRateSample(long lastObservedAtMillis, double lastObservedValue, long lastChangeAtMillis, double lastChangeValue, String displaySuffix) {
        private SensorRateSample withObserved(long observedAtMillis, double observedValue) {
            return new SensorRateSample(observedAtMillis, observedValue, lastChangeAtMillis, lastChangeValue, displaySuffix);
        }
    }
}


