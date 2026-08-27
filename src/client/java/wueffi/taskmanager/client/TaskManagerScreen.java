package wueffi.taskmanager.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;
import wueffi.taskmanager.client.AttributionModelBuilder.EffectiveCpuAttribution;
import wueffi.taskmanager.client.AttributionModelBuilder.EffectiveGpuAttribution;
import wueffi.taskmanager.client.AttributionModelBuilder.EffectiveMemoryAttribution;
import wueffi.taskmanager.client.util.ConfigManager;
import wueffi.taskmanager.client.util.ModIconCache;
import wueffi.taskmanager.client.util.ModTimingSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

public class TaskManagerScreen extends Screen {

    record LagMapLayout(int left, int miniTabY, int summaryY, int mapRenderY, int mapWidth, int mapHeight, int cell, int radius, int mapTop) {}

    record FindingClickTarget(int x, int y, int width, int height, String key) {}

    record SpikePinClickTarget(int x, int y, int width, int height, ProfilerManager.SpikeCapture spike, boolean clearPin) {}

    record ModalLayout(int x, int y, int width, int height) {}

    record AttributionListLayout(int listWidth, int headerY, int listY, int listHeight) {}

    record MemoryListLayout(int tableWidth, int searchY, int headerY, int listY, int listHeight) {}

    record SliderLayout(int x, int y, int width, int height) {}

    public enum TableId {
        TASKS,
        GPU,
        MEMORY
    }

    public enum WorldMiniTab {
        LAG_MAP,
        ENTITIES,
        CHUNKS,
        BLOCK_ENTITIES
    }

    public enum SystemMiniTab {
        OVERVIEW,
        CPU_GRAPH,
        GPU_GRAPH,
        MEMORY_GRAPH
    }

    public enum GraphMetricTab {
        LOAD,
        TEMPERATURE
    }

    public enum ColorSetting {
        CPU,
        GPU,
        WORLD_ENTITIES,
        WORLD_CHUNKS_LOADED,
        WORLD_CHUNKS_RENDERED
    }

    public enum StartupSort {
        NAME,
        START,
        END,
        ACTIVE,
        ENTRYPOINTS,
        REGISTRATIONS
    }

    public enum TaskSort {
        NAME,
        CPU,
        THREADS,
        SAMPLES,
        INVOKES;

        TaskSort next() {
            TaskSort[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public enum GpuSort {
        NAME,
        EST_GPU,
        THREADS,
        GPU_MS,
        RENDER_SAMPLES;

        GpuSort next() {
            GpuSort[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public enum MemorySort {
        NAME,
        MEMORY_MB,
        CLASS_COUNT,
        PERCENT;

        MemorySort next() {
            MemorySort[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public enum ThreadSort {
        NAME,
        CPU,
        ALLOC,
        BLOCKED,
        WAITED;

        ThreadSort next() {
            ThreadSort[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    private static final int TAB_HEIGHT = 24;
    static final int PADDING = 8;
    private static final int ROW_HEIGHT = 20;
    static final int ATTRIBUTION_ROW_HEIGHT = 30;
    private static final int STARTUP_ROW_HEIGHT = 28;
    private static final int ICON_SIZE = 16;
    private static final int GRAPH_TOP = 34;
    private static final int GRAPH_HEIGHT = 60;
    private static final String[] TAB_NAMES = {"Tasks", "GPU", "Render", "Startup", "Memory", "Flame", "Timeline", "Network", "Disk", "World", "Threads", "System", "Settings"};
    private static final int ATTRIBUTION_TREND_WINDOW_SECONDS = 30;

    private static final int BG_COLOR = 0xE0101010;
    private static final int PANEL_COLOR = 0xCC1A1A1A;
    private static final int TAB_ACTIVE = 0xFF2A2A2A;
    private static final int TAB_INACTIVE = 0xFF161616;
    private static final int BORDER_COLOR = 0xFF3A3A3A;
    static final int TEXT_PRIMARY = 0xFFE0E0E0;
    static final int TEXT_DIM = 0xFF888888;
    static final int ACCENT_GREEN = 0xFF4CAF50;
    static final int ACCENT_YELLOW = 0xFFFFB300;
    private static final int ACCENT_RED = 0xFFFF6666;
    static final int HEADER_COLOR = 0xFF222222;
    private static final int ROW_ALT = 0x11FFFFFF;
    private static final int PANEL_SOFT = 0x99161616;
    private static final int PANEL_OUTLINE = 0x55343434;
    private static final int AMD_COLOR = 0xFFFF6B6B;
    static final int INTEL_COLOR = 0xFF5EA9FF;
    private static final int NVIDIA_COLOR = 0xFF77DD77;
    private static int lastOpenedTab = 0;
    private static final TabRenderer[] TAB_RENDERERS = new TabRenderer[] {
            (ctx, screen, x, y, w, h, mouseX, mouseY) -> screen.renderTasks(ctx, x, y, w, h, mouseX, mouseY),
            (ctx, screen, x, y, w, h, mouseX, mouseY) -> screen.renderGpu(ctx, x, y, w, h, mouseX, mouseY),
            (ctx, screen, x, y, w, h, mouseX, mouseY) -> screen.renderRender(ctx, x, y, w, h, mouseX, mouseY),
            (ctx, screen, x, y, w, h, mouseX, mouseY) -> screen.renderStartup(ctx, x, y, w, h, mouseX, mouseY),
            (ctx, screen, x, y, w, h, mouseX, mouseY) -> screen.renderMemory(ctx, x, y, w, h, mouseX, mouseY),
            (ctx, screen, x, y, w, h, mouseX, mouseY) -> screen.renderFlamegraph(ctx, x, y, w, h),
            (ctx, screen, x, y, w, h, mouseX, mouseY) -> screen.renderTimeline(ctx, x, y, w, h),
            (ctx, screen, x, y, w, h, mouseX, mouseY) -> screen.renderNetwork(ctx, x, y, w, h),
            (ctx, screen, x, y, w, h, mouseX, mouseY) -> screen.renderDisk(ctx, x, y, w, h),
            (ctx, screen, x, y, w, h, mouseX, mouseY) -> screen.renderWorldTab(ctx, x, y, w, h),
            (ctx, screen, x, y, w, h, mouseX, mouseY) -> screen.renderThreads(ctx, x, y, w, h, mouseX, mouseY),
            (ctx, screen, x, y, w, h, mouseX, mouseY) -> screen.renderSystem(ctx, x, y, w, h),
            (ctx, screen, x, y, w, h, mouseX, mouseY) -> screen.renderSettings(ctx, x, y, w, h, mouseX, mouseY)
    };

    int activeTab = 0;
    int scrollOffset = 0;
    String selectedTaskMod;
    String selectedGpuMod;
    String selectedMemoryMod;
    long selectedThreadId = -1L;
    String selectedSharedFamily;
    ChunkPos selectedLagChunk;
    String tasksSearch = "";
    String gpuSearch = "";
    String memorySearch = "";
    String startupSearch = "";
    String globalSearch = "";
    TableId focusedSearchTable;
    boolean startupSearchFocused;
    boolean globalSearchFocused;
    ColorSetting focusedColorSetting;
    String colorEditValue = "";
    TaskSort taskSort = TaskSort.CPU;
    boolean taskSortDescending = true;
    GpuSort gpuSort = GpuSort.EST_GPU;
    boolean gpuSortDescending = true;
    MemorySort memorySort = MemorySort.MEMORY_MB;
    boolean memorySortDescending = true;
    ThreadSort threadSort = ThreadSort.CPU;
    boolean threadSortDescending = true;
    StartupSort startupSort = StartupSort.ACTIVE;
    boolean startupSortDescending = true;
    WorldMiniTab worldMiniTab = WorldMiniTab.LAG_MAP;
    SystemMiniTab systemMiniTab = SystemMiniTab.OVERVIEW;
    GraphMetricTab cpuGraphMetricTab = GraphMetricTab.LOAD;
    GraphMetricTab gpuGraphMetricTab = GraphMetricTab.LOAD;
    boolean taskEffectiveView = true;
    boolean taskShowSharedRows;
    boolean gpuEffectiveView = true;
    boolean gpuShowSharedRows;
    boolean memoryEffectiveView = true;
    boolean memoryShowSharedRows;
    boolean threadFreeze;
    float uiScale = 1.0f;
    float uiOffsetX = 0.0f;
    float uiOffsetY = 0.0f;
    int layoutWidth;
    int layoutHeight;
    final List<FindingClickTarget> findingClickTargets = new ArrayList<>();
    final TooltipManager tooltipManager = new TooltipManager();
    final List<SpikePinClickTarget> spikePinClickTargets = new ArrayList<>();
    String selectedFindingKey;
    TableId activeDrilldownTable;
    boolean attributionHelpOpen;
    ProfilerManager.ProfilerSnapshot snapshot = ProfilerManager.getInstance().getCurrentSnapshot();
    ProfilerManager.ProfilerSnapshot cachedAttributionSnapshot;
    EffectiveCpuAttribution cachedEffectiveCpuAttribution;
    EffectiveGpuAttribution cachedRawGpuAttribution;
    EffectiveGpuAttribution cachedEffectiveGpuAttribution;
    EffectiveMemoryAttribution cachedEffectiveMemoryAttribution;
    long cachedRawCpuTotalMetric = 1L;
    long cachedRawMemoryTotalBytes = 1L;
    LagMapLayout lastRenderedLagMapLayout;
    boolean draggingHudTransparency;
    List<SystemMetricsProfiler.ThreadDrilldown> frozenThreadRows = List.of();

    public TaskManagerScreen() {
        this(lastOpenedTab);
    }

    public TaskManagerScreen(int initialTab) {
        super(Text.literal("Task Manager"));
        this.activeTab = Math.max(0, Math.min(TAB_NAMES.length - 1, initialTab));
        lastOpenedTab = this.activeTab;
        this.tasksSearch = ConfigManager.getTasksSearch();
        this.gpuSearch = ConfigManager.getGpuSearch();
        this.memorySearch = ConfigManager.getMemorySearch();
        this.startupSearch = ConfigManager.getStartupSearch();
        this.globalSearch = ConfigManager.getGlobalSearch();
        try { this.taskSort = TaskSort.valueOf(ConfigManager.getTaskSort()); } catch (Exception ignored) { this.taskSort = TaskSort.CPU; }
        this.taskSortDescending = ConfigManager.isTaskSortDescending();
        try { this.gpuSort = GpuSort.valueOf(ConfigManager.getGpuSort()); } catch (Exception ignored) { this.gpuSort = GpuSort.EST_GPU; }
        this.gpuSortDescending = ConfigManager.isGpuSortDescending();
        try { this.memorySort = MemorySort.valueOf(ConfigManager.getMemorySort()); } catch (Exception ignored) { this.memorySort = MemorySort.MEMORY_MB; }
        this.memorySortDescending = ConfigManager.isMemorySortDescending();
        try { this.startupSort = StartupSort.valueOf(ConfigManager.getStartupSort()); } catch (Exception ignored) { this.startupSort = StartupSort.ACTIVE; }
        this.startupSortDescending = ConfigManager.isStartupSortDescending();
        this.taskEffectiveView = ConfigManager.isTaskEffectiveView();
        this.taskShowSharedRows = ConfigManager.isTaskShowSharedRows();
        this.gpuEffectiveView = ConfigManager.isGpuEffectiveView();
        this.gpuShowSharedRows = ConfigManager.isGpuShowSharedRows();
        this.memoryEffectiveView = ConfigManager.isMemoryEffectiveView();
        this.memoryShowSharedRows = ConfigManager.isMemoryShowSharedRows();
        FlamegraphProfiler.getInstance().reset();
        FlamegraphProfiler.getInstance().start();
        ProfilerManager.getInstance().onScreenOpened();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        FlamegraphProfiler.getInstance().stop();
        ProfilerManager.getInstance().onScreenClosed();
        lastOpenedTab = activeTab;
        ConfigManager.setTasksSearch(tasksSearch);
        ConfigManager.setGpuSearch(gpuSearch);
        ConfigManager.setMemorySearch(memorySearch);
        ConfigManager.setStartupSearch(startupSearch);
        ConfigManager.setGlobalSearch(globalSearch);
        ConfigManager.setTaskSortState(taskSort.name(), taskSortDescending);
        ConfigManager.setGpuSortState(gpuSort.name(), gpuSortDescending);
        ConfigManager.setMemorySortState(memorySort.name(), memorySortDescending);
        ConfigManager.setStartupSortState(startupSort.name(), startupSortDescending);
        ConfigManager.setTaskAttributionView(taskEffectiveView, taskShowSharedRows);
        ConfigManager.setGpuAttributionView(gpuEffectiveView, gpuShowSharedRows);
        ConfigManager.setMemoryAttributionView(memoryEffectiveView, memoryShowSharedRows);
        super.close();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        snapshot = ProfilerManager.getInstance().getCurrentSnapshot();
        invalidateDerivedAttributionCacheIfSnapshotChanged();
        findingClickTargets.clear();
        tooltipManager.clear();
        spikePinClickTargets.clear();
        updateUiScale();
        int logicalMouseX = toLogicalX(mouseX);
        int logicalMouseY = toLogicalY(mouseY);

        ctx.fill(0, 0, this.width, this.height, BG_COLOR);
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(uiOffsetX, uiOffsetY);
        ctx.getMatrices().scale(uiScale, uiScale);

        int w = getScreenWidth();
        int h = getScreenHeight();

        ctx.fill(0, 0, w, h, BG_COLOR);
        ctx.fill(0, 0, w, 30, 0xFF0A0A0A);
        ctx.drawText(textRenderer, "Task Manager", PADDING, 6, TEXT_PRIMARY, false);

        renderModeButton(ctx, logicalMouseX, logicalMouseY);
        renderHudToggle(ctx, logicalMouseX, logicalMouseY);
        renderExportButton(ctx, logicalMouseX, logicalMouseY);
        renderGlobalSearchBox(ctx, logicalMouseX, logicalMouseY, w);

        double clientTickMs = TickProfiler.getInstance().getAverageClientTickNs() / 1_000_000.0;
        double serverTickMs = TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0;
        String headerMetrics = String.format(
                "Client tick %.2f ms | Server tick %.2f ms | Entities %d/%d/%d | Chunks %d/%d",
                clientTickMs,
                serverTickMs,
                snapshot.entityCounts().totalEntities(),
                snapshot.entityCounts().livingEntities(),
                snapshot.entityCounts().blockEntities(),
                snapshot.chunkCounts().loadedChunks(),
                snapshot.chunkCounts().renderedChunks()
        );
        ctx.drawText(textRenderer, textRenderer.trimToWidth(headerMetrics, w - 240), PADDING, 18, TEXT_DIM, false);
        if (!snapshot.lastExportStatus().isBlank()) {
            ctx.drawText(textRenderer, textRenderer.trimToWidth(snapshot.lastExportStatus(), 220), w - 226, 18, TEXT_DIM, false);
        }

        int tabY = getTabY();
        int tabW = Math.max(66, Math.min(84, (w - (PADDING * 2) - ((TAB_NAMES.length - 1) * 2)) / TAB_NAMES.length));
        for (int i = 0; i < TAB_NAMES.length; i++) {
            int tx = PADDING + i * (tabW + 2);
            ctx.fill(tx, tabY, tx + tabW, tabY + TAB_HEIGHT, i == activeTab ? TAB_ACTIVE : TAB_INACTIVE);
            if (i == activeTab) ctx.fill(tx, tabY, tx + tabW, tabY + 2, ACCENT_GREEN);
            ctx.fill(tx, tabY + TAB_HEIGHT - 1, tx + tabW, tabY + TAB_HEIGHT, BORDER_COLOR);
            int textX = tx + (tabW - textRenderer.getWidth(TAB_NAMES[i])) / 2;
            ctx.drawText(textRenderer, TAB_NAMES[i], textX, tabY + 8, i == activeTab ? TEXT_PRIMARY : TEXT_DIM, false);
        }

        int contentY = getContentY();
        int contentH = h - contentY - PADDING;
        ctx.fill(0, contentY, w, h, PANEL_COLOR);
        ctx.fill(0, contentY, w, contentY + 1, BORDER_COLOR);

        TAB_RENDERERS[Math.max(0, Math.min(TAB_RENDERERS.length - 1, activeTab))]
                .render(ctx, this, 0, contentY, w, contentH, logicalMouseX, logicalMouseY);

        ModalDialogRenderer.render(this, ctx, w, h, logicalMouseX, logicalMouseY);
        renderTooltipOverlay(ctx, logicalMouseX, logicalMouseY);
        ctx.getMatrices().popMatrix();
        super.render(ctx, mouseX, mouseY, delta);
    }

    private void updateUiScale() {
        float widthScale = this.width / 1180.0f;
        float heightScale = this.height / 760.0f;
        uiScale = Math.min(1.0f, Math.min(widthScale, heightScale));
        if (uiScale <= 0.0f) {
            uiScale = 1.0f;
        }
        layoutWidth = Math.max(1, Math.round(this.width / uiScale));
        layoutHeight = Math.max(1, Math.round(this.height / uiScale));
        uiOffsetX = (this.width - (layoutWidth * uiScale)) / 2.0f;
        uiOffsetY = (this.height - (layoutHeight * uiScale)) / 2.0f;
    }

    private int getScreenWidth() {
        return layoutWidth > 0 ? layoutWidth : this.width;
    }

    private int getScreenHeight() {
        return layoutHeight > 0 ? layoutHeight : this.height;
    }

    private int toLogicalX(double mouseX) {
        return Math.round((float) ((mouseX - uiOffsetX) / uiScale));
    }

    private int toLogicalY(double mouseY) {
        return Math.round((float) ((mouseY - uiOffsetY) / uiScale));
    }

    private int getTabY() {
        return 34;
    }

    private int getContentY() {
        return getTabY() + TAB_HEIGHT + 1;
    }

    void drawTopChip(DrawContext ctx, int x, int y, int width, int height, boolean hovered) {
        ctx.fill(x, y, x + width, y + height, hovered ? 0x66404040 : 0x3A202020);
        ctx.fill(x, y, x + width, y + 1, PANEL_OUTLINE);
        ctx.fill(x, y + height - 1, x + width, y + height, PANEL_OUTLINE);
    }

    private void drawInsetPanel(DrawContext ctx, int x, int y, int width, int height) {
        ctx.fill(x, y, x + width, y + height, PANEL_SOFT);
        ctx.fill(x, y, x + width, y + 1, PANEL_OUTLINE);
        ctx.fill(x, y + height - 1, x + width, y + height, PANEL_OUTLINE);
        ctx.fill(x, y, x + 1, y + height, PANEL_OUTLINE);
        ctx.fill(x + width - 1, y, x + width, y + height, PANEL_OUTLINE);
    }

    int renderSectionHeader(DrawContext ctx, int x, int y, String title, String subtitle) {
        ctx.drawText(textRenderer, title, x, y, TEXT_PRIMARY, false);
        if (subtitle != null && !subtitle.isBlank()) {
            ctx.drawText(textRenderer, textRenderer.trimToWidth(subtitle, Math.max(120, getScreenWidth() - (x * 2))), x, y + 12, TEXT_DIM, false);
            return y + 28;
        }
        return y + 16;
    }

    private void renderModeButton(DrawContext ctx, int mouseX, int mouseY) {
        int x = PADDING + 106;
        int y = 3;
        int w = 132;
        int h = 14;
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        drawTopChip(ctx, x, y, w, h, hovered);
        ctx.drawText(textRenderer, "Mode: " + formatMode(snapshot.mode()), x + 8, y + 3, hovered ? TEXT_PRIMARY : TEXT_DIM, false);
    }

    private void renderHudToggle(DrawContext ctx, int mouseX, int mouseY) {
        int checkX = getScreenWidth() - 254;
        int checkY = 3;
        int chipW = 96;
        int chipH = 14;
        boolean hovered = mouseX >= checkX && mouseX < checkX + chipW && mouseY >= checkY && mouseY < checkY + chipH;
        drawTopChip(ctx, checkX, checkY, chipW, chipH, hovered);
        ctx.fill(checkX + 6, checkY + 3, checkX + 16, checkY + 13, hovered ? 0xFF444444 : 0xFF2A2A2A);
        ctx.fill(checkX + 7, checkY + 4, checkX + 15, checkY + 12, 0xFF1A1A1A);
        if (ConfigManager.isHudEnabled()) ctx.fill(checkX + 8, checkY + 5, checkX + 14, checkY + 11, ACCENT_GREEN);
        ctx.drawText(textRenderer, "HUD overlay", checkX + 20, checkY + 3, hovered ? TEXT_PRIMARY : TEXT_DIM, false);
    }

    private void renderExportButton(DrawContext ctx, int mouseX, int mouseY) {
        int x = getScreenWidth() - 118;
        int y = 3;
        int w = 110;
        int h = 14;
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        drawTopChip(ctx, x, y, w, h, hovered);
        ctx.drawText(textRenderer, "Export Session", x + 10, y + 3, hovered ? TEXT_PRIMARY : TEXT_DIM, false);
    }

    private void renderGlobalSearchBox(DrawContext ctx, int mouseX, int mouseY, int screenWidth) {
        int width = 176;
        int height = 14;
        int x = screenWidth - 438;
        int y = 3;
        renderSearchBox(ctx, x, y, width, height, "Search all tabs", globalSearch, globalSearchFocused);
        if (!globalSearch.isBlank()) {
            ctx.drawText(textRenderer, textRenderer.trimToWidth("All tabs", 48), x + width + 6, y + 3, TEXT_DIM, false);
        }
        addTooltip(x, y, width, height, "Universal search travels across tabs. It combines with local tab filters instead of replacing them.");
    }

    private void renderTasks(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        TasksTabRenderer.render(this, ctx, x, y, w, h, mouseX, mouseY);
    }

    private void renderGpu(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        GpuTabRenderer.render(this, ctx, x, y, w, h, mouseX, mouseY);
    }

    private void renderRender(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        RenderTabRenderer.render(this, ctx, x, y, w, h, mouseX, mouseY);
    }

    private void renderStartup(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        StartupTabRenderer.render(this, ctx, x, y, w, h, mouseX, mouseY);
    }

    private void renderMemory(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        MemoryTabRenderer.render(this, ctx, x, y, w, h, mouseX, mouseY);
    }

    void renderCpuDetailPanel(DrawContext ctx, int x, int y, int width, int height, String modId, CpuSamplingProfiler.Snapshot rawCpuSnapshot, CpuSamplingProfiler.Snapshot effectiveCpuSnapshot, CpuSamplingProfiler.Snapshot displayCpuSnapshot, long redistributedSamples, CpuSamplingProfiler.DetailSnapshot detail, ModTimingSnapshot invokeSnapshot, long totalCpuMetric, boolean effectiveView) {
        drawInsetPanel(ctx, x, y, width, height);
        if (modId == null || displayCpuSnapshot == null) {
            ctx.drawText(textRenderer, "Select a row to inspect sampled CPU causes.", x + 8, y + 8, TEXT_DIM, false);
            return;
        }
        ctx.enableScissor(x, y, x + width, y + height);
        drawTopChip(ctx, x + width - 96, y + 6, 88, 16, activeDrilldownTable == TableId.TASKS);
        ctx.drawText(textRenderer, "Why this row", x + width - 88, y + 10, activeDrilldownTable == TableId.TASKS ? TEXT_PRIMARY : TEXT_DIM, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth(getDisplayName(modId), width - 112), x + 8, y + 8, TEXT_PRIMARY, false);
        long rawSamples = rawCpuSnapshot == null ? 0L : rawCpuSnapshot.totalSamples();
        long effectiveSamples = effectiveCpuSnapshot == null ? rawSamples : effectiveCpuSnapshot.totalSamples();
        double pct = cpuMetricValue(displayCpuSnapshot) * 100.0 / Math.max(1L, totalCpuMetric);
        int rowY = renderWrappedText(ctx, x + 8, y + 20, width - 16, String.format(Locale.ROOT, "%s view | %.1f%% CPU | %s threads | %s shown samples | %s invokes", effectiveView ? "Effective" : "Raw", pct, detail == null ? 0 : detail.sampledThreadCount(), formatCount(displayCpuSnapshot.totalSamples()), formatCount(invokeSnapshot == null ? 0 : invokeSnapshot.calls())), TEXT_DIM);
        rowY = renderWrappedText(ctx, x + 8, rowY, width - 16, "Raw owned samples: " + formatCount(rawSamples) + " | Effective samples: " + formatCount(effectiveSamples), TEXT_DIM);
        if (redistributedSamples > 0) {
            rowY = renderWrappedText(ctx, x + 8, rowY, width - 16, "Redistributed shared/framework samples: " + formatCount(redistributedSamples), TEXT_DIM);
        }
        rowY = renderWrappedText(ctx, x + 8, rowY, width - 16, effectiveView ? "Attribution: sampled stack ownership with shared/framework time proportionally folded into concrete mods [measured/inferred]" : "Attribution: sampled stack ownership without redistribution. Shared/framework rows remain separate [measured/inferred]", TEXT_DIM) + 6;
        String attributionHint = cpuAttributionHint(modId, detail, rawSamples, displayCpuSnapshot.totalSamples(), redistributedSamples);
        if (attributionHint != null) {
            rowY = renderWrappedText(ctx, x + 8, rowY, width - 16, attributionHint, ACCENT_YELLOW) + 4;
        }
        rowY = renderReasonSection(ctx, x + 8, rowY, width - 16, "Top threads [sampled]", effectiveThreadBreakdown(modId, detail));
        if ("shared/render".equals(modId)) {
            rowY = renderStringListSection(ctx, x + 8, rowY + 6, width - 16, "Shared bucket sources", buildGpuPhaseBreakdownLines(modId));
            renderReasonSection(ctx, x + 8, rowY + 6, width - 16, "Top sampled frames [sampled]", detail == null ? Map.of() : detail.topFrames());
        } else {
            rowY = renderReasonSection(ctx, x + 8, rowY + 6, width - 16, isSharedAttributionBucket(modId) ? "Shared bucket sources" : "Top sampled frames [sampled]", isSharedAttributionBucket(modId) ? buildCpuBucketBreakdown(modId, detail) : (detail == null ? Map.of() : detail.topFrames()));
            if (isSharedAttributionBucket(modId)) {
                renderReasonSection(ctx, x + 8, rowY + 6, width - 16, "Top sampled frames [sampled]", detail == null ? Map.of() : detail.topFrames());
            }
        }
        ctx.disableScissor();
    }

    void renderThreadDetailPanel(DrawContext ctx, int x, int y, int width, int height, SystemMetricsProfiler.ThreadDrilldown thread) {
        drawInsetPanel(ctx, x, y, width, height);
        if (thread == null) {
            ctx.drawText(textRenderer, "Select a thread to inspect CPU time, allocation rate, and sampled ownership.", x + 8, y + 8, TEXT_DIM, false);
            return;
        }
        ctx.enableScissor(x, y, x + width, y + height);
        ctx.drawText(textRenderer, textRenderer.trimToWidth(cleanProfilerLabel(thread.threadName()), width - 16), x + 8, y + 8, TEXT_PRIMARY, false);
        int rowY = renderWrappedText(ctx, x + 8, y + 22, width - 16,
                String.format(Locale.ROOT, "tid %d | %.1f%% CPU | %s alloc | %s | blocked %d ms | waited %d ms",
                        thread.threadId(),
                        thread.cpuLoadPercent(),
                        formatBytesPerSecond(thread.allocationRateBytesPerSecond()),
                        blankToUnknown(thread.state()),
                        thread.blockedTimeDeltaMs(),
                        thread.waitedTimeDeltaMs()),
                TEXT_DIM);
        rowY = renderWrappedText(ctx, x + 8, rowY, width - 16,
                "Owner: " + getDisplayName(thread.ownerMod()) + " | Confidence: " + blankToUnknown(thread.confidence()),
                TEXT_DIM);
        rowY = renderWrappedText(ctx, x + 8, rowY, width - 16,
                "Role: " + blankToUnknown(thread.threadRole()) + " | Source: " + blankToUnknown(thread.roleSource()),
                TEXT_DIM);
        rowY = renderWrappedText(ctx, x + 8, rowY, width - 16, "Reason frame: " + cleanProfilerLabel(thread.reasonFrame()), TEXT_DIM) + 6;
        rowY = renderStringListSection(ctx, x + 8, rowY, width - 16, "Top sampled frames", thread.topFrames()) + 6;
        renderStringListSection(ctx, x + 8, rowY, width - 16, "Owner candidates", thread.ownerCandidates());
        ctx.disableScissor();
    }

    void renderGpuDetailPanel(DrawContext ctx, int x, int y, int width, int height, String modId, long rawGpuNanos, long displayGpuNanos, long rawRenderSamples, long displayRenderSamples, long redistributedGpuNanos, long redistributedRenderSamples, CpuSamplingProfiler.DetailSnapshot detail, long totalRenderSamples, long totalGpuNanos, boolean effectiveView) {
        drawInsetPanel(ctx, x, y, width, height);
        if (modId == null || (displayGpuNanos <= 0L && displayRenderSamples <= 0L)) {
            ctx.drawText(textRenderer, "Select a row to inspect estimated GPU work.", x + 8, y + 8, TEXT_DIM, false);
            return;
        }
        double pct = displayGpuNanos * 100.0 / Math.max(1L, totalGpuNanos);
        double gpuMs = displayGpuNanos / 1_000_000.0;
        ctx.enableScissor(x, y, x + width, y + height);
        drawTopChip(ctx, x + width - 96, y + 6, 88, 16, activeDrilldownTable == TableId.GPU);
        ctx.drawText(textRenderer, "Why this row", x + width - 88, y + 10, activeDrilldownTable == TableId.GPU ? TEXT_PRIMARY : TEXT_DIM, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth(getDisplayName(modId), width - 112), x + 8, y + 8, TEXT_PRIMARY, false);
        int rowY = renderWrappedText(ctx, x + 8, y + 20, width - 16, String.format(Locale.ROOT, "%s view | %.1f%% GPU | %s threads | %.2f ms shown GPU | %s shown render samples", effectiveView ? "Effective" : "Raw", pct, detail == null ? 0 : detail.sampledThreadCount(), gpuMs, formatCount(displayRenderSamples)), TEXT_DIM);
        rowY = renderWrappedText(ctx, x + 8, rowY, width - 16, String.format(Locale.ROOT, "Raw tagged GPU: %.2f ms | Raw render samples: %s", rawGpuNanos / 1_000_000.0, formatCount(rawRenderSamples)), TEXT_DIM);
        rowY = renderWrappedText(ctx, x + 8, rowY, width - 16, "Owner source: " + describeGpuOwnerSource(modId), TEXT_DIM);
        if (redistributedGpuNanos > 0 || redistributedRenderSamples > 0) {
            rowY = renderWrappedText(ctx, x + 8, rowY, width - 16, String.format(Locale.ROOT, "Redistributed shared GPU: %.2f ms | Redistributed render samples: %s", redistributedGpuNanos / 1_000_000.0, formatCount(redistributedRenderSamples)), TEXT_DIM);
        }
        rowY = renderWrappedText(ctx, x + 8, rowY, width - 16, effectiveView ? "Attribution: tagged render-phase ownership plus proportional redistribution of shared render work [estimated]" : "Attribution: tagged render-phase ownership without redistribution. Shared render rows remain separate [estimated]", TEXT_DIM) + 6;
        rowY = renderReasonSection(ctx, x + 8, rowY, width - 16, "Render threads [sampled]", effectiveThreadBreakdown(modId, detail));
        if (isSharedAttributionBucket(modId)) {
            rowY = renderReasonSection(ctx, x + 8, rowY + 6, width - 16, "Likely owners during shared/render [sampled]", buildSharedRenderLikelyOwners());
            rowY = renderStringListSection(ctx, x + 8, rowY + 6, width - 16, "Top shared owner phases [tagged]", buildGpuPhaseBreakdownLines(modId));
            rowY = renderReasonSection(ctx, x + 8, rowY + 6, width - 16, "Likely render frames during shared/render [sampled]", buildSharedRenderLikelyFrames());
            renderReasonSection(ctx, x + 8, rowY + 6, width - 16, "Top sampled render frames [sampled]", detail == null ? Map.of() : detail.topFrames());
        } else {
            rowY = renderStringListSection(ctx, x + 8, rowY + 6, width - 16, "Top owner phases [tagged]", buildGpuPhaseBreakdownLines(modId));
            renderReasonSection(ctx, x + 8, rowY + 6, width - 16, "Top sampled render frames [sampled]", detail == null ? Map.of() : detail.topFrames());
        }
        ctx.disableScissor();
    }

    void renderMemoryDetailPanel(DrawContext ctx, int x, int y, int width, int height, String modId, long rawBytes, long effectiveBytes, long displayBytes, Map<String, Long> topClasses, long redistributedBytes, long totalAttributedBytes, boolean effectiveView) {
        drawInsetPanel(ctx, x, y, width, height);
        if (modId == null) {
            ctx.drawText(textRenderer, "Select a row to inspect top live classes.", x + 8, y + 8, TEXT_DIM, false);
            return;
        }
        drawTopChip(ctx, x + width - 96, y + 6, 88, 16, activeDrilldownTable == TableId.MEMORY);
        ctx.drawText(textRenderer, "Why this row", x + width - 88, y + 10, activeDrilldownTable == TableId.MEMORY ? TEXT_PRIMARY : TEXT_DIM, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth(getDisplayName(modId), width - 112), x + 8, y + 8, TEXT_PRIMARY, false);
        int rowY = y + 20;
        double pct = displayBytes * 100.0 / Math.max(1L, totalAttributedBytes);
        rowY = renderWrappedText(ctx, x + 8, rowY, width - 16, String.format(Locale.ROOT, "%s view | %.1f%% heap | %.1f MB shown", effectiveView ? "Effective" : "Raw", pct, displayBytes / (1024.0 * 1024.0)), TEXT_DIM);
        rowY = renderWrappedText(ctx, x + 8, rowY, width - 16, String.format(Locale.ROOT, "Raw owned memory: %.1f MB | Effective memory: %.1f MB", rawBytes / (1024.0 * 1024.0), effectiveBytes / (1024.0 * 1024.0)), TEXT_DIM);
        if (redistributedBytes > 0) {
            rowY = renderWrappedText(ctx, x + 8, rowY, width - 16, "Redistributed shared/runtime memory: " + String.format(Locale.ROOT, "%.1f MB", redistributedBytes / (1024.0 * 1024.0)), TEXT_DIM);
        }
        ctx.drawText(textRenderer, effectiveView ? "Attribution: live class ownership with shared/runtime buckets proportionally folded into concrete mods [measured/inferred]" : "Attribution: live class ownership without redistribution. Shared/runtime rows remain separate [measured/inferred]", x + 8, rowY, TEXT_DIM, false);
        renderReasonSection(ctx, x + 8, rowY + 18, width - 16, "Top classes", topClasses);
    }

    private int renderThreadSnapshotSection(DrawContext ctx, int x, int y, int width, String title, Map<String, ThreadLoadProfiler.ThreadSnapshot> data) {
        ctx.drawText(textRenderer, title, x, y, TEXT_DIM, false);
        int rowY = y + 12;
        List<Map.Entry<String, ThreadLoadProfiler.ThreadSnapshot>> filtered = data == null ? List.of() : data.entrySet().stream()
                .filter(entry -> matchesGlobalSearch(entry.getKey().toLowerCase(Locale.ROOT)))
                .toList();
        if (filtered.isEmpty()) {
            ctx.drawText(textRenderer, globalSearch.isBlank() ? "No thread diagnostics captured in the current window." : "No thread rows match the universal search.", x + 6, rowY, TEXT_DIM, false);
            return rowY + 12;
        }
        int shown = 0;
        for (Map.Entry<String, ThreadLoadProfiler.ThreadSnapshot> entry : filtered) {
            ThreadLoadProfiler.ThreadSnapshot details = entry.getValue();
            String summary = entry.getKey() + " | " + String.format(Locale.ROOT, "%.1f%% %s", details.loadPercent(), blankToUnknown(details.state()));
            rowY = renderWrappedText(ctx, x + 6, rowY, width - 12, summary, getHeatColor(details.loadPercent()));
            if (details.blockedCountDelta() > 0 || details.waitedCountDelta() > 0 || details.lockName() != null || details.lockOwnerName() != null) {
                String waitLine = "blocked " + details.blockedCountDelta()
                        + " / " + details.blockedTimeDeltaMs() + "ms | waited "
                        + details.waitedCountDelta() + " / " + details.waitedTimeDeltaMs()
                        + "ms | lock " + describeLock(details);
                rowY = renderWrappedText(ctx, x + 12, rowY, width - 18, waitLine, TEXT_DIM);
            }
            shown++;
            if (shown >= 4) {
                break;
            }
        }
        return rowY;
    }

    private int renderReasonSection(DrawContext ctx, int x, int y, int width, String title, Map<String, ? extends Number> data) {
        ctx.drawText(textRenderer, title, x, y, TEXT_DIM, false);
        int rowY = y + 12;
        java.util.List<Map.Entry<String, ? extends Number>> filtered = new ArrayList<>();
        if (data != null) {
            for (Map.Entry<String, ? extends Number> entry : data.entrySet()) {
                if (matchesGlobalSearch(entry.getKey().toLowerCase(Locale.ROOT))) {
                    filtered.add(entry);
                }
            }
        }
        if (filtered.isEmpty()) {
            ctx.drawText(textRenderer, globalSearch.isBlank() ? "No detail captured in the current window." : "No detail matches the universal search.", x + 6, rowY, TEXT_DIM, false);
            return rowY + 12;
        }
        int shown = 0;
        for (Map.Entry<String, ? extends Number> entry : filtered) {
            String label = textRenderer.trimToWidth(entry.getKey(), Math.max(80, width - 50));
            ctx.drawText(textRenderer, label, x + 6, rowY, TEXT_PRIMARY, false);
            String value = formatDetailValue(entry.getValue());
            ctx.drawText(textRenderer, value, x + width - textRenderer.getWidth(value), rowY, TEXT_DIM, false);
            rowY += 12;
            shown++;
            if (shown >= 5) {
                break;
            }
        }
        return rowY;
    }

    int renderStringListSection(DrawContext ctx, int x, int y, int width, String title, java.util.List<String> lines) {
        ctx.drawText(textRenderer, title, x, y, TEXT_DIM, false);
        int rowY = y + 12;
        java.util.List<String> filtered = lines == null ? List.of() : lines.stream()
                .filter(line -> matchesGlobalSearch(line.toLowerCase(Locale.ROOT)))
                .toList();
        if (filtered.isEmpty()) {
            ctx.drawText(textRenderer, globalSearch.isBlank() ? "No detail captured in the current window." : "No detail matches the universal search.", x + 6, rowY, TEXT_DIM, false);
            return rowY + 12;
        }
        int shown = 0;
        for (String line : filtered) {
            rowY = renderWrappedText(ctx, x + 6, rowY, Math.max(80, width - 12), line, TEXT_PRIMARY);
            shown++;
            if (shown >= 4) {
                break;
            }
        }
        return rowY;
    }

    int renderWrappedText(DrawContext ctx, int x, int y, int width, String text, int color) {
        if (text == null || text.isBlank()) {
            return y;
        }
        int wrappedWidth = Math.max(40, width);
        ctx.drawWrappedText(textRenderer, Text.literal(text), x, y, wrappedWidth, color, false);
        return y + measureWrappedHeight(wrappedWidth, text);
    }

    private int measureWrappedHeight(int width, String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int wrappedWidth = Math.max(40, width);
        int lineCount = Math.max(1, textRenderer.wrapLines(Text.literal(text), wrappedWidth).size());
        return lineCount * 12;
    }

    String describeLock(ThreadLoadProfiler.ThreadSnapshot detail) {
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

    private Map<String, Number> effectiveThreadBreakdown(String modId, CpuSamplingProfiler.DetailSnapshot detail) {
        if ("shared/jvm".equals(modId) || "shared/framework".equals(modId)) {
            Map<String, Number> result = new LinkedHashMap<>();
            int shown = 0;
            for (Map.Entry<String, Double> entry : snapshot.systemMetrics().threadLoadPercentByName().entrySet()) {
                result.put(entry.getKey(), entry.getValue());
                shown++;
                if (shown >= 5) {
                    break;
                }
            }
            return result;
        }
        return detail == null ? Map.of() : new LinkedHashMap<>(detail.topThreads());
    }

    private Map<String, Number> buildCpuBucketBreakdown(String modId, CpuSamplingProfiler.DetailSnapshot detail) {
        if (detail != null && detail.topFrames() != null && !detail.topFrames().isEmpty()) {
            return new LinkedHashMap<>(detail.topFrames());
        }
        if ("shared/gpu-stall".equals(modId)) {
            return Map.of("Render-thread GPU driver wait", 1);
        }
        if ("shared/jvm".equals(modId) || "shared/framework".equals(modId)) {
            Map<String, Number> result = new LinkedHashMap<>();
            snapshot.systemMetrics().threadLoadPercentByName().entrySet().stream().limit(5).forEach(entry -> result.put(entry.getKey(), entry.getValue()));
            return result;
        }
        if ("shared/render".equals(modId)) {
            return Map.of();
        }
        return Map.of();
    }

    private String cpuAttributionHint(String modId, CpuSamplingProfiler.DetailSnapshot detail, long rawSamples, long shownSamples, long redistributedSamples) {
        if ("shared/gpu-stall".equals(modId)) {
            return "GPU-stall bucket: these samples were dominated by LWJGL/Blaze3D/JNI driver frames while the render thread used little actual CPU time, so they are kept separate instead of inflating whichever mod hook happened to be on the stack.";
        }
        if (modId == null || detail == null || detail.topFrames() == null || detail.topFrames().isEmpty()) {
            return null;
        }
        StringJoiner hints = new StringJoiner(" ");
        if (isLowConfidenceCpuAttribution(detail, rawSamples, shownSamples, redistributedSamples)) {
            hints.add("Low-confidence attribution: this row is built from a small raw sample set with heavy shared/framework redistribution and mostly runtime/native frames, so treat it as a clue rather than direct proof that this mod is spending this much CPU.");
        }
        if (isRenderSubmissionHeavy(detail)) {
            hints.add("Render-thread submission hint: most sampled frames here are OpenGL/JNI driver calls, so this row likely reflects a mod-associated render hook or submission path rather than direct Java CPU loops.");
        }
        String combined = hints.toString();
        return combined.isBlank() ? null : combined;
    }

    private boolean isLowConfidenceCpuAttribution(CpuSamplingProfiler.DetailSnapshot detail, long rawSamples, long shownSamples, long redistributedSamples) {
        if (detail == null || detail.topFrames() == null || detail.topFrames().isEmpty()) {
            return false;
        }
        boolean smallRawSampleSet = rawSamples > 0L && rawSamples < 12L;
        boolean redistributedHeavy = redistributedSamples > 0L && redistributedSamples * 10L >= Math.max(1L, shownSamples) * 4L;
        long opaqueFrames = detail.topFrames().entrySet().stream()
                .filter(entry -> isOpaqueCpuAttributionFrame(entry.getKey()))
                .mapToLong(Map.Entry::getValue)
                .sum();
        long totalFrames = detail.topFrames().values().stream().mapToLong(Long::longValue).sum();
        boolean opaqueFrameHeavy = totalFrames > 0L && opaqueFrames * 10L >= totalFrames * 6L;
        return smallRawSampleSet && redistributedHeavy && opaqueFrameHeavy;
    }

    private boolean isRenderSubmissionHeavy(CpuSamplingProfiler.DetailSnapshot detail) {
        if (detail == null || detail.topFrames() == null || detail.topFrames().isEmpty()) {
            return false;
        }
        boolean renderThreadDominant = detail.topThreads() != null && detail.topThreads().keySet().stream()
                .findFirst()
                .map(name -> name.toLowerCase(Locale.ROOT).contains("render"))
                .orElse(false);
        if (!renderThreadDominant) {
            return false;
        }
        long opaqueFrames = detail.topFrames().entrySet().stream()
                .filter(entry -> isOpaqueRenderSubmissionFrame(entry.getKey()))
                .mapToLong(Map.Entry::getValue)
                .sum();
        long totalFrames = detail.topFrames().values().stream().mapToLong(Long::longValue).sum();
        return totalFrames > 0L && opaqueFrames * 2L >= totalFrames;
    }

    private boolean isOpaqueCpuAttributionFrame(String frame) {
        if (frame == null) {
            return false;
        }
        String lower = frame.toLowerCase(Locale.ROOT);
        return isOpaqueRenderSubmissionFrame(frame)
                || lower.startsWith("native#")
                || lower.contains("operatingsystemimpl#")
                || lower.contains("processcpuload")
                || lower.contains("managementfactory")
                || lower.contains("spark")
                || lower.contains("oshi")
                || lower.contains("jna")
                || lower.contains("hwinfo")
                || lower.contains("telemetry")
                || lower.contains("perf")
                || lower.contains("sensor");
    }

    private boolean isOpaqueRenderSubmissionFrame(String frame) {
        if (frame == null) {
            return false;
        }
        String lower = frame.toLowerCase(Locale.ROOT);
        return lower.startsWith("gl")
                || lower.startsWith("jni#")
                || lower.contains("org.lwjgl")
                || lower.contains("framebuffer")
                || lower.contains("blaze3d")
                || lower.contains("fencesync");
    }

    private java.util.List<String> buildGpuPhaseBreakdownLines(String modId) {
        return AttributionModelBuilder.buildGpuPhaseBreakdownLines(snapshot.renderPhases(), modId, this::getDisplayName);
    }

    private Map<String, Long> buildSharedRenderLikelyOwners() {
        return AttributionModelBuilder.buildSharedRenderLikelyOwners(snapshot.renderPhases(), this::getDisplayName);
    }

    private Map<String, Long> buildSharedRenderLikelyFrames() {
        return AttributionModelBuilder.buildSharedRenderLikelyFrames(snapshot.renderPhases());
    }

    private String describeGpuOwnerSource(String modId) {
        return AttributionModelBuilder.describeGpuOwnerSource(snapshot.renderPhases(), modId);
    }

    private String formatDetailValue(Number value) {
        if (value == null) {
            return "0";
        }
        if (value instanceof Double || value instanceof Float) {
            return String.format(Locale.ROOT, "%.1f%%", value.doubleValue());
        }
        return formatCount(value.longValue());
    }

    int getFullPageScrollTop(int y) {
        return y + PADDING - scrollOffset;
    }

    private int getFullPageContentHeight(int h) {
        return Math.max(1, h - PADDING);
    }

    void beginFullPageScissor(DrawContext ctx, int x, int y, int w, int h) {
        ctx.enableScissor(x, y, x + w, y + h);
    }

    private void endFullPageScissor(DrawContext ctx) {
        ctx.disableScissor();
    }

    private void renderNetwork(DrawContext ctx, int x, int y, int w, int h) {
        NetworkTabRenderer.render(this, ctx, x, y, w, h);
    }


    private void renderDisk(DrawContext ctx, int x, int y, int w, int h) {
        DiskTabRenderer.render(this, ctx, x, y, w, h);
    }
    private void renderThreads(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        ThreadsTabRenderer.render(this, ctx, x, y, w, h, mouseX, mouseY);
    }

    void renderThreadToolbar(DrawContext ctx, int x, int y) {
        renderThreadToolbarChip(ctx, x, y, 70, "CPU", threadSort == ThreadSort.CPU);
        renderThreadToolbarChip(ctx, x + 74, y, 82, "Alloc", threadSort == ThreadSort.ALLOC);
        renderThreadToolbarChip(ctx, x + 160, y, 86, "Blocked", threadSort == ThreadSort.BLOCKED);
        renderThreadToolbarChip(ctx, x + 250, y, 82, "Waited", threadSort == ThreadSort.WAITED);
        renderThreadToolbarChip(ctx, x + 336, y, 76, "Name", threadSort == ThreadSort.NAME);
        renderThreadToolbarChip(ctx, x + 418, y, 86, threadFreeze ? "Unfreeze" : "Freeze", threadFreeze);
    }

    private void renderThreadToolbarChip(DrawContext ctx, int x, int y, int width, String label, boolean active) {
        drawTopChip(ctx, x, y, width, 16, active);
        ctx.drawText(textRenderer, label, x + 10, y + 4, active ? TEXT_PRIMARY : TEXT_DIM, false);
    }

    private void renderSystem(DrawContext ctx, int x, int y, int w, int h) {
        SystemTabRenderer.render(this, ctx, x, y, w, h);
    }

    private void renderBlockEntities(DrawContext ctx, int x, int y, int w, int h) {
        int left = x + PADDING;
        int top = getFullPageScrollTop(y);
        beginFullPageScissor(ctx, x, y, w, h);
        ctx.drawText(textRenderer, "Measured block-entity hotspots, chunk density, and findings focused on ticking/storage pressure.", left, top, TEXT_DIM, false);
        top += 18;
        top = renderBlockEntityHotspotSection(ctx, left, top, w - 24, ProfilerManager.getInstance().getLatestBlockEntityHotspots(), "Top Block Entity Hotspots") + 10;
        java.util.List<ProfilerManager.HotChunkSnapshot> hotChunks = ProfilerManager.getInstance().getLatestHotChunks();
        ctx.drawText(textRenderer, "Block-entity heavy chunks", left, top, TEXT_PRIMARY, false);
        top += 14;
        if (hotChunks.isEmpty()) {
            ctx.drawText(textRenderer, "No hot chunks sampled yet.", left + 6, top, TEXT_DIM, false);
            top += 12;
        } else {
            int shown = 0;
            for (ProfilerManager.HotChunkSnapshot hotChunk : hotChunks) {
                String line = String.format(Locale.ROOT, "%d,%d | %d block entities | top %s | score %.1f", hotChunk.chunkX(), hotChunk.chunkZ(), hotChunk.blockEntityCount(), cleanProfilerLabel(hotChunk.topBlockEntityClass()), hotChunk.activityScore());
                ctx.drawText(textRenderer, textRenderer.trimToWidth(line, w - 24), left + 6, top, hotChunk.blockEntityCount() >= 16 ? ACCENT_YELLOW : TEXT_DIM, false);
                top += 12;
                shown++;
                if (shown >= 6) {
                    break;
                }
            }
        }
        top += 8;
        top += renderRuleFindingsSection(ctx, left, top, w - 24, ProfilerManager.getInstance().getLatestRuleFindings()) + 8;
        if (selectedLagChunk != null) {
            ctx.drawText(textRenderer, "Selected chunk block entities", left, top, TEXT_PRIMARY, false);
            top += 14;
            MinecraftClient client = MinecraftClient.getInstance();
            Map<String, Integer> blockEntityCounts = new HashMap<>();
            if (client.world != null) {
                for (BlockEntity blockEntity : client.world.getBlockEntities()) {
                    ChunkPos chunkPos = new ChunkPos(blockEntity.getPos());
                    if (chunkPos.x == selectedLagChunk.x && chunkPos.z == selectedLagChunk.z) {
                        blockEntityCounts.merge(cleanProfilerLabel(blockEntity.getClass().getSimpleName()), 1, Integer::sum);
                    }
                }
            }
            top = renderCountMap(ctx, left, top, w - 24, "Top block entities in selected chunk [measured counts]", blockEntityCounts) + 6;
        }
        ctx.disableScissor();
    }

    private void renderWorldTab(DrawContext ctx, int x, int y, int w, int h) {
        WorldTabRenderer.render(this, ctx, x, y, w, h);
    }

    void renderLagMap(DrawContext ctx, int x, int y, int width, int height) {
        MinecraftClient client = MinecraftClient.getInstance();
        ctx.drawText(textRenderer, "Lag Map", x, y, TEXT_PRIMARY, false);
        if (client.player == null || client.world == null) {
            ctx.drawText(textRenderer, "World not loaded.", x, y + 14, TEXT_DIM, false);
            return;
        }

        double serverTickMs = TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0;
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Entity entity : client.world.getEntities()) {
            ChunkPos chunkPos = entity.getChunkPos();
            long key = (((long) chunkPos.x) << 32) ^ (chunkPos.z & 0xFFFFFFFFL);
            counts.merge(key, 1, Integer::sum);
        }

        ChunkPos playerChunk = client.player.getChunkPos();
        int radius = 4;
        int cell = Math.max(12, Math.min(20, Math.min(width, height - 18) / ((radius * 2) + 1)));
        int maxCount = Math.max(1, counts.values().stream().mapToInt(Integer::intValue).max().orElse(1));
        int mapTop = y + 14;

        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int chunkX = playerChunk.x + dx;
                int chunkZ = playerChunk.z + dz;
                long key = (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL);
                int count = counts.getOrDefault(key, 0);
                double intensity = count / (double) maxCount;
                int color = serverTickMs > 40.0
                        ? (0x22000000 | ((int) (40 + (215 * intensity)) << 16))
                        : (0x22000000 | ((int) (40 + (120 * intensity)) << 8));
                int px = x + (dx + radius) * cell;
                int py = mapTop + (dz + radius) * cell;
                ctx.fill(px, py, px + cell - 1, py + cell - 1, color);
                if (dx == 0 && dz == 0) {
                    ctx.fill(px + 3, py + 3, px + cell - 4, py + cell - 4, 0x99FFFFFF);
                }
                if (selectedLagChunk != null && selectedLagChunk.x == chunkX && selectedLagChunk.z == chunkZ) {
                    ctx.fill(px, py, px + cell - 1, py + 1, 0xFFFFFFFF);
                    ctx.fill(px, py + cell - 2, px + cell - 1, py + cell - 1, 0xFFFFFFFF);
                    ctx.fill(px, py, px + 1, py + cell - 1, 0xFFFFFFFF);
                    ctx.fill(px + cell - 2, py, px + cell - 1, py + cell - 1, 0xFFFFFFFF);
                }
            }
        }

        String legend = serverTickMs > 40.0
                ? "High server tick: dense chunks highlighted red"
                : "Server tick stable: map shows relative entity density";
        ctx.drawText(textRenderer, legend, x, mapTop + (cell * ((radius * 2) + 1)) + 4, TEXT_DIM, false);
    }

    void renderSensorsPanel(DrawContext ctx, int x, int y, int width, SystemMetricsProfiler.Snapshot system) {
        ctx.fill(x, y, x + width, y + 134, 0x14000000);
        String source = blankToUnknown(system.sensorSource());
        String[] sourceParts = source.split("\\| Tried: ", 2);
        String activeSource = sourceParts[0].trim();
        String attempts = sourceParts.length > 1 ? sourceParts[1].trim() : "provider attempts unavailable";
        String status = blankToUnknown(system.cpuSensorStatus());
        String availability = system.cpuTemperatureC() >= 0 || system.gpuTemperatureC() >= 0 ? "Measured temperatures available" : "Falling back to load-only telemetry";
        ctx.fill(x, y, x + width, y + 16, 0x22000000);
        ctx.drawText(textRenderer, "Sensors & Telemetry Health", x + 6, y + 4, TEXT_PRIMARY, false);
        addTooltip(x + 6, y + 2, 146, 14, "Sensor diagnostics shows provider availability, helper health, fallback path, and the last bridge error.");
        String statusLabel = textRenderer.trimToWidth(status, width - 12);
        ctx.drawText(textRenderer, statusLabel, x + width - 6 - textRenderer.getWidth(statusLabel), y + 4, TEXT_DIM, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth("Provider: " + activeSource, width - 12), x + 6, y + 22, TEXT_DIM, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth("Availability: " + availability, width - 12), x + 6, y + 36, system.cpuTemperatureC() >= 0 || system.gpuTemperatureC() >= 0 ? ACCENT_GREEN : ACCENT_YELLOW, false);
        String tempSummary = "CPU temp " + formatTemperature(system.cpuTemperatureC()) + " | GPU " + TelemetryTextFormatter.formatGpuTemperatureCompact(system) + " | CPU load " + formatPercent(system.cpuCoreLoadPercent()) + " | GPU load " + formatPercent(system.gpuCoreLoadPercent());
        ctx.drawText(textRenderer, textRenderer.trimToWidth(tempSummary, width - 12), x + 6, y + 50, TEXT_DIM, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth("GPU core temp provider: " + blankToUnknown(system.gpuTemperatureProvider()) + " | GPU hot spot provider: " + blankToUnknown(system.gpuHotSpotProvider()), width - 12), x + 6, y + 64, TEXT_DIM, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth("Windows helper: " + blankToUnknown(system.telemetryHelperStatus()) + " | Last sample age: " + formatDuration(system.telemetrySampleAgeMillis()), width - 12), x + 6, y + 78, TEXT_DIM, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth("Counter source: " + blankToUnknown(system.counterSource()) + " | helper cost " + system.telemetryHelperCostMillis() + " ms", width - 12), x + 6, y + 92, TEXT_DIM, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth("Attempts: " + attempts, width - 12), x + 6, y + 106, TEXT_DIM, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth("Reason / error: " + blankToUnknown(system.cpuTemperatureUnavailableReason()) + " | " + blankToUnknown(system.sensorErrorCode()), width - 12), x + 6, y + 120, ACCENT_YELLOW, false);
    }

    String summarizeResourcePackAndTextureState(SystemMetricsProfiler.Snapshot system) {
        List<String> packs = ProfilerManager.getInstance().getEnabledResourcePackNames();
        String packSummary = packs.isEmpty()
                ? "default/unknown packs"
                : textRenderer.trimToWidth(String.join(", ", packs), 220);
        return packSummary + " | uploads " + formatCount(system.textureUploadRate());
    }

    String summarizeAllocationPressure() {
        Map<String, Long> allocationRates = MemoryProfiler.getInstance().getModAllocationRateBytesPerSecond();
        if (allocationRates.isEmpty()) {
            return "warming up";
        }
        return allocationRates.entrySet().stream()
                .limit(2)
                .map(entry -> getDisplayName(entry.getKey()) + " " + formatBytesPerSecond(entry.getValue()))
                .reduce((left, right) -> left + " | " + right)
                .orElse("warming up");
    }

    void renderProfilerSelfCostPanel(DrawContext ctx, int x, int y, int width, SystemMetricsProfiler.Snapshot system) {
        ctx.fill(x, y, x + width, y + 76, 0x14000000);
        ctx.fill(x, y, x + width, y + 16, 0x22000000);
        ctx.drawText(textRenderer, "Profiler Self-Cost", x + 6, y + 4, TEXT_PRIMARY, false);
        addTooltip(x + 6, y + 2, 90, 14, "Shows TaskManager's own visible overhead so the profiler stays accountable.");
        ctx.drawText(textRenderer, textRenderer.trimToWidth(String.format(Locale.ROOT, "Profiler CPU %.1f%% | governor %s | world scan %d ms", system.profilerCpuLoadPercent(), blankToUnknown(system.collectorGovernorMode()), system.worldScanCostMillis()), width - 12), x + 6, y + 22, TEXT_DIM, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth(String.format(Locale.ROOT, "Memory histogram %d ms | telemetry helper %d ms", system.memoryHistogramCostMillis(), system.telemetryHelperCostMillis()), width - 12), x + 6, y + 36, TEXT_DIM, false);
        String protectionLine = "self-protect".equals(system.collectorGovernorMode())
                ? "Self-protection is active: expensive collectors are backing off to keep TaskManager from adding more lag."
                : "Adaptive mode slows expensive collectors during stable periods and bursts into higher detail for alerts, recording, or active inspection.";
        ctx.drawText(textRenderer, textRenderer.trimToWidth(protectionLine, width - 12), x + 6, y + 50, "self-protect".equals(system.collectorGovernorMode()) ? ACCENT_YELLOW : TEXT_DIM, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth("GPU coverage: " + blankToUnknown(system.gpuCoverageSummary()), width - 12), x + 6, y + 62, ACCENT_YELLOW, false);
    }

    List<String> buildThreadDrilldownLines(SystemMetricsProfiler.Snapshot system) {
        if (system.threadDrilldown() == null || system.threadDrilldown().isEmpty()) {
            return List.of("Waiting for live thread CPU, allocation, and stack snapshots.");
        }
        List<String> lines = new ArrayList<>();
        for (SystemMetricsProfiler.ThreadDrilldown thread : system.threadDrilldown()) {
            String topFrames = thread.topFrames() == null || thread.topFrames().isEmpty()
                    ? "no sampled frames yet"
                    : String.join(" > ", thread.topFrames());
            String candidates = thread.ownerCandidates() == null || thread.ownerCandidates().isEmpty()
                    ? "no alternate candidates"
                    : String.join(" ; ", thread.ownerCandidates());
            lines.add(String.format(Locale.ROOT,
                    "%s [tid %d] | %.1f%% CPU | %s alloc | %s | role %s | owner %s | %s | reason %s | top %s | candidates %s",
                    cleanProfilerLabel(thread.threadName()),
                    thread.threadId(),
                    thread.cpuLoadPercent(),
                    formatBytesPerSecond(thread.allocationRateBytesPerSecond()),
                    blankToUnknown(thread.state()),
                    blankToUnknown(thread.threadRole()),
                    getDisplayName(thread.ownerMod()),
                    blankToUnknown(thread.confidence()),
                    cleanProfilerLabel(thread.reasonFrame()),
                    topFrames,
                    candidates));
        }
        return lines;
    }

    List<String> buildShaderCompileLines() {
        ShaderCompilationProfiler.Snapshot snapshot = ShaderCompilationProfiler.getInstance().getSnapshot();
        if (snapshot.durationNanosByLabel().isEmpty()) {
            return List.of("No shader compilation captured in the current window.");
        }
        List<String> lines = new ArrayList<>();
        snapshot.durationNanosByLabel().forEach((label, durationNs) -> lines.add(String.format(
                Locale.ROOT,
                "%s | %.2f ms | %d compiles",
                cleanProfilerLabel(label),
                durationNs / 1_000_000.0,
                snapshot.compileCountByLabel().getOrDefault(label, 0L)
        )));
        ShaderCompilationProfiler.CompileEvent latest = snapshot.recentEvents().isEmpty() ? null : snapshot.recentEvents().getFirst();
        if (latest != null) {
            lines.add(0, String.format(Locale.ROOT, "Latest compile: %s | %.2f ms | age %d ms",
                    cleanProfilerLabel(latest.label()),
                    latest.durationNs() / 1_000_000.0,
                    snapshot.sampleAgeMillis() == Long.MAX_VALUE ? -1L : snapshot.sampleAgeMillis()));
        }
        return lines;
    }

    List<String> buildChunkPipelineDrilldownLines() {
        List<String> lines = new ArrayList<>();
        SystemMetricsProfiler.Snapshot system = snapshot.systemMetrics();
        lines.add(String.format(Locale.ROOT, "Workers [inferred]: gen %d | mesh %d | upload %d | lights %d | texture uploads %s",
                system.chunksGenerating(),
                system.chunksMeshing(),
                system.chunksUploading(),
                system.lightsUpdatePending(),
                formatCount(system.textureUploadRate())));
        ThreadLoadProfiler.getInstance().getLatestRawThreadSnapshots().values().stream()
                .filter(raw -> isChunkPipelineThread(raw.threadName()) || isChunkPipelineThread(raw.canonicalThreadName()))
                .sorted((a, b) -> Double.compare(b.snapshot().loadPercent(), a.snapshot().loadPercent()))
                .limit(4)
                .forEach(raw -> lines.add(String.format(Locale.ROOT, "%s [measured] %.1f%% %s | blocked %d | waited %d",
                        cleanProfilerLabel(raw.threadName()),
                        raw.snapshot().loadPercent(),
                        raw.snapshot().state(),
                        raw.snapshot().blockedCountDelta(),
                        raw.snapshot().waitedCountDelta())));
        snapshot.renderPhases().entrySet().stream()
                .filter(entry -> isChunkPipelinePhase(entry.getKey()))
                .sorted((a, b) -> Long.compare(Math.max(b.getValue().gpuNanos(), b.getValue().cpuNanos()), Math.max(a.getValue().gpuNanos(), a.getValue().cpuNanos())))
                .limit(3)
                .forEach(entry -> lines.add(String.format(Locale.ROOT, "%s [tagged] owner %s | CPU %.2f ms | GPU %.2f ms",
                        cleanProfilerLabel(entry.getKey()),
                        getDisplayName(entry.getValue().ownerMod() == null ? "shared/render" : entry.getValue().ownerMod()),
                        entry.getValue().cpuNanos() / 1_000_000.0,
                        entry.getValue().gpuNanos() / 1_000_000.0)));
        return lines;
    }

    private boolean isChunkPipelineThread(String threadName) {
        if (threadName == null) {
            return false;
        }
        String lower = threadName.toLowerCase(Locale.ROOT);
        return lower.contains("chunk") || lower.contains("mesh") || lower.contains("builder") || lower.contains("upload") || lower.contains("light");
    }

    private boolean isChunkPipelinePhase(String phase) {
        if (phase == null) {
            return false;
        }
        String lower = phase.toLowerCase(Locale.ROOT);
        return lower.contains("chunk") || lower.contains("mesh") || lower.contains("terrain") || lower.contains("section") || lower.contains("upload") || lower.contains("light");
    }


    int renderPacketBreakdownColumn(DrawContext ctx, int x, int y, int width, Map<String, Long> breakdown) {
        List<Map.Entry<String, Long>> filtered = breakdown.entrySet().stream()
                .filter(entry -> matchesGlobalSearch(entry.getKey().toLowerCase(Locale.ROOT)))
                .toList();
        if (filtered.isEmpty()) {
            ctx.drawText(textRenderer, globalSearch.isBlank() ? "No packet attribution yet." : "No packet rows match the universal search.", x, y, TEXT_DIM, false);
            return 12;
        }
        int rowY = y;
        int shown = 0;
        for (Map.Entry<String, Long> entry : filtered) {
            String label = textRenderer.trimToWidth(entry.getKey(), Math.max(60, width - 46));
            ctx.drawText(textRenderer, label, x, rowY, TEXT_DIM, false);
            String value = String.valueOf(entry.getValue());
            ctx.drawText(textRenderer, value, x + width - textRenderer.getWidth(value), rowY, TEXT_PRIMARY, false);
            rowY += 12;
            shown++;
            if (shown >= 6) {
                break;
            }
        }
        return rowY - y;
    }


    int renderLagChunkDetail(DrawContext ctx, int x, int y, int width, int height) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || selectedLagChunk == null) {
            ctx.drawText(textRenderer, "Click a chunk in the world map to inspect its entities and block entities.", x, y, TEXT_DIM, false);
            return y + 12;
        }
        ctx.drawText(textRenderer, "Selected chunk " + selectedLagChunk.x + ", " + selectedLagChunk.z, x, y, TEXT_PRIMARY, false);
        int rowY = y + 14;
        Map<String, Integer> entityCounts = new HashMap<>();
        for (Entity entity : client.world.getEntities()) {
            ChunkPos chunkPos = entity.getChunkPos();
            if (chunkPos.x == selectedLagChunk.x && chunkPos.z == selectedLagChunk.z) {
                entityCounts.merge(cleanEntityName(entity), 1, Integer::sum);
            }
        }
        Map<String, Integer> blockEntityCounts = new HashMap<>();
        for (BlockEntity blockEntity : client.world.getBlockEntities()) {
            ChunkPos chunkPos = new ChunkPos(blockEntity.getPos());
            if (chunkPos.x == selectedLagChunk.x && chunkPos.z == selectedLagChunk.z) {
                blockEntityCounts.merge(cleanProfilerLabel(blockEntity.getClass().getSimpleName()), 1, Integer::sum);
            }
        }
        int totalEntities = entityCounts.values().stream().mapToInt(Integer::intValue).sum();
        int totalBlockEntities = blockEntityCounts.values().stream().mapToInt(Integer::intValue).sum();
        java.util.List<Integer> activityHistory = ProfilerManager.getInstance().getChunkActivityHistory(selectedLagChunk);
        int maxEntitiesOverall = Math.max(1, snapshot.entityCounts().totalEntities());
        rowY = renderWrappedText(ctx, x, rowY, width, String.format(Locale.ROOT, "Measured counts: %d entities | %d block entities | %d activity samples | loaded-world max %d", totalEntities, totalBlockEntities, activityHistory.size(), maxEntitiesOverall), TEXT_DIM);
        String topEntityClass = entityCounts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("none");
        String topBlockEntityClass = blockEntityCounts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("none");
        rowY = renderWrappedText(ctx, x, rowY + 2, width, "Top hot classes: entity " + topEntityClass + " | block entity " + topBlockEntityClass, TEXT_DIM);
        boolean chunkIoHint = ProfilerManager.getInstance().getLatestLockSummaries().stream()
                .map(line -> line.toLowerCase(Locale.ROOT))
                .anyMatch(line -> line.contains("chunk") || line.contains("region") || line.contains("anvil") || line.contains("poi"));
        if (chunkIoHint) {
            rowY = renderWrappedText(ctx, x, rowY + 2, width, "Chunk I/O lock hint active in current window. Cross-check blocked threads below and on the System tab.", ACCENT_YELLOW);
        }
        rowY += renderSimpleHistoryGraph(ctx, x, rowY + 2, width, 64, activityHistory, "Chunk activity over time [measured]", "activity", maxEntitiesOverall) + 8;
        rowY = renderCountMap(ctx, x, rowY, width, "Top entities [measured counts]", entityCounts, false) + 6;
        rowY = renderCountMap(ctx, x, rowY, width, "Top block entities [measured counts]", blockEntityCounts) + 6;
        return rowY;
    }


    int renderCountMap(DrawContext ctx, int x, int y, int width, String title, Map<String, Integer> counts) {
        return renderCountMap(ctx, x, y, width, title, counts, true);
    }

    int renderCountMap(DrawContext ctx, int x, int y, int width, String title, Map<String, Integer> counts, boolean normalizeLabels) {
        ctx.drawText(textRenderer, title, x, y, TEXT_DIM, false);
        int rowY = y + 12;
        List<Map.Entry<String, Integer>> filtered = counts.entrySet().stream()
                .filter(entry -> matchesGlobalSearch((normalizeLabels ? cleanProfilerLabel(entry.getKey()) : entry.getKey()).toLowerCase(Locale.ROOT)))
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .toList();
        if (filtered.isEmpty()) {
            ctx.drawText(textRenderer, globalSearch.isBlank() ? "none" : "no matches", x + 6, rowY, TEXT_DIM, false);
            return rowY + 12;
        }
        int shown = 0;
        for (Map.Entry<String, Integer> entry : filtered) {
            String rawLabel = normalizeLabels ? cleanProfilerLabel(entry.getKey()) : entry.getKey();
            String label = textRenderer.trimToWidth(rawLabel, Math.max(60, width - 36));
            ctx.drawText(textRenderer, label, x + 6, rowY, TEXT_PRIMARY, false);
            String value = String.valueOf(entry.getValue());
            ctx.drawText(textRenderer, value, x + width - textRenderer.getWidth(value), rowY, TEXT_DIM, false);
            rowY += 12;
            shown++;
            if (shown >= 5) {
                break;
            }
        }
        return rowY;
    }


    int renderRuleFindingsSection(DrawContext ctx, int x, int y, int width, java.util.List<ProfilerManager.RuleFinding> findings) {
        ctx.drawText(textRenderer, "Conflict and slowdown findings", x, y, TEXT_PRIMARY, false);
        int rowY = y + 14;
        List<ProfilerManager.RuleFinding> filteredFindings = findings == null ? List.of() : findings.stream()
                .filter(finding -> matchesGlobalSearch((finding.category() + " " + finding.message() + " " + finding.details() + " " + finding.metricSummary()).toLowerCase(Locale.ROOT)))
                .sorted((a, b) -> {
                    int sectionCompare = Integer.compare(findingSectionRank(a), findingSectionRank(b));
                    if (sectionCompare != 0) {
                        return sectionCompare;
                    }
                    return Integer.compare(severitySortRank(b.severity()), severitySortRank(a.severity()));
                })
                .toList();
        if (filteredFindings.isEmpty()) {
            ctx.drawText(textRenderer, globalSearch.isBlank() ? "No active findings in the current window." : "No findings match the universal search.", x + 6, rowY, TEXT_DIM, false);
            return 24;
        }
        if (selectedFindingKey == null || filteredFindings.stream().noneMatch(finding -> findingKey(finding).equals(selectedFindingKey))) {
            selectedFindingKey = findingKey(filteredFindings.getFirst());
        }
        boolean stacked = activeTab == 9 || width < 720;
        int listWidth = stacked ? width : Math.max(180, width / 2);
        int shown = 0;
        String currentSection = null;
        for (ProfilerManager.RuleFinding finding : filteredFindings) {
            String section = findingSectionTitle(finding);
            if (!section.equals(currentSection)) {
                ctx.drawText(textRenderer, section, x + 6, rowY, ACCENT_YELLOW, false);
                rowY += 12;
                currentSection = section;
            }
            int color = switch (finding.severity()) {
                case "critical" -> 0xFFFF4444;
                case "warning" -> ACCENT_YELLOW;
                case "error" -> ACCENT_RED;
                default -> TEXT_DIM;
            };
            boolean selected = findingKey(finding).equals(selectedFindingKey);
            int itemHeight = 24;
            if (selected) {
                ctx.fill(x + 2, rowY - 2, x + listWidth, rowY + itemHeight - 2, 0x18000000);
            }
            findingClickTargets.add(new FindingClickTarget(x + 2, rowY - 2, listWidth - 2, itemHeight, findingKey(finding)));
            String heading = findingListLabel(finding) + " | " + finding.severity().toUpperCase(Locale.ROOT) + " | " + finding.confidence();
            ctx.drawText(textRenderer, textRenderer.trimToWidth(heading, listWidth - 12), x + 6, rowY, color, false);
            rowY += 12;
            ctx.drawText(textRenderer, textRenderer.trimToWidth(finding.message(), listWidth - 18), x + 12, rowY, TEXT_PRIMARY, false);
            rowY += 14;
            shown++;
            if (shown >= 8) {
                break;
            }
        }
        ProfilerManager.RuleFinding selected = filteredFindings.stream().filter(finding -> findingKey(finding).equals(selectedFindingKey)).findFirst().orElse(filteredFindings.getFirst());
        int detailX = stacked ? x : x + listWidth + 8;
        int detailW = stacked ? width : Math.max(140, width - listWidth - 8);
        int detailBoxY = stacked ? rowY : y + 12;
        int detailInnerY = detailBoxY + 22;
        int detailTextHeight = measureWrappedHeight(detailW - 16, selected.message())
                + measureWrappedHeight(detailW - 16, "Why: " + selected.details())
                + measureWrappedHeight(detailW - 16, "Metrics: " + selected.metricSummary())
                + measureWrappedHeight(detailW - 16, "Next step: " + selected.nextStep())
                + 18;
        int detailHeight = Math.max(92, detailTextHeight + 18);
        drawInsetPanel(ctx, detailX, detailBoxY, detailW, stacked ? detailHeight : Math.max(detailHeight, rowY - y + 18));
        ctx.drawText(textRenderer, "Finding drilldown", detailX + 8, detailBoxY + 8, TEXT_PRIMARY, false);
        int detailY = renderWrappedText(ctx, detailX + 8, detailInnerY, detailW - 16, selected.message(), TEXT_PRIMARY);
        detailY = renderWrappedText(ctx, detailX + 8, detailY + 2, detailW - 16, "Why: " + selected.details(), TEXT_DIM);
        detailY = renderWrappedText(ctx, detailX + 8, detailY + 2, detailW - 16, "Metrics: " + selected.metricSummary(), TEXT_DIM);
        renderWrappedText(ctx, detailX + 8, detailY + 2, detailW - 16, "Next step: " + selected.nextStep(), ACCENT_YELLOW);
        return stacked ? Math.max((detailBoxY + detailHeight + 8) - y, rowY - y) : Math.max(rowY - y, detailHeight + 8);
    }

    private int findingSectionRank(ProfilerManager.RuleFinding finding) {
        String category = finding == null || finding.category() == null ? "" : finding.category().toLowerCase(Locale.ROOT);
        String confidence = finding == null || finding.confidence() == null ? "" : finding.confidence().toLowerCase(Locale.ROOT);
        if (category.startsWith("conflict-confirmed") || confidence.equals("known incompatibility")) {
            return 0;
        }
        if (category.startsWith("conflict-repeated")) {
            return 1;
        }
        if (category.startsWith("conflict-weak") || confidence.equals("weak heuristic")) {
            return 2;
        }
        return 3;
    }

    private String findingSectionTitle(ProfilerManager.RuleFinding finding) {
        return switch (findingSectionRank(finding)) {
            case 0 -> "Confirmed contention";
            case 1 -> "Repeated conflict candidates";
            case 2 -> "Weak heuristics";
            default -> "Unrelated slowdown causes";
        };
    }

    private String findingListLabel(ProfilerManager.RuleFinding finding) {
        String category = finding == null || finding.category() == null ? "" : finding.category();
        if (category.startsWith("conflict-")) {
            return "Conflict";
        }
        return prettifyKey(category);
    }

    private int severitySortRank(String severity) {
        return switch (severity == null ? "info" : severity.toLowerCase(Locale.ROOT)) {
            case "critical" -> 3;
            case "error" -> 2;
            case "warning" -> 1;
            default -> 0;
        };
    }

    private void renderThreadWaitSection(DrawContext ctx, int x, int y, int width, Map<String, ThreadLoadProfiler.ThreadSnapshot> details) {
        ctx.drawText(textRenderer, "Blocked / waiting analysis", x, y, TEXT_PRIMARY, false);
        int rowY = y + 14;
        java.util.List<Map.Entry<String, ThreadLoadProfiler.ThreadSnapshot>> interesting = details.entrySet().stream()
                .filter(entry -> entry.getValue().blockedCountDelta() > 0 || entry.getValue().waitedCountDelta() > 0 || "BLOCKED".equals(entry.getValue().state()) || "WAITING".equals(entry.getValue().state()))
                .limit(5)
                .toList();
        if (interesting.isEmpty()) {
            ctx.drawText(textRenderer, "No blocked or waiting thread anomalies in the current window.", x + 6, rowY, TEXT_DIM, false);
            return;
        }
        for (Map.Entry<String, ThreadLoadProfiler.ThreadSnapshot> entry : interesting) {
            ThreadLoadProfiler.ThreadSnapshot detail = entry.getValue();
            String summary = entry.getKey() + " | " + detail.state() + " | blocked " + detail.blockedCountDelta() + " | waited " + detail.waitedCountDelta();
            ctx.drawText(textRenderer, textRenderer.trimToWidth(summary, width), x + 6, rowY, TEXT_PRIMARY, false);
            rowY += 12;
            if (detail.lockName() != null && !detail.lockName().isBlank()) {
                ctx.drawText(textRenderer, textRenderer.trimToWidth("Lock: " + detail.lockName(), width - 6), x + 12, rowY, TEXT_DIM, false);
                rowY += 12;
            }
        }
    }

    int renderPacketSpikeBookmarks(DrawContext ctx, int x, int y, int width, java.util.List<NetworkPacketProfiler.SpikeSnapshot> spikes) {
        List<NetworkPacketProfiler.SpikeSnapshot> filtered = spikes == null ? List.of() : spikes.stream()
                .filter(spike -> matchesGlobalSearch((formatPacketSummary(spike.inboundByCategory()) + " " + formatPacketSummary(spike.inboundByType()) + " " + formatPacketSummary(spike.outboundByCategory()) + " " + formatPacketSummary(spike.outboundByType())).toLowerCase(Locale.ROOT)))
                .toList();
        if (filtered.isEmpty()) {
            ctx.drawText(textRenderer, globalSearch.isBlank() ? "No packet spike bookmarks yet." : "No packet spikes match the universal search.", x + 6, y, TEXT_DIM, false);
            return 14;
        }
        int rowY = y;
        int shown = 0;
        for (NetworkPacketProfiler.SpikeSnapshot spike : filtered) {
            String header = "Spike @ " + formatDuration(Math.max(0L, System.currentTimeMillis() - spike.capturedAtEpochMillis())) + " ago";
            ctx.drawText(textRenderer, header, x + 6, rowY, TEXT_DIM, false);
            rowY += 12;
            String inbound = "In categories: " + formatPacketSummary(spike.inboundByCategory());
            ctx.drawText(textRenderer, textRenderer.trimToWidth(inbound, width - 12), x + 12, rowY, TEXT_PRIMARY, false);
            rowY += 12;
            String inboundTypes = "In types: " + formatPacketSummary(spike.inboundByType());
            ctx.drawText(textRenderer, textRenderer.trimToWidth(inboundTypes, width - 12), x + 12, rowY, TEXT_DIM, false);
            rowY += 12;
            String outbound = "Out categories: " + formatPacketSummary(spike.outboundByCategory());
            ctx.drawText(textRenderer, textRenderer.trimToWidth(outbound, width - 12), x + 12, rowY, TEXT_PRIMARY, false);
            rowY += 12;
            String outboundTypes = "Out types: " + formatPacketSummary(spike.outboundByType());
            ctx.drawText(textRenderer, textRenderer.trimToWidth(outboundTypes, width - 12), x + 12, rowY, TEXT_DIM, false);
            rowY += 14;
            shown++;
            if (shown >= 4) {
                break;
            }
        }
        return rowY - y;
    }

    void renderSpikeInspector(DrawContext ctx, int x, int y, int width) {
        ctx.drawText(textRenderer, "Spike inspector", x, y, TEXT_PRIMARY, false);
        int rowY = y + 14;
        List<ProfilerManager.SpikeCapture> filteredSpikes = snapshot.spikes().stream()
                .filter(spike -> matchesGlobalSearch((spike.likelyBottleneck() + " " + String.join(" ", spike.topCpuMods()) + " " + String.join(" ", spike.topRenderPhases()) + " " + String.join(" ", spike.topThreads())).toLowerCase(Locale.ROOT)))
                .toList();
        if (filteredSpikes.isEmpty()) {
            ctx.drawText(textRenderer, globalSearch.isBlank() ? "No spike bookmarks yet. Capture a hitch to inspect it here." : "No spike bookmarks match the universal search.", x + 6, rowY, TEXT_DIM, false);
            return;
        }
        ProfilerManager.SpikeCapture pinned = ProfilerManager.getInstance().getPinnedSpike();
        if (pinned != null) {
            ctx.drawText(textRenderer, textRenderer.trimToWidth(String.format(Locale.ROOT, "Pinned baseline %.1f ms | stutter %.1f | %s", pinned.frameDurationMs(), pinned.stutterScore(), pinned.likelyBottleneck()), width - 88), x + 6, rowY, ACCENT_GREEN, false);
            renderSpikePinButton(ctx, x + width - 78, rowY - 2, 72, 14, "Clear Pin", null, true);
            rowY += 16;
        }
        int shown = 0;
        for (ProfilerManager.SpikeCapture spike : filteredSpikes) {
            String summary = String.format(Locale.ROOT, "%.1f ms | stutter %.1f | %s", spike.frameDurationMs(), spike.stutterScore(), spike.likelyBottleneck());
            ctx.drawText(textRenderer, textRenderer.trimToWidth(summary, width - 84), x + 6, rowY, shown == 0 ? ACCENT_YELLOW : TEXT_PRIMARY, false);
            renderSpikePinButton(ctx, x + width - 70, rowY - 2, 64, 14, spike.equals(pinned) ? "Pinned" : "Pin", spike, false);
            rowY += 12;
            ProfilerManager.SpikeDelta delta = ProfilerManager.getInstance().compareSpikeToPinned(spike);
            if (delta != null) {
                int deltaColor = delta.frameDurationDeltaMs() <= 0.0 ? ACCENT_GREEN : ACCENT_RED;
                String deltaText = String.format(Locale.ROOT, "%+.1f ms vs pinned | stutter %+.1f | %s", delta.frameDurationDeltaMs(), delta.stutterScoreDelta(), delta.bottleneckChange());
                ctx.drawText(textRenderer, textRenderer.trimToWidth(deltaText, width - 12), x + 12, rowY, deltaColor, false);
                rowY += 12;
            }
            ctx.drawText(textRenderer, textRenderer.trimToWidth("Top CPU: " + String.join(" | ", spike.topCpuMods()), width - 12), x + 12, rowY, TEXT_DIM, false);
            rowY += 12;
            ctx.drawText(textRenderer, textRenderer.trimToWidth("Top render: " + String.join(" | ", spike.topRenderPhases()), width - 12), x + 12, rowY, TEXT_DIM, false);
            rowY += 12;
            ctx.drawText(textRenderer, textRenderer.trimToWidth("Threads: " + String.join(" | ", spike.topThreads()), width - 12), x + 12, rowY, TEXT_DIM, false);
            rowY += 14;
            if (shown == 0) {
                rowY += renderRuleFindingsSection(ctx, x + 6, rowY, width - 6, spike.findings()) + 6;
            }
            shown++;
            if (shown >= 3) {
                break;
            }
        }
    }

    private void renderSpikePinButton(DrawContext ctx, int x, int y, int width, int height, String label, ProfilerManager.SpikeCapture spike, boolean clearPin) {
        drawTopChip(ctx, x, y, width, height, true);
        ctx.drawText(textRenderer, label, x + 8, y + 4, TEXT_PRIMARY, false);
        spikePinClickTargets.add(new SpikePinClickTarget(x, y, width, height, spike, clearPin));
    }

    private int renderSimpleHistoryGraph(DrawContext ctx, int x, int y, int width, int height, java.util.List<Integer> history, String title, String units) {
        return renderSimpleHistoryGraph(ctx, x, y, width, height, history, title, units, -1);
    }

    private int renderSimpleHistoryGraph(DrawContext ctx, int x, int y, int width, int height, java.util.List<Integer> history, String title, String units, int explicitMax) {
        ctx.drawText(textRenderer, title + " (" + units + ")", x, y, TEXT_DIM, false);
        int axisLabelWidth = 34;
        int gx = x;
        int gy = y + 14;
        int graphWidth = Math.max(80, width - axisLabelWidth - 6);
        int axisX = gx + graphWidth + 8;
        int graphHeight = Math.max(36, height - 18);
        ctx.fill(gx - 2, gy - 2, gx + graphWidth + 2, gy + graphHeight + 2, 0x66000000);
        if (history == null || history.isEmpty()) {
            ctx.drawText(textRenderer, "No activity history yet.", gx + 8, gy + graphHeight / 2 - 4, TEXT_DIM, false);
            return height;
        }
        int max = explicitMax > 0 ? explicitMax : history.stream().mapToInt(Integer::intValue).max().orElse(1);
        String topLabel = String.valueOf(max);
        String midLabel = String.valueOf(Math.max(0, max / 2));
        ctx.drawText(textRenderer, topLabel, axisX, gy - 4, TEXT_DIM, false);
        ctx.drawText(textRenderer, midLabel, axisX, gy + graphHeight / 2 - 4, TEXT_DIM, false);
        ctx.drawText(textRenderer, "0", axisX, gy + graphHeight - 8, TEXT_DIM, false);
        for (int px = 0; px < graphWidth; px++) {
            int start = (int) Math.floor(px * history.size() / (double) graphWidth);
            int end = (int) Math.floor((px + 1) * history.size() / (double) graphWidth) - 1;
            if (end < start) {
                end = start;
            }
            start = Math.max(0, Math.min(history.size() - 1, start));
            end = Math.max(0, Math.min(history.size() - 1, end));
            int peak = 0;
            for (int i = start; i <= end; i++) {
                peak = Math.max(peak, history.get(i));
            }
            int barHeight = (int) Math.min(graphHeight, Math.round((peak / (double) Math.max(1, max)) * graphHeight));
            if (barHeight <= 0) {
                continue;
            }
            ctx.fill(gx + px, gy + graphHeight - barHeight, gx + px + 1, gy + graphHeight, 0xFF5EA9FF);
        }
        double[] markerValues = history.stream().mapToDouble(Integer::doubleValue).toArray();
        drawCurrentValueMarker(ctx, gx, gy, graphWidth, graphHeight, axisX, markerValues, max, 0xFF5EA9FF, "", 0);
        return height;
    }

    int renderEntityHotspotSection(DrawContext ctx, int x, int y, int width, java.util.List<ProfilerManager.EntityHotspot> hotspots, String title) {
        ctx.drawText(textRenderer, title, x, y, TEXT_PRIMARY, false);
        int rowY = y + 14;
        if (hotspots == null || hotspots.isEmpty()) {
            ctx.drawText(textRenderer, "No entity hotspots in the current window.", x + 6, rowY, TEXT_DIM, false);
            return rowY + 12;
        }
        int shown = 0;
        for (ProfilerManager.EntityHotspot hotspot : hotspots) {
            ctx.drawText(textRenderer, textRenderer.trimToWidth(hotspot.className() + " x" + hotspot.count(), width), x + 6, rowY, TEXT_PRIMARY, false);
            rowY += 12;
            ctx.drawText(textRenderer, textRenderer.trimToWidth(hotspot.heuristic(), width - 6), x + 12, rowY, TEXT_DIM, false);
            rowY += 14;
            shown++;
            if (shown >= 4) {
                break;
            }
        }
        return rowY;
    }

    int renderBlockEntityHotspotSection(DrawContext ctx, int x, int y, int width, java.util.List<ProfilerManager.BlockEntityHotspot> hotspots, String title) {
        ctx.drawText(textRenderer, title, x, y, TEXT_PRIMARY, false);
        int rowY = y + 14;
        if (hotspots == null || hotspots.isEmpty()) {
            ctx.drawText(textRenderer, "No block entity hotspots in the current window.", x + 6, rowY, TEXT_DIM, false);
            return rowY + 12;
        }
        int shown = 0;
        for (ProfilerManager.BlockEntityHotspot hotspot : hotspots) {
            ctx.drawText(textRenderer, textRenderer.trimToWidth(hotspot.className() + " x" + hotspot.count(), width), x + 6, rowY, TEXT_PRIMARY, false);
            rowY += 12;
            ctx.drawText(textRenderer, textRenderer.trimToWidth(hotspot.heuristic(), width - 6), x + 12, rowY, TEXT_DIM, false);
            rowY += 14;
            shown++;
            if (shown >= 4) {
                break;
            }
        }
        return rowY;
    }

    private String formatPacketSummary(Map<String, Long> packetMap) {
        if (packetMap == null || packetMap.isEmpty()) {
            return "none";
        }
        return packetMap.entrySet().stream()
                .limit(3)
                .map(entry -> entry.getKey() + " " + entry.getValue())
                .reduce((left, right) -> left + " | " + right)
                .orElse("none");
    }

    private String formatTopThreads(Map<String, Double> threadLoads, int maxThreads) {
        if (threadLoads == null || threadLoads.isEmpty()) {
            return "no recent thread samples";
        }
        return threadLoads.entrySet().stream()
                .limit(maxThreads)
                .map(entry -> entry.getKey() + " " + String.format("%.1f%%", entry.getValue()))
                .reduce((left, right) -> left + " | " + right)
                .orElse("no recent thread samples");
    }


    public boolean mouseClicked(Click click, boolean doubled) {
        double mouseX = toLogicalX(click.x());
        double mouseY = toLogicalY(click.y());
        focusedSearchTable = null;
        startupSearchFocused = false;
        globalSearchFocused = false;
        focusedColorSetting = null;

        if (attributionHelpOpen || activeDrilldownTable != null) {
            ModalLayout modal = getCenteredModalLayout(getScreenWidth(), getScreenHeight(), Math.min(920, getScreenWidth() - 32), Math.min(620, getScreenHeight() - 48));
            if (!isInside(mouseX, mouseY, modal.x(), modal.y(), modal.width(), modal.height()) || isInside(mouseX, mouseY, modal.x() + modal.width() - 62, modal.y() + 10, 52, 16)) {
                attributionHelpOpen = false;
                activeDrilldownTable = null;
            }
            return true;
        }

        if (isInside(mouseX, mouseY, PADDING + 106, 3, 128, 14)) {
            ProfilerManager.getInstance().cycleMode();
            return true;
        }

        if (isInside(mouseX, mouseY, getScreenWidth() - 250, 5, 90, 12)) {
            ConfigManager.setHudEnabled(!ConfigManager.isHudEnabled());
            return true;
        }

        if (isInside(mouseX, mouseY, getScreenWidth() - 116, 4, 108, 12)) {
            ProfilerManager.getInstance().exportSession();
            return true;
        }

        if (isInside(mouseX, mouseY, getScreenWidth() - 438, 3, 176, 14)) {
            globalSearchFocused = true;
            return true;
        }

        for (FindingClickTarget target : findingClickTargets) {
            if (isInside(mouseX, mouseY, target.x(), target.y(), target.width(), target.height())) {
                selectedFindingKey = target.key();
                return true;
            }
        }

        for (SpikePinClickTarget target : spikePinClickTargets) {
            if (isInside(mouseX, mouseY, target.x(), target.y(), target.width(), target.height())) {
                if (target.clearPin()) {
                    ProfilerManager.getInstance().clearPinnedSpike();
                } else {
                    ProfilerManager.getInstance().pinSpike(target.spike());
                }
                return true;
            }
        }

        int tabY = getTabY();
        int tabW = Math.max(66, Math.min(84, (getScreenWidth() - (PADDING * 2) - ((TAB_NAMES.length - 1) * 2)) / TAB_NAMES.length));
        for (int i = 0; i < TAB_NAMES.length; i++) {
            int tx = PADDING + i * (tabW + 2);
            if (isInside(mouseX, mouseY, tx - 4, tabY - 2, tabW + 8, TAB_HEIGHT + 4)) {
                activeTab = i;
                lastOpenedTab = activeTab;
                scrollOffset = 0;
                return true;
            }
        }

        if (activeTab == 0) {
            int detailW = Math.min(420, Math.max(320, getScreenWidth() / 3));
            int listW = getScreenWidth() - detailW - PADDING;
            if (isInside(mouseX, mouseY, PADDING, getContentY() + PADDING + 34, 78, 16)) {
                activeTab = 11;
                systemMiniTab = SystemMiniTab.CPU_GRAPH;
                scrollOffset = 0;
                return true;
            }
            if (isInside(mouseX, mouseY, PADDING + 84, getContentY() + PADDING + 34, 98, 16)) {
                taskEffectiveView = !taskEffectiveView;
                scrollOffset = 0;
                return true;
            }
            if (isInside(mouseX, mouseY, PADDING + 188, getContentY() + PADDING + 34, 112, 16)) {
                taskShowSharedRows = !taskShowSharedRows;
                scrollOffset = 0;
                return true;
            }
            if (selectedTaskMod != null && isInside(mouseX, mouseY, listW + PADDING + detailW - 96, getContentY() + PADDING + 6, 88, 16)) {
                activeDrilldownTable = TableId.TASKS;
                return true;
            }
            if (isInside(mouseX, mouseY, listW - 160, getContentY() + PADDING + 34, 152, 16)) {
                focusedSearchTable = TableId.TASKS;
                return true;
            }
            if (isInside(mouseX, mouseY, listW - 214, getContentY() + PADDING + 34, 48, 16)) {
                resetTasksTable();
                return true;
            }
            if (handleTaskHeaderClick(mouseX, mouseY, listW)) {
                return true;
            }
            String clickedMod = findTaskRowAt(mouseX, mouseY);
            if (clickedMod != null) {
                selectedTaskMod = clickedMod;
                return true;
            }
        }

        if (activeTab == 1) {
            int detailW = Math.min(420, Math.max(320, getScreenWidth() / 3));
            int listW = getScreenWidth() - detailW - PADDING;
            if (isInside(mouseX, mouseY, PADDING, getContentY() + PADDING + 34, 78, 16)) {
                activeTab = 11;
                systemMiniTab = SystemMiniTab.GPU_GRAPH;
                scrollOffset = 0;
                return true;
            }
            if (isInside(mouseX, mouseY, PADDING + 84, getContentY() + PADDING + 34, 98, 16)) {
                gpuEffectiveView = !gpuEffectiveView;
                scrollOffset = 0;
                return true;
            }
            if (isInside(mouseX, mouseY, PADDING + 188, getContentY() + PADDING + 34, 112, 16)) {
                gpuShowSharedRows = !gpuShowSharedRows;
                scrollOffset = 0;
                return true;
            }
            if (selectedGpuMod != null && isInside(mouseX, mouseY, listW + PADDING + detailW - 96, getContentY() + PADDING + 6, 88, 16)) {
                activeDrilldownTable = TableId.GPU;
                return true;
            }
            if (isInside(mouseX, mouseY, listW - 160, getContentY() + PADDING + 34, 152, 16)) {
                focusedSearchTable = TableId.GPU;
                return true;
            }
            if (isInside(mouseX, mouseY, listW - 214, getContentY() + PADDING + 34, 48, 16)) {
                resetGpuTable();
                return true;
            }
            if (handleGpuHeaderClick(mouseX, mouseY, listW)) {
                return true;
            }
            String clickedMod = findGpuRowAt(mouseX, mouseY);
            if (clickedMod != null) {
                selectedGpuMod = clickedMod;
                return true;
            }
        }

        if (activeTab == 4) {
            int sharedPanelW = snapshot.sharedMemoryFamilies().isEmpty() ? 0 : Math.min(280, Math.max(220, getScreenWidth() / 4));
            int tableW = getScreenWidth() - sharedPanelW - (sharedPanelW > 0 ? PADDING : 0);
            int memoryDescriptionBottomY = getContentY() + PADDING + measureWrappedHeight(Math.max(260, tableW - 16), memoryEffectiveView ? "Effective live heap by mod with shared/runtime buckets folded into concrete mods for comparison. Updated asynchronously." : "Raw live heap by owner/class family. Shared/runtime buckets stay separate until you switch back to Effective view.");
            int memoryControlsTopY = memoryDescriptionBottomY + 26;
            if (isInside(mouseX, mouseY, tableW - 106, memoryControlsTopY + 18, 98, 16)) {
                activeTab = 11;
                systemMiniTab = SystemMiniTab.MEMORY_GRAPH;
                scrollOffset = 0;
                return true;
            }
            int memoryContentHeight = getScreenHeight() - getContentY() - PADDING;
            if (selectedMemoryMod != null && isInside(mouseX, mouseY, tableW - 96, getContentY() + memoryContentHeight - 116 + 6, 88, 16)) {
                activeDrilldownTable = TableId.MEMORY;
                return true;
            }
            if (isInside(mouseX, mouseY, tableW - 112, memoryControlsTopY, 98, 16)) {
                memoryEffectiveView = !memoryEffectiveView;
                scrollOffset = 0;
                return true;
            }
            if (isInside(mouseX, mouseY, tableW - 222, memoryControlsTopY, 112, 16)) {
                memoryShowSharedRows = !memoryShowSharedRows;
                scrollOffset = 0;
                return true;
            }
            MemoryListLayout memoryLayout = getMemoryListLayout();
            if (isInside(mouseX, mouseY, tableW - 160, memoryLayout.searchY(), 152, 16)) {
                focusedSearchTable = TableId.MEMORY;
                return true;
            }
            if (isInside(mouseX, mouseY, tableW - 214, memoryLayout.searchY(), 48, 16)) {
                resetMemoryTable();
                return true;
            }
            if (handleMemoryHeaderClick(mouseX, mouseY, tableW)) {
                return true;
            }
            String clickedMod = findMemoryRowAt(mouseX, mouseY);
            if (clickedMod != null) {
                selectedMemoryMod = clickedMod;
                return true;
            }
            String clickedFamily = findSharedFamilyAt(mouseX, mouseY);
            if (clickedFamily != null) {
                selectedSharedFamily = clickedFamily;
                return true;
            }
        }

        if (activeTab == 3) {
            if (isInside(mouseX, mouseY, getScreenWidth() - 160, getContentY() + PADDING + 28 - scrollOffset, 152, 16)) {
                startupSearchFocused = true;
                return true;
            }
            if (isInside(mouseX, mouseY, getScreenWidth() - 214, getContentY() + PADDING + 28 - scrollOffset, 48, 16)) {
                resetStartupTable();
                return true;
            }
            if (handleStartupHeaderClick(mouseX, mouseY, 0, getScreenWidth())) {
                return true;
            }
        }

        if (activeTab == 9) {
            LagMapLayout lagMapLayout = lastRenderedLagMapLayout != null
                    ? lastRenderedLagMapLayout
                    : getLagMapLayout(getContentY(), getScreenWidth(), getScreenHeight() - getContentY() - PADDING);
            int left = lagMapLayout.left();
            int top = lagMapLayout.miniTabY();
            if (isInside(mouseX, mouseY, left - 3, top - 2, 82, 20)) {
                worldMiniTab = WorldMiniTab.LAG_MAP;
                return true;
            }
            if (isInside(mouseX, mouseY, left + 79, top - 2, 76, 20)) {
                worldMiniTab = WorldMiniTab.ENTITIES;
                return true;
            }
            if (isInside(mouseX, mouseY, left + 155, top - 2, 78, 20)) {
                worldMiniTab = WorldMiniTab.CHUNKS;
                return true;
            }
            if (isInside(mouseX, mouseY, left + 233, top - 2, 114, 20)) {
                worldMiniTab = WorldMiniTab.BLOCK_ENTITIES;
                return true;
            }
            if (worldMiniTab == WorldMiniTab.LAG_MAP) {
                ChunkPos clickedChunk = findLagMapChunkAt(mouseX, mouseY);
                if (clickedChunk != null) {
                    selectedLagChunk = clickedChunk;
                    return true;
                }
            }
        }

        if (activeTab == 10) {
            if (handleThreadToolbarClick(mouseX, mouseY)) {
                return true;
            }
            long clickedThreadId = findThreadRowAt(mouseX, mouseY);
            if (clickedThreadId >= 0L) {
                selectedThreadId = clickedThreadId;
                return true;
            }
        }

        if (activeTab == 6) {
            int left = PADDING + Math.max(PADDING, (getScreenWidth() - getPreferredGraphWidth(getScreenWidth())) / 2);
            int top = getFullPageScrollTop(getContentY()) + 28;
            if (isInside(mouseX, mouseY, left, top, 96, 16)) {
                ProfilerManager.getInstance().setBaseline(ProfilerManager.getInstance().captureBaseline("manual"));
                return true;
            }
            if (isInside(mouseX, mouseY, left + 102, top, 112, 16)) {
                ProfilerManager.getInstance().importBaselineFromLatestExport();
                return true;
            }
            if (isInside(mouseX, mouseY, left + 220, top, 74, 16)) {
                ProfilerManager.getInstance().clearBaseline();
                return true;
            }
        }

        if (activeTab == 11) {
            int left = PADDING;
            int top = getFullPageScrollTop(getContentY()) + 28;
            if (isInside(mouseX, mouseY, left - 3, top - 2, 84, 20)) {
                systemMiniTab = SystemMiniTab.OVERVIEW;
                return true;
            }
            if (isInside(mouseX, mouseY, left + 81, top - 2, 94, 20)) {
                systemMiniTab = SystemMiniTab.CPU_GRAPH;
                return true;
            }
            if (isInside(mouseX, mouseY, left + 175, top - 2, 94, 20)) {
                systemMiniTab = SystemMiniTab.GPU_GRAPH;
                return true;
            }
            if (isInside(mouseX, mouseY, left + 269, top - 2, 114, 20)) {
                systemMiniTab = SystemMiniTab.MEMORY_GRAPH;
                return true;
            }
            int graphTabsY = top + 24;
            int graphTabsX = Math.max(PADDING, (getScreenWidth() - getPreferredGraphWidth(getScreenWidth())) / 2);
            if (systemMiniTab == SystemMiniTab.CPU_GRAPH) {
                if (isInside(mouseX, mouseY, graphTabsX - 3, graphTabsY - 2, 90, 20)) {
                    cpuGraphMetricTab = GraphMetricTab.LOAD;
                    return true;
                }
                if (isInside(mouseX, mouseY, graphTabsX + 87, graphTabsY - 2, 126, 20)) {
                    cpuGraphMetricTab = GraphMetricTab.TEMPERATURE;
                    return true;
                }
            }
            if (systemMiniTab == SystemMiniTab.GPU_GRAPH) {
                if (isInside(mouseX, mouseY, graphTabsX - 3, graphTabsY - 2, 90, 20)) {
                    gpuGraphMetricTab = GraphMetricTab.LOAD;
                    return true;
                }
                if (isInside(mouseX, mouseY, graphTabsX + 87, graphTabsY - 2, 126, 20)) {
                    gpuGraphMetricTab = GraphMetricTab.TEMPERATURE;
                    return true;
                }
            }
        }

        if (activeTab == 12) {
            int left = PADDING;
            int actionY = getContentY() + PADDING + 18 - scrollOffset;
            if (isInside(mouseX, mouseY, left + 104, actionY - 2, 108, 16)) {
                attributionHelpOpen = true;
                return true;
            }
            actionY += measureWrappedHeight(getScreenWidth() - 24, "Raw keeps direct ownership separate. Effective folds shared/runtime buckets back into mods for easier ranking. Use Open Guide for the full measured / inferred / estimated explanation.") + 28;
            Runnable[] sessionActions = sessionActions();
            for (Runnable action : sessionActions) {
                if (isInside(mouseX, mouseY, left, actionY, getScreenWidth() - 16, 16)) {
                    action.run();
                    return true;
                }
                actionY += 22;
            }

            actionY += 32;
            Runnable[] hudBaseActions = hudBaseActions();
            for (Runnable action : hudBaseActions) {
                if (isInside(mouseX, mouseY, left, actionY, getScreenWidth() - 16, 16)) {
                    action.run();
                    return true;
                }
                actionY += 22;
            }
            if (handleHudTransparencySliderClick(mouseX, mouseY, left, actionY, getScreenWidth() - 24)) {
                return true;
            }
            actionY += 24;
            if (isInside(mouseX, mouseY, left, actionY, getScreenWidth() - 16, 16)) {
                ConfigManager.cycleHudConfigMode();
                return true;
            }
            actionY += 22;

            boolean presetMode = ConfigManager.getHudConfigMode() == ConfigManager.HudConfigMode.PRESET;
            Runnable[] hudModeActions = hudModeActions(presetMode);
            for (Runnable action : hudModeActions) {
                if (isInside(mouseX, mouseY, left, actionY, getScreenWidth() - 16, 16)) {
                    action.run();
                    return true;
                }
                actionY += 22;
            }

            actionY += presetMode ? 20 : 20;
            Runnable[] hudRateActions = hudRateActions();
            for (Runnable action : hudRateActions) {
                if (isInside(mouseX, mouseY, left, actionY, getScreenWidth() - 16, 16)) {
                    action.run();
                    return true;
                }
                actionY += 22;
            }

            actionY += 32;
            Runnable[] performanceAlertActions = performanceAlertActions();
            for (Runnable action : performanceAlertActions) {
                if (isInside(mouseX, mouseY, left, actionY, getScreenWidth() - 16, 16)) {
                    action.run();
                    return true;
                }
                actionY += 22;
            }

            actionY += 32;
            Runnable[] tableActions = tableActions();
            for (Runnable action : tableActions) {
                if (isInside(mouseX, mouseY, left, actionY, getScreenWidth() - 16, 16)) {
                    action.run();
                    return true;
                }
                actionY += 22;
            }

            actionY += 32;
            ColorSetting[] colorSettings = colorSettings();
            for (ColorSetting colorSetting : colorSettings) {
                if (isInside(mouseX, mouseY, left, actionY, getScreenWidth() - 16, 16)) {
                    focusedColorSetting = colorSetting;
                    colorEditValue = getColorSettingHex(focusedColorSetting);
                    return true;
                }
                actionY += 22;
            }
            if (isInside(mouseX, mouseY, left, actionY, getScreenWidth() - 16, 16)) {
                ConfigManager.resetGraphColors();
                focusedColorSetting = null;
                colorEditValue = "";
                return true;
            }
        }

        return super.mouseClicked(click, doubled);
    }



    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (draggingHudTransparency && activeTab == 12) {
            int left = PADDING;
            int actionY = getContentY() + PADDING + 18 - scrollOffset;
            actionY += (22 * sessionActionCount()) + 32 + (22 * hudBaseActionCount());
            SliderLayout slider = getHudTransparencySliderLayout(left, actionY, getScreenWidth() - 24);
            updateHudTransparencyFromMouse(toLogicalX(click.x()), slider);
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        draggingHudTransparency = false;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        mouseX = toLogicalX(mouseX);
        mouseY = toLogicalY(mouseY);
        int maxScroll = getMaxScrollOffset();
        if (maxScroll <= 0) {
            scrollOffset = 0;
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) (verticalAmount * 18.0)));
        return true;
    }

    private int getMaxScrollOffset() {
        int visibleHeight = Math.max(1, getScreenHeight() - getContentY() - PADDING);
        int contentHeight = switch (activeTab) {
            case 0 -> Math.max(visibleHeight, 40 + (getTaskRows().size() * ATTRIBUTION_ROW_HEIGHT));
            case 1 -> Math.max(visibleHeight, 40 + (getGpuRows().size() * ATTRIBUTION_ROW_HEIGHT));
            case 2 -> Math.max(visibleHeight, 32 + (snapshot.renderPhases().size() * ROW_HEIGHT));
            case 3 -> Math.max(visibleHeight, 68 + (snapshot.startupRows().size() * STARTUP_ROW_HEIGHT));
            case 4 -> Math.max(visibleHeight, 214 + (getMemoryRows().size() * ATTRIBUTION_ROW_HEIGHT));
            case 5 -> Math.max(visibleHeight, 44 + (Math.min(20, snapshot.flamegraphStacks().size()) * 12));
            case 6 -> Math.max(visibleHeight, 430);
            case 7 -> Math.max(visibleHeight, 560);
            case 8 -> Math.max(visibleHeight, 240);
            case 9 -> Math.max(visibleHeight, 980);
            case 10 -> Math.max(visibleHeight, 56 + (getThreadRows().size() * ATTRIBUTION_ROW_HEIGHT));
            case 11 -> Math.max(visibleHeight, 1560);
            case 12 -> Math.max(visibleHeight, getSettingsContentHeight());
            default -> visibleHeight;
        };
        return Math.max(0, contentHeight - visibleHeight);
    }

    private int getSettingsContentHeight() {
        int contentHeight = 18;
        contentHeight += sessionActionCount() * 22;
        contentHeight += 32;
        contentHeight += 18;
        contentHeight += hudBaseActionCount() * 22;
        contentHeight += 24;
        contentHeight += 22;
        if (ConfigManager.getHudConfigMode() == ConfigManager.HudConfigMode.PRESET) {
            contentHeight += hudModeActionCount(true) * 22;
            contentHeight += 20;
            contentHeight += 34;
        } else {
            contentHeight += hudModeActionCount(false) * 22;
            contentHeight += 20;
        }
        contentHeight += 18;
        contentHeight += hudRateActionCount() * 22;
        contentHeight += 32;
        contentHeight += 18;
        contentHeight += performanceAlertActionCount() * 22;
        contentHeight += 32;
        contentHeight += 18;
        contentHeight += tableActionCount() * 22;
        contentHeight += 32;
        contentHeight += 18;
        contentHeight += colorSettingCount() * 22;
        contentHeight += 34;
        return contentHeight;
    }


    static int sessionActionCount() {
        return sessionActions().length;
    }

    static int hudBaseActionCount() {
        return hudBaseActions().length;
    }

    static int hudModeActionCount(boolean presetMode) {
        return hudModeActions(presetMode).length;
    }

    static int hudRateActionCount() {
        return hudRateActions().length;
    }

    static int performanceAlertActionCount() {
        return performanceAlertActions().length;
    }

    static int tableActionCount() {
        return tableActions().length;
    }

    static int colorSettingCount() {
        return colorSettings().length + 1;
    }

    private static Runnable[] sessionActions() {
        return new Runnable[] {
                () -> ProfilerManager.getInstance().toggleSessionLogging(),
                ConfigManager::cycleSessionDurationSeconds,
                ConfigManager::cycleMetricsUpdateIntervalMs,
                ConfigManager::cycleProfilerUpdateDelayMs
        };
    }

    private static Runnable[] hudBaseActions() {
        return new Runnable[] {
                () -> ConfigManager.setHudEnabled(!ConfigManager.isHudEnabled()),
                ConfigManager::cycleHudPosition,
                ConfigManager::cycleHudLayoutMode,
                ConfigManager::cycleHudTriggerMode,
                ConfigManager::cycleFrameBudgetTargetFps
        };
    }

    private static Runnable[] hudModeActions(boolean presetMode) {
        return presetMode
                ? new Runnable[] {
                    ConfigManager::cycleHudPreset,
                    () -> ConfigManager.setHudExpandedOnWarning(!ConfigManager.isHudExpandedOnWarning()),
                    ConfigManager::toggleHudBudgetColorMode,
                    ConfigManager::toggleHudAutoFocusAlertRow
                }
                : new Runnable[] {
                    ConfigManager::toggleHudShowFps,
                    ConfigManager::toggleHudShowFrame,
                    ConfigManager::toggleHudShowTicks,
                    ConfigManager::toggleHudShowUtilization,
                    ConfigManager::toggleHudShowTemperatures,
                    ConfigManager::toggleHudShowParallelism,
                    ConfigManager::toggleHudShowLogic,
                    ConfigManager::toggleHudShowBackground,
                    ConfigManager::toggleHudShowFrameBudget,
                    ConfigManager::toggleHudShowMemory,
                    ConfigManager::toggleHudShowVram,
                    ConfigManager::toggleHudShowNetwork,
                    ConfigManager::toggleHudShowChunkActivity,
                    ConfigManager::toggleHudShowWorld,
                    ConfigManager::toggleHudShowDiskIo,
                    ConfigManager::toggleHudShowInputLatency,
                    ConfigManager::toggleHudShowSession,
                    () -> ConfigManager.setHudExpandedOnWarning(!ConfigManager.isHudExpandedOnWarning()),
                    ConfigManager::toggleHudBudgetColorMode,
                    ConfigManager::toggleHudAutoFocusAlertRow
                };
    }

    private static Runnable[] hudRateActions() {
        return new Runnable[] {
                ConfigManager::toggleHudShowZeroRateOfChange,
                ConfigManager::toggleHudShowFpsRateOfChange,
                ConfigManager::toggleHudShowFrameRateOfChange,
                ConfigManager::toggleHudShowTickRateOfChange,
                ConfigManager::toggleHudShowUtilizationRateOfChange,
                ConfigManager::toggleHudShowMemoryAllocationRate,
                ConfigManager::toggleHudShowVramRateOfChange,
                ConfigManager::toggleHudShowNetworkRateOfChange,
                ConfigManager::toggleHudShowChunkActivityRateOfChange,
                ConfigManager::toggleHudShowWorldRateOfChange,
                ConfigManager::toggleHudShowDiskIoRateOfChange,
                ConfigManager::toggleHudShowInputLatencyRateOfChange
        };
    }

    private static Runnable[] performanceAlertActions() {
        return new Runnable[] {
                ConfigManager::togglePerformanceAlertsEnabled,
                ConfigManager::togglePerformanceAlertChatEnabled,
                ConfigManager::cyclePerformanceAlertFrameThresholdMs,
                ConfigManager::cyclePerformanceAlertServerThresholdMs,
                ConfigManager::cyclePerformanceAlertConsecutiveTicks
        };
    }

    private static Runnable[] tableActions() {
        return new Runnable[] {
                () -> ConfigManager.toggleTasksColumn("cpu"),
                () -> ConfigManager.toggleTasksColumn("threads"),
                () -> ConfigManager.toggleTasksColumn("samples"),
                () -> ConfigManager.toggleTasksColumn("invokes"),
                () -> ConfigManager.toggleGpuColumn("pct"),
                () -> ConfigManager.toggleGpuColumn("threads"),
                () -> ConfigManager.toggleGpuColumn("gpums"),
                () -> ConfigManager.toggleGpuColumn("rsamples"),
                () -> ConfigManager.toggleMemoryColumn("classes"),
                () -> ConfigManager.toggleMemoryColumn("mb"),
                () -> ConfigManager.toggleMemoryColumn("pct")
        };
    }

    private static ColorSetting[] colorSettings() {
        return new ColorSetting[] {ColorSetting.CPU, ColorSetting.GPU, ColorSetting.WORLD_ENTITIES, ColorSetting.WORLD_CHUNKS_LOADED, ColorSetting.WORLD_CHUNKS_RENDERED};
    }

    private boolean isInside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (focusedColorSetting != null && input.isValidChar()) {
            colorEditValue = normalizeColorEdit(colorEditValue + input.asString());
            return true;
        }
        if (globalSearchFocused && input.isValidChar()) {
            globalSearch += input.asString();
            scrollOffset = 0;
            return true;
        }
        if (startupSearchFocused && input.isValidChar()) {
            startupSearch += input.asString();
            scrollOffset = 0;
            return true;
        }
        if (focusedSearchTable == null || !input.isValidChar()) {
            return super.charTyped(input);
        }
        String current = getSearchValue(focusedSearchTable);
        setSearchValue(focusedSearchTable, current + input.asString());
        scrollOffset = 0;
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if ((attributionHelpOpen || activeDrilldownTable != null) && input.key() == 256) {
            attributionHelpOpen = false;
            activeDrilldownTable = null;
            return true;
        }
        if (wueffi.taskmanager.client.util.KeyBindHandler.matchesOpenKey(input)) {
            close();
            return true;
        }
        if (input.key() == 47) {
            globalSearchFocused = true;
            focusedSearchTable = null;
            startupSearchFocused = false;
            return true;
        }
        if (focusedColorSetting != null) {
            if (input.key() == 259) {
                if (!colorEditValue.isEmpty()) {
                    colorEditValue = colorEditValue.substring(0, Math.max(0, colorEditValue.length() - 1));
                    if (colorEditValue.isEmpty()) colorEditValue = "#";
                }
                return true;
            }
            if (input.key() == 257 || input.key() == 335) {
                applyColorSetting(focusedColorSetting, colorEditValue);
                focusedColorSetting = null;
                colorEditValue = "";
                return true;
            }
            if (input.key() == 256) {
                focusedColorSetting = null;
                colorEditValue = "";
                return true;
            }
        }
        if (startupSearchFocused) {
            if (input.key() == 259) {
                if (!startupSearch.isEmpty()) {
                    startupSearch = startupSearch.substring(0, startupSearch.length() - 1);
                }
                return true;
            }
            if (input.key() == 256) {
                startupSearchFocused = false;
                return true;
            }
        }
        if (globalSearchFocused) {
            if (input.key() == 259) {
                if (!globalSearch.isEmpty()) {
                    globalSearch = globalSearch.substring(0, globalSearch.length() - 1);
                }
                return true;
            }
            if (input.key() == 256) {
                globalSearchFocused = false;
                return true;
            }
        }
        if (focusedSearchTable != null) {
            if (input.key() == 259) {
                String current = getSearchValue(focusedSearchTable);
                if (!current.isEmpty()) {
                    setSearchValue(focusedSearchTable, current.substring(0, current.length() - 1));
                }
                return true;
            }
            if (input.key() == 256) {
                focusedSearchTable = null;
                return true;
            }
        }
        return super.keyPressed(input);
    }

    private String getSearchValue(TableId tableId) {
        return switch (tableId) {
            case TASKS -> tasksSearch;
            case GPU -> gpuSearch;
            case MEMORY -> memorySearch;
        };
    }

    private void setSearchValue(TableId tableId, String value) {
        String normalized = value == null ? "" : value;
        switch (tableId) {
            case TASKS -> tasksSearch = normalized;
            case GPU -> gpuSearch = normalized;
            case MEMORY -> memorySearch = normalized;
        }
    }

    private boolean matchesCombinedSearch(String haystack, String localQuery) {
        return SearchState.matchesCombinedSearch(haystack, globalSearch, localQuery);
    }

    boolean matchesGlobalSearch(String haystack) {
        return SearchState.matchesQuery(haystack, globalSearch);
    }

    private boolean matchesQuery(String haystack, String query) {
        return SearchState.matchesQuery(haystack, query);
    }

    List<String> getTaskRows(Map<String, CpuSamplingProfiler.Snapshot> cpu, Map<String, CpuSamplingProfiler.DetailSnapshot> cpuDetails, Map<String, ModTimingSnapshot> invokes, boolean includeShared) {
        LinkedHashSet<String> mods = new LinkedHashSet<>();
        mods.addAll(cpu.keySet());
        mods.addAll(invokes.keySet());
        List<String> rows = new ArrayList<>();
        for (String modId : mods) {
            if (!includeShared && isSharedAttributionBucket(modId) && !"shared/gpu-stall".equals(modId)) {
                continue;
            }
            String haystack = (modId + " " + getDisplayName(modId)).toLowerCase(Locale.ROOT);
            if (matchesCombinedSearch(haystack, tasksSearch)) {
                rows.add(modId);
            }
        }
        rows.sort(taskComparator(cpu, cpuDetails, invokes));
        if (taskSortDescending) {
            Collections.reverse(rows);
        }
        return rows;
    }

    private Comparator<String> taskComparator(Map<String, CpuSamplingProfiler.Snapshot> cpu, Map<String, CpuSamplingProfiler.DetailSnapshot> cpuDetails, Map<String, ModTimingSnapshot> invokes) {
        long totalCpuSamples = Math.max(1L, totalCpuMetric(cpu));
        return switch (taskSort) {
            case NAME -> Comparator.comparing((String modId) -> getDisplayName(modId).toLowerCase(Locale.ROOT));
            case THREADS -> Comparator.comparingInt((String modId) -> cpuDetails.get(modId) == null ? 0 : cpuDetails.get(modId).sampledThreadCount());
            case SAMPLES -> Comparator.comparingLong((String modId) -> cpu.getOrDefault(modId, new CpuSamplingProfiler.Snapshot(0, 0, 0)).totalSamples());
            case INVOKES -> Comparator.comparingLong((String modId) -> invokes.getOrDefault(modId, new ModTimingSnapshot(0, 0)).calls());
            case CPU -> Comparator.comparingDouble((String modId) -> cpuMetricValue(cpu.getOrDefault(modId, new CpuSamplingProfiler.Snapshot(0, 0, 0))) * 100.0 / totalCpuSamples);
        };
    }

    List<String> getGpuRows(EffectiveGpuAttribution gpuAttribution, Map<String, CpuSamplingProfiler.DetailSnapshot> cpuDetails, boolean includeShared) {
        List<String> rows = new ArrayList<>();
        boolean forceShowShared = gpuAttribution.gpuNanosByMod().getOrDefault("shared/render", 0L) > 0L
                && gpuAttribution.gpuNanosByMod().entrySet().stream()
                .noneMatch(entry -> !isSharedAttributionBucket(entry.getKey()) && entry.getValue() > 0L);
        for (String modId : gpuAttribution.gpuNanosByMod().keySet()) {
            if (gpuAttribution.gpuNanosByMod().getOrDefault(modId, 0L) <= 0L && gpuAttribution.renderSamplesByMod().getOrDefault(modId, 0L) <= 0L) {
                continue;
            }
            if (!includeShared && isSharedAttributionBucket(modId) && !forceShowShared) {
                continue;
            }
            String haystack = (modId + " " + getDisplayName(modId)).toLowerCase(Locale.ROOT);
            if (matchesCombinedSearch(haystack, gpuSearch)) {
                rows.add(modId);
            }
        }
        rows.sort(gpuComparator(gpuAttribution, cpuDetails));
        if (gpuSortDescending) {
            Collections.reverse(rows);
        }
        return rows;
    }

    private Comparator<String> gpuComparator(EffectiveGpuAttribution gpuAttribution, Map<String, CpuSamplingProfiler.DetailSnapshot> cpuDetails) {
        long safeTotalGpuNanos = Math.max(1L, gpuAttribution.totalGpuNanos());
        return switch (gpuSort) {
            case NAME -> Comparator.comparing((String modId) -> getDisplayName(modId).toLowerCase(Locale.ROOT));
            case THREADS -> Comparator.comparingInt((String modId) -> cpuDetails.get(modId) == null ? 0 : cpuDetails.get(modId).sampledThreadCount());
            case GPU_MS -> Comparator.comparingDouble((String modId) -> gpuAttribution.gpuNanosByMod().getOrDefault(modId, 0L) / 1_000_000.0);
            case RENDER_SAMPLES -> Comparator.comparingLong((String modId) -> gpuAttribution.renderSamplesByMod().getOrDefault(modId, 0L));
            case EST_GPU -> Comparator.comparingDouble((String modId) -> gpuAttribution.gpuNanosByMod().getOrDefault(modId, 0L) * 100.0 / safeTotalGpuNanos);
        };
    }

    List<String> getMemoryRows(Map<String, Long> memoryMods, Map<String, Map<String, Long>> memoryClassesByMod, boolean includeShared) {
        long totalAttributedBytes = Math.max(1L, memoryMods.values().stream().mapToLong(Long::longValue).sum());
        List<String> rows = new ArrayList<>();
        for (String modId : memoryMods.keySet()) {
            if (!includeShared && isSharedAttributionBucket(modId)) {
                continue;
            }
            String haystack = (modId + " " + getDisplayName(modId)).toLowerCase(Locale.ROOT);
            if (matchesCombinedSearch(haystack, memorySearch)) {
                rows.add(modId);
            }
        }
        rows.sort(memoryComparator(memoryMods, memoryClassesByMod, totalAttributedBytes));
        if (memorySortDescending) {
            Collections.reverse(rows);
        }
        return rows;
    }

    private Comparator<String> memoryComparator(Map<String, Long> memoryMods, Map<String, Map<String, Long>> memoryClassesByMod, long totalAttributedBytes) {
        return switch (memorySort) {
            case NAME -> Comparator.comparing((String modId) -> getDisplayName(modId).toLowerCase(Locale.ROOT));
            case CLASS_COUNT -> Comparator.comparingInt((String modId) -> memoryClassesByMod.getOrDefault(modId, Map.of()).size());
            case PERCENT -> Comparator.comparingDouble((String modId) -> memoryMods.getOrDefault(modId, 0L) * 100.0 / totalAttributedBytes);
            case MEMORY_MB -> Comparator.comparingLong((String modId) -> memoryMods.getOrDefault(modId, 0L));
        };
    }

    boolean isSharedAttributionBucket(String modId) {
        return AttributionModelBuilder.isSharedAttributionBucket(modId);
    }

    private void invalidateDerivedAttributionCacheIfSnapshotChanged() {
        if (cachedAttributionSnapshot == snapshot) {
            return;
        }
        cachedAttributionSnapshot = snapshot;
        cachedEffectiveCpuAttribution = null;
        cachedRawGpuAttribution = null;
        cachedEffectiveGpuAttribution = null;
        cachedEffectiveMemoryAttribution = null;
        cachedRawCpuTotalMetric = Math.max(1L, totalCpuMetric(snapshot.cpuMods()));
        cachedRawMemoryTotalBytes = Math.max(1L, snapshot.memoryMods().values().stream().mapToLong(Long::longValue).sum());
    }

    private void ensureCpuAttributionCache() {
        invalidateDerivedAttributionCacheIfSnapshotChanged();
        if (cachedEffectiveCpuAttribution != null) {
            return;
        }
        cachedEffectiveCpuAttribution = AttributionModelBuilder.buildEffectiveCpuAttribution(snapshot.cpuMods(), snapshot.cpuDetails(), snapshot.modInvokes());
    }

    private void ensureGpuAttributionCache() {
        invalidateDerivedAttributionCacheIfSnapshotChanged();
        ensureCpuAttributionCache();
        if (cachedRawGpuAttribution == null) {
            cachedRawGpuAttribution = AttributionModelBuilder.buildEffectiveGpuAttribution(snapshot.renderPhases(), snapshot.cpuMods(), cachedEffectiveCpuAttribution, false);
        }
        if (cachedEffectiveGpuAttribution == null) {
            cachedEffectiveGpuAttribution = AttributionModelBuilder.buildEffectiveGpuAttribution(snapshot.renderPhases(), snapshot.cpuMods(), cachedEffectiveCpuAttribution, true);
        }
    }

    private void ensureMemoryAttributionCache() {
        invalidateDerivedAttributionCacheIfSnapshotChanged();
        if (cachedEffectiveMemoryAttribution != null) {
            return;
        }
        cachedEffectiveMemoryAttribution = AttributionModelBuilder.buildEffectiveMemoryAttribution(snapshot.memoryMods());
    }

    EffectiveCpuAttribution effectiveCpuAttribution() {
        ensureCpuAttributionCache();
        return cachedEffectiveCpuAttribution;
    }

    EffectiveGpuAttribution rawGpuAttribution() {
        ensureGpuAttributionCache();
        return cachedRawGpuAttribution;
    }

    EffectiveGpuAttribution effectiveGpuAttribution() {
        ensureGpuAttributionCache();
        return cachedEffectiveGpuAttribution;
    }

    EffectiveMemoryAttribution effectiveMemoryAttribution() {
        ensureMemoryAttributionCache();
        return cachedEffectiveMemoryAttribution;
    }

    long cpuMetricValue(CpuSamplingProfiler.Snapshot snapshot) {
        if (snapshot == null) {
            return 0L;
        }
        return snapshot.totalCpuNanos() > 0L ? snapshot.totalCpuNanos() : snapshot.totalSamples();
    }

    long totalCpuMetric(Map<String, CpuSamplingProfiler.Snapshot> snapshots) {
        long totalCpuNanos = snapshots.values().stream().mapToLong(CpuSamplingProfiler.Snapshot::totalCpuNanos).sum();
        if (totalCpuNanos > 0L) {
            return totalCpuNanos;
        }
        return snapshots.values().stream().mapToLong(CpuSamplingProfiler.Snapshot::totalSamples).sum();
    }


    private List<String> getTaskRows() {
        Map<String, CpuSamplingProfiler.Snapshot> displayCpu = taskEffectiveView ? effectiveCpuAttribution().displaySnapshots() : snapshot.cpuMods();
        return getTaskRows(displayCpu, snapshot.cpuDetails(), snapshot.modInvokes(), !taskEffectiveView && taskShowSharedRows);
    }

    private List<String> getGpuRows() {
        return getGpuRows(gpuEffectiveView ? effectiveGpuAttribution() : rawGpuAttribution(), snapshot.cpuDetails(), !gpuEffectiveView && gpuShowSharedRows);
    }

    private List<String> getMemoryRows() {

        Map<String, Long> displayMemory = memoryEffectiveView ? effectiveMemoryAttribution().displayBytes() : snapshot.memoryMods();
        return getMemoryRows(displayMemory, snapshot.memoryClassesByMod(), !memoryEffectiveView && memoryShowSharedRows);
    }

    List<SystemMetricsProfiler.ThreadDrilldown> getThreadRows() {
        List<SystemMetricsProfiler.ThreadDrilldown> sourceRows = (threadFreeze && !frozenThreadRows.isEmpty())
                ? frozenThreadRows
                : snapshot.systemMetrics().threadDrilldown();
        List<SystemMetricsProfiler.ThreadDrilldown> rows = sourceRows.stream()
                .filter(thread -> matchesGlobalSearch((thread.threadName() + " " + thread.ownerMod() + " " + thread.reasonFrame() + " " + String.join(" ", thread.topFrames())).toLowerCase(Locale.ROOT)))
                .sorted(threadComparator())
                .toList();
        if (threadSortDescending) {
            List<SystemMetricsProfiler.ThreadDrilldown> reversed = new ArrayList<>(rows);
            Collections.reverse(reversed);
            return reversed;
        }
        return rows;
    }

    Comparator<SystemMetricsProfiler.ThreadDrilldown> threadComparator() {
        return switch (threadSort) {
            case NAME -> Comparator.comparing(thread -> cleanProfilerLabel(thread.threadName()).toLowerCase(Locale.ROOT));
            case CPU -> Comparator.comparingDouble(SystemMetricsProfiler.ThreadDrilldown::cpuLoadPercent);
            case ALLOC -> Comparator.comparingLong(SystemMetricsProfiler.ThreadDrilldown::allocationRateBytesPerSecond);
            case BLOCKED -> Comparator.comparingLong(SystemMetricsProfiler.ThreadDrilldown::blockedTimeDeltaMs);
            case WAITED -> Comparator.comparingLong(SystemMetricsProfiler.ThreadDrilldown::waitedTimeDeltaMs);
        };
    }
    java.util.List<StartupTimingProfiler.StartupRow> getStartupRows() {
        java.util.List<StartupTimingProfiler.StartupRow> rows = new ArrayList<>();
        for (StartupTimingProfiler.StartupRow row : snapshot.startupRows()) {
            String haystack = (row.modId() + " " + getDisplayName(row.modId()) + " " + row.stageSummary() + " " + row.definitionSummary()).toLowerCase(Locale.ROOT);
            if (matchesCombinedSearch(haystack, startupSearch)) {
                rows.add(row);
            }
        }
        rows.sort(startupComparator());
        if (startupSortDescending) {
            Collections.reverse(rows);
        }
        return rows;
    }

    private Comparator<StartupTimingProfiler.StartupRow> startupComparator() {
        return switch (startupSort) {
            case NAME -> Comparator.comparing((StartupTimingProfiler.StartupRow row) -> getDisplayName(row.modId()).toLowerCase(Locale.ROOT));
            case START -> Comparator.comparingLong(StartupTimingProfiler.StartupRow::first);
            case END -> Comparator.comparingLong(StartupTimingProfiler.StartupRow::last);
            case ACTIVE -> Comparator.comparingLong(StartupTimingProfiler.StartupRow::activeNanos);
            case ENTRYPOINTS -> Comparator.comparingInt(StartupTimingProfiler.StartupRow::entrypoints);
            case REGISTRATIONS -> Comparator.comparingInt(StartupTimingProfiler.StartupRow::registrations);
        };
    }

    boolean isColumnVisible(TableId tableId, String key) {
        return switch (tableId) {
            case TASKS -> ConfigManager.isTasksColumnVisible(key);
            case GPU -> ConfigManager.isGpuColumnVisible(key);
            case MEMORY -> ConfigManager.isMemoryColumnVisible(key);
        };
    }

    private void toggleColumn(TableId tableId, String key) {
        switch (tableId) {
            case TASKS -> ConfigManager.toggleTasksColumn(key);
            case GPU -> ConfigManager.toggleGpuColumn(key);
            case MEMORY -> ConfigManager.toggleMemoryColumn(key);
        }
    }

    private boolean handleTaskHeaderClick(double mouseX, double mouseY, int listW) {
        int headerY = getContentY() + PADDING + 76;
        int modX = PADDING + ICON_SIZE + 6;
        int pctX = listW - 206;
        int threadsX = listW - 146;
        int samplesX = listW - 92;
        int invokesX = listW - 42;
        if (isInside(mouseX, mouseY, modX, headerY, 120, 14)) { toggleTaskSort(TaskSort.NAME); return true; }
        if (isInside(mouseX, mouseY, pctX, headerY, 54, 14)) { toggleTaskSort(TaskSort.CPU); return true; }
        if (isInside(mouseX, mouseY, threadsX, headerY, 62, 14)) { toggleTaskSort(TaskSort.THREADS); return true; }
        if (isInside(mouseX, mouseY, samplesX, headerY, 62, 14)) { toggleTaskSort(TaskSort.SAMPLES); return true; }
        if (isInside(mouseX, mouseY, invokesX, headerY, 58, 14)) { toggleTaskSort(TaskSort.INVOKES); return true; }
        return false;
    }

    private boolean handleGpuHeaderClick(double mouseX, double mouseY, int listW) {
        int headerY = getContentY() + PADDING + 76;
        int modX = PADDING + ICON_SIZE + 6;
        int pctX = listW - 232;
        int threadsX = listW - 172;
        int gpuMsX = listW - 108;
        int renderSamplesX = listW - 42;
        if (isInside(mouseX, mouseY, modX, headerY, 120, 14)) { toggleGpuSort(GpuSort.NAME); return true; }
        if (isInside(mouseX, mouseY, pctX, headerY, 64, 14)) { toggleGpuSort(GpuSort.EST_GPU); return true; }
        if (isInside(mouseX, mouseY, threadsX, headerY, 62, 14)) { toggleGpuSort(GpuSort.THREADS); return true; }
        if (isInside(mouseX, mouseY, gpuMsX, headerY, 54, 14)) { toggleGpuSort(GpuSort.GPU_MS); return true; }
        if (isInside(mouseX, mouseY, renderSamplesX, headerY, 42, 14)) { toggleGpuSort(GpuSort.RENDER_SAMPLES); return true; }
        return false;
    }

    private boolean handleMemoryHeaderClick(double mouseX, double mouseY, int tableW) {
        MemoryListLayout layout = getMemoryListLayout();
        int headerY = layout.headerY();
        int modX = PADDING + ICON_SIZE + 6;
        int classesX = tableW - 140;
        int mbX = tableW - 94;
        int pctX = tableW - 42;
        if (isInside(mouseX, mouseY, modX, headerY, 120, 14)) { toggleMemorySort(MemorySort.NAME); return true; }
        if (isInside(mouseX, mouseY, classesX, headerY, 34, 14)) { toggleMemorySort(MemorySort.CLASS_COUNT); return true; }
        if (isInside(mouseX, mouseY, mbX, headerY, 28, 14)) { toggleMemorySort(MemorySort.MEMORY_MB); return true; }
        if (isInside(mouseX, mouseY, pctX, headerY, 20, 14)) { toggleMemorySort(MemorySort.PERCENT); return true; }
        return false;
    }

    private void toggleTaskSort(TaskSort sort) {
        if (taskSort == sort) {
            taskSortDescending = !taskSortDescending;
        } else {
            taskSort = sort;
            taskSortDescending = true;
        }
    }

    private void toggleGpuSort(GpuSort sort) {
        if (gpuSort == sort) {
            gpuSortDescending = !gpuSortDescending;
        } else {
            gpuSort = sort;
            gpuSortDescending = true;
        }
    }

    private void toggleMemorySort(MemorySort sort) {
        if (memorySort == sort) {
            memorySortDescending = !memorySortDescending;
        } else {
            memorySort = sort;
            memorySortDescending = true;
        }
    }

    private boolean handleStartupHeaderClick(double mouseX, double mouseY, int x, int w) {
        int headerY = getContentY() + PADDING + 68 - scrollOffset;
        int regsX = x + w - 34;
        int epX = regsX - 28;
        int activeMsX = epX - 54;
        int endMsX = activeMsX - 54;
        int startMsX = endMsX - 54;
        int barW = Math.max(120, Math.min(220, w / 7));
        int barX = startMsX - barW - 14;
        int modX = x + PADDING + ICON_SIZE + 6;
        if (isInside(mouseX, mouseY, modX, headerY, Math.max(100, barX - modX - 8), 14)) { toggleStartupSort(StartupSort.NAME); return true; }
        if (isInside(mouseX, mouseY, startMsX, headerY, 44, 14)) { toggleStartupSort(StartupSort.START); return true; }
        if (isInside(mouseX, mouseY, endMsX, headerY, 40, 14)) { toggleStartupSort(StartupSort.END); return true; }
        if (isInside(mouseX, mouseY, activeMsX, headerY, 48, 14)) { toggleStartupSort(StartupSort.ACTIVE); return true; }
        if (isInside(mouseX, mouseY, epX, headerY, 22, 14)) { toggleStartupSort(StartupSort.ENTRYPOINTS); return true; }
        if (isInside(mouseX, mouseY, regsX, headerY, 28, 14)) { toggleStartupSort(StartupSort.REGISTRATIONS); return true; }
        return false;
    }

    private void toggleStartupSort(StartupSort sort) {
        if (startupSort == sort) {
            startupSortDescending = !startupSortDescending;
        } else {
            startupSort = sort;
            startupSortDescending = true;
        }
    }

    private String findTaskRowAt(double mouseX, double mouseY) {
        if (activeTab != 0) {
            return null;
        }
        AttributionListLayout layout = getTaskListLayout();
        return findRowAt(mouseX, mouseY, layout.listY(), layout.listWidth(), getTaskRows(), ATTRIBUTION_ROW_HEIGHT);
    }

    private String findGpuRowAt(double mouseX, double mouseY) {
        if (activeTab != 1) {
            return null;
        }
        AttributionListLayout layout = getGpuListLayout();
        return findRowAt(mouseX, mouseY, layout.listY(), layout.listWidth(), getGpuRows(), ATTRIBUTION_ROW_HEIGHT);
    }

    private String findMemoryRowAt(double mouseX, double mouseY) {
        if (activeTab != 4) {
            return null;
        }
        MemoryListLayout layout = getMemoryListLayout();
        if (!isInside(mouseX, mouseY, 0, layout.listY(), layout.tableWidth(), layout.listHeight())) {
            return null;
        }
        return findRowAt(mouseX, mouseY, layout.listY(), layout.tableWidth(), getMemoryRows(), ATTRIBUTION_ROW_HEIGHT);
    }

    private long findThreadRowAt(double mouseX, double mouseY) {
        if (activeTab != 10) {
            return -1L;
        }
        AttributionListLayout layout = getThreadListLayout();
        int rowY = layout.listY() - scrollOffset;
        for (SystemMetricsProfiler.ThreadDrilldown thread : getThreadRows()) {
            if (isInside(mouseX, mouseY, 0, rowY, layout.listWidth(), ATTRIBUTION_ROW_HEIGHT)) {
                return thread.threadId();
            }
            rowY += ATTRIBUTION_ROW_HEIGHT;
        }
        return -1L;
    }

    private boolean handleThreadToolbarClick(double mouseX, double mouseY) {
        AttributionListLayout layout = getThreadListLayout();
        int x = PADDING;
        int y = layout.headerY() - 22;
        if (isInside(mouseX, mouseY, x, y, 70, 16)) { toggleThreadSort(ThreadSort.CPU); return true; }
        if (isInside(mouseX, mouseY, x + 74, y, 82, 16)) { toggleThreadSort(ThreadSort.ALLOC); return true; }
        if (isInside(mouseX, mouseY, x + 160, y, 86, 16)) { toggleThreadSort(ThreadSort.BLOCKED); return true; }
        if (isInside(mouseX, mouseY, x + 250, y, 82, 16)) { toggleThreadSort(ThreadSort.WAITED); return true; }
        if (isInside(mouseX, mouseY, x + 336, y, 76, 16)) { toggleThreadSort(ThreadSort.NAME); return true; }
        if (isInside(mouseX, mouseY, x + 418, y, 86, 16)) {
            threadFreeze = !threadFreeze;
            frozenThreadRows = threadFreeze ? new ArrayList<>(snapshot.systemMetrics().threadDrilldown()) : List.of();
            if (threadFreeze && selectedThreadId < 0L && !frozenThreadRows.isEmpty()) {
                selectedThreadId = frozenThreadRows.getFirst().threadId();
            }
            return true;
        }
        return false;
    }

    private void toggleThreadSort(ThreadSort sort) {
        if (threadSort == sort) {
            threadSortDescending = !threadSortDescending;
        } else {
            threadSort = sort;
            threadSortDescending = true;
        }
    }

    private String findSharedFamilyAt(double mouseX, double mouseY) {

        Map<String, Long> sharedFamilies = snapshot.sharedMemoryFamilies();
        if (activeTab != 4 || sharedFamilies.isEmpty()) {
            return null;
        }
        int sharedPanelW = Math.min(280, Math.max(220, getScreenWidth() / 4));
        int panelX = getScreenWidth() - sharedPanelW;
        int rowY = getContentY() + PADDING + 118 - scrollOffset;
        for (String family : sharedFamilies.keySet()) {
            if (isInside(mouseX, mouseY, panelX, rowY - 2, sharedPanelW, 12)) {
                return family;
            }
            rowY += 12;
        }
        return null;
    }

    private ChunkPos findLagMapChunkAt(double mouseX, double mouseY) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || activeTab != 9 || worldMiniTab != WorldMiniTab.LAG_MAP) {
            return null;
        }

        LagMapLayout layout = lastRenderedLagMapLayout != null
                ? lastRenderedLagMapLayout
                : getLagMapLayout(getContentY(), getScreenWidth(), getScreenHeight() - getContentY() - PADDING);
        ChunkPos playerChunk = client.player.getChunkPos();
        for (int dz = -layout.radius(); dz <= layout.radius(); dz++) {
            for (int dx = -layout.radius(); dx <= layout.radius(); dx++) {
                int px = layout.left() + (dx + layout.radius()) * layout.cell();
                int py = layout.mapTop() + (dz + layout.radius()) * layout.cell();
                if (mouseX >= px && mouseX < px + layout.cell() && mouseY >= py && mouseY < py + layout.cell()) {
                    return new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
                }
            }
        }
        return null;
    }

    private String findRowAt(double mouseX, double mouseY, int startY, int width, List<String> rows, int rowHeight) {
        int rowY = startY - scrollOffset;
        for (String row : rows) {
            if (isInside(mouseX, mouseY, 0, rowY, width, rowHeight)) {
                return row;
            }
            rowY += rowHeight;
        }
        return null;
    }

    private AttributionListLayout getTaskListLayout() {
        int detailW = Math.min(420, Math.max(320, getScreenWidth() / 3));
        int listW = getScreenWidth() - detailW - PADDING;
        int top = getContentY() + PADDING;
        String description = taskEffectiveView
                ? "Effective CPU share by mod from rolling sampled stack windows. Shared/framework work is folded into concrete mods for comparison."
                : "Raw CPU ownership by mod from rolling sampled stack windows. Shared/framework buckets stay separate until you switch back to Effective view.";
        int descriptionBottomY = top + measureWrappedHeight(Math.max(260, listW - 16), description);
        int controlsY = descriptionBottomY + 26;
        int headerY = controlsY + 42;
        int listY = headerY + 16;
        int listH = getScreenHeight() - getContentY() - PADDING - (listY - getContentY());
        return new AttributionListLayout(listW, headerY, listY, listH);
    }

    private AttributionListLayout getGpuListLayout() {
        int detailW = Math.min(420, Math.max(320, getScreenWidth() / 3));
        int listW = getScreenWidth() - detailW - PADDING;
        int top = getContentY() + PADDING;
        String description = gpuEffectiveView
                ? "Estimated GPU share by tagged render phases with shared render work folded into concrete mods."
                : "Raw GPU ownership by tagged render phases. Shared render buckets stay separate until you switch back to Effective view.";
        int descriptionBottomY = top + measureWrappedHeight(Math.max(260, listW - 16), description);
        int controlsY = descriptionBottomY + 26;
        int headerY = controlsY + 42;
        int listY = headerY + 16;
        int listH = getScreenHeight() - getContentY() - PADDING - (listY - getContentY());
        return new AttributionListLayout(listW, headerY, listY, listH);
    }

    AttributionListLayout getThreadListLayout() {
        int detailW = Math.min(420, Math.max(320, getScreenWidth() / 3));
        int listW = getScreenWidth() - detailW - PADDING;
        int top = getContentY() + PADDING;
        int descriptionBottomY = top + measureWrappedHeight(Math.max(260, listW - 16), "Measured live thread CPU/allocation snapshots with sampled owner and confidence. Use this when a mod row is really a thread question.");
        int headerY = descriptionBottomY + 52;
        int listY = headerY + 16;
        int listH = getScreenHeight() - getContentY() - PADDING - (listY - getContentY());
        return new AttributionListLayout(listW, headerY, listY, listH);
    }

    private MemoryListLayout getMemoryListLayout() {
        int sharedPanelW = snapshot.sharedMemoryFamilies().isEmpty() ? 0 : Math.min(280, Math.max(220, getScreenWidth() / 4));
        int detailH = 116;
        int panelGap = sharedPanelW > 0 ? PADDING : 0;
        int tableW = getScreenWidth() - sharedPanelW - panelGap;
        int top = getContentY() + PADDING;
        String description = memoryEffectiveView
                ? "Effective live heap by mod with shared/runtime buckets folded into concrete mods for comparison. Updated asynchronously."
                : "Raw live heap by owner/class family. Shared/runtime buckets stay separate until you switch back to Effective view.";
        int descriptionBottomY = top + measureWrappedHeight(Math.max(260, tableW - 16), description);
        int controlsTopY = descriptionBottomY + 26;
        int controlsY = controlsTopY + 42;
        int barY = controlsY + 24;
        int headerY = barY + 82;
        int listY = headerY + 16;
        int listH = getScreenHeight() - getContentY() - PADDING - (listY - getContentY()) - detailH;
        return new MemoryListLayout(tableW, controlsY, headerY, listY, listH);
    }

    ProfilerManager.ProfilerSnapshot currentSnapshot() {
        return snapshot;
    }

    long currentSelectedThreadId() {
        return selectedThreadId;
    }

    void setCurrentSelectedThreadId(long threadId) {
        selectedThreadId = threadId;
    }

    String currentGlobalSearch() {
        return globalSearch;
    }

    int currentScrollOffset() {
        return scrollOffset;
    }

    boolean isThreadSortCpu() {
        return threadSort == ThreadSort.CPU;
    }

    boolean isThreadSortAlloc() {
        return threadSort == ThreadSort.ALLOC;
    }

    boolean isThreadSortBlocked() {
        return threadSort == ThreadSort.BLOCKED;
    }

    boolean isThreadSortWaited() {
        return threadSort == ThreadSort.WAITED;
    }

    boolean isThreadSortName() {
        return threadSort == ThreadSort.NAME;
    }

    String currentThreadSortLabel() {
        return threadSort.name().toLowerCase(Locale.ROOT);
    }

    boolean isThreadFreezeActive() {
        return threadFreeze;
    }

    SystemMiniTab currentSystemMiniTab() {
        return systemMiniTab;
    }

    GraphMetricTab currentCpuGraphMetricTab() {
        return cpuGraphMetricTab;
    }

    GraphMetricTab currentGpuGraphMetricTab() {
        return gpuGraphMetricTab;
    }

    TextRenderer uiTextRenderer() {
        return textRenderer;
    }


    public static boolean isProfilingActive() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null && client.currentScreen instanceof TaskManagerScreen;
    }

    public static boolean isLiveMetricsActive() {
        return ProfilerManager.getInstance().shouldCollectFrameMetrics();
    }

    public static boolean isMemoryTabActive(MinecraftClient client) {
        return client != null && client.currentScreen instanceof TaskManagerScreen screen && screen.activeTab == 4;
    }

    private String formatMode(ProfilerManager.CaptureMode mode) {
        return mode == null ? "Unknown" : mode.name().replace('_', ' ');
    }

    String cpuStatusText(boolean ready, long samples, long ageMillis) {
        if (!ready) {
            return "Warming up | " + formatCount(samples) + " samples";
        }
        return "Loaded | " + formatCount(samples) + " samples | updated " + formatDuration(ageMillis) + " ago";
    }

    int getCpuStatusColor(boolean ready) {
        return ready ? ACCENT_GREEN : ACCENT_YELLOW;
    }

    int getGpuStatusColor(boolean ready) {
        return ready ? ACCENT_GREEN : ACCENT_YELLOW;
    }

    void renderStripedRowVariable(DrawContext ctx, int x, int width, int rowY, int rowHeight, int rowIdx, int mouseX, int mouseY) {
        if (rowIdx % 2 == 0) {
            ctx.fill(x, rowY, x + width, rowY + rowHeight, ROW_ALT);
        }
        if (mouseX >= x && mouseX < x + width && mouseY >= rowY && mouseY < rowY + rowHeight) {
            ctx.fill(x, rowY, x + width, rowY + rowHeight, 0x22FFFFFF);
        }
    }

    LagMapLayout getLagMapLayout(int contentY, int contentW, int contentH) {
        int left = PADDING;
        int top = getFullPageScrollTop(contentY);
        top = renderSectionHeaderOffset(top, true);
        int miniTabY = top;
        int summaryY = miniTabY + 24;
        int mapRenderY = summaryY + 14;
        int mapWidth = Math.min(260, contentW - 24);
        int mapHeight = Math.min(260, contentH - 32);
        int radius = 4;
        int cell = Math.max(12, Math.min(20, Math.min(mapWidth, mapHeight - 18) / ((radius * 2) + 1)));
        int mapTop = mapRenderY + 14;
        return new LagMapLayout(left, miniTabY, summaryY, mapRenderY, mapWidth, mapHeight, cell, radius, mapTop);
    }

    private int renderSectionHeaderOffset(int y, boolean hasSubtitle) {
        return hasSubtitle ? y + 28 : y + 16;
    }

    void drawSettingRow(DrawContext ctx, int x, int y, int width, String label, String value, int mouseX, int mouseY) {
        if (isInside(mouseX, mouseY, x - 4, y - 2, width + 8, 16)) {
            ctx.fill(x - 4, y - 2, x + width + 4, y + 14, 0x1AFFFFFF);
        }
        drawMetricRow(ctx, x, y, width, label, value);
    }


    void drawSettingRowWithTooltip(DrawContext ctx, int x, int y, int width, String label, String value, int mouseX, int mouseY, String tooltip) {
        drawSettingRow(ctx, x, y, width, label, value, mouseX, mouseY);
        addTooltip(x - 4, y - 2, width + 8, 16, tooltip);
    }

    void drawSliderSetting(DrawContext ctx, int x, int y, int width, String label, int percent, int mouseX, int mouseY) {
        if (isInside(mouseX, mouseY, x - 4, y - 2, width + 8, 20)) {
            ctx.fill(x - 4, y - 2, x + width + 4, y + 18, 0x1AFFFFFF);
        }
        ctx.drawText(textRenderer, label, x, y, TEXT_DIM, false);
        String value = percent + "%";
        ctx.drawText(textRenderer, value, x + width - textRenderer.getWidth(value), y, TEXT_PRIMARY, false);
        SliderLayout slider = getHudTransparencySliderLayout(x, y, width);
        ctx.fill(slider.x(), slider.y(), slider.x() + slider.width(), slider.y() + slider.height(), 0x332A2A2A);
        ctx.fill(slider.x(), slider.y(), slider.x() + slider.width(), slider.y() + 1, PANEL_OUTLINE);
        ctx.fill(slider.x(), slider.y() + slider.height() - 1, slider.x() + slider.width(), slider.y() + slider.height(), PANEL_OUTLINE);
        int filledWidth = Math.max(6, slider.width() * percent / 100);
        ctx.fill(slider.x(), slider.y(), slider.x() + filledWidth, slider.y() + slider.height(), ACCENT_GREEN);
        int knobX = slider.x() + Math.max(0, Math.min(slider.width() - 6, filledWidth - 3));
        ctx.fill(knobX, slider.y() - 2, knobX + 6, slider.y() + slider.height() + 2, TEXT_PRIMARY);
    }

    private SliderLayout getHudTransparencySliderLayout(int x, int y, int width) {
        int sliderWidth = Math.min(140, Math.max(96, width / 4));
        int sliderX = x + width - sliderWidth;
        int sliderY = y + 10;
        return new SliderLayout(sliderX, sliderY, sliderWidth, 6);
    }

    private boolean handleHudTransparencySliderClick(double mouseX, double mouseY, int x, int y, int width) {
        SliderLayout slider = getHudTransparencySliderLayout(x, y, width);
        if (!isInside(mouseX, mouseY, slider.x(), slider.y() - 3, slider.width(), slider.height() + 6)) {
            return false;
        }
        draggingHudTransparency = true;
        updateHudTransparencyFromMouse(mouseX, slider);
        return true;
    }

    private void updateHudTransparencyFromMouse(double mouseX, SliderLayout slider) {
        double ratio = Math.max(0.0, Math.min(1.0, (mouseX - slider.x()) / Math.max(1.0, slider.width())));
        int percent = (int) Math.round(10 + (ratio * 90.0));
        ConfigManager.setHudTransparencyPercent(percent);
    }

    void renderStripedRow(DrawContext ctx, int x, int width, int rowY, int rowIdx, int mouseX, int mouseY) {
        if (rowIdx % 2 == 0) {
            ctx.fill(x, rowY, x + width, rowY + ROW_HEIGHT, ROW_ALT);
        }
        if (mouseX >= x && mouseX < x + width && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) {
            ctx.fill(x, rowY, x + width, rowY + ROW_HEIGHT, 0x22FFFFFF);
        }
    }

    void renderConfidenceChip(DrawContext ctx, int x, int y, String confidence) {
        int width = confidenceChipWidth(confidence);
        int fill = switch (confidence) {
            case "Measured" -> 0x334CAF50;
            case "Mixed" -> 0x33FFB300;
            default -> 0x33FF6666;
        };
        int border = switch (confidence) {
            case "Measured" -> ACCENT_GREEN;
            case "Mixed" -> ACCENT_YELLOW;
            default -> ACCENT_RED;
        };
        ctx.fill(x, y, x + width, y + 12, fill);
        ctx.fill(x, y, x + width, y + 1, border);
        ctx.fill(x, y + 11, x + width, y + 12, border);
        ctx.drawText(textRenderer, confidence, x + 4, y + 2, TEXT_PRIMARY, false);
    }

    int confidenceChipWidth(String confidence) {
        return textRenderer.getWidth(confidence == null ? "Unknown" : confidence) + 8;
    }

    int firstVisibleMetricX(int fallback, int firstX, boolean firstVisible, int secondX, boolean secondVisible, int thirdX, boolean thirdVisible) {
        int visibleX = fallback;
        if (firstVisible) {
            visibleX = Math.min(visibleX, firstX - 8);
        }
        if (secondVisible) {
            visibleX = Math.min(visibleX, secondX - 8);
        }
        if (thirdVisible) {
            visibleX = Math.min(visibleX, thirdX - 8);
        }
        return visibleX;
    }

    int firstVisibleMetricX(int fallback, int firstX, boolean firstVisible, int secondX, boolean secondVisible, int thirdX, boolean thirdVisible, int fourthX, boolean fourthVisible) {
        return firstVisibleMetricX(firstVisibleMetricX(fallback, firstX, firstVisible, secondX, secondVisible, thirdX, thirdVisible), fourthX, fourthVisible, Integer.MAX_VALUE, false, Integer.MAX_VALUE, false);
    }
    boolean hasTaskFilter() {
        return !tasksSearch.isBlank() || taskSort != TaskSort.CPU || !taskSortDescending || !taskEffectiveView || taskShowSharedRows;
    }

    boolean hasGpuFilter() {
        return !gpuSearch.isBlank() || gpuSort != GpuSort.EST_GPU || !gpuSortDescending || !gpuEffectiveView || gpuShowSharedRows;
    }

    boolean hasMemoryFilter() {
        return !memorySearch.isBlank() || memorySort != MemorySort.MEMORY_MB || !memorySortDescending || !memoryEffectiveView || memoryShowSharedRows;
    }

    boolean hasStartupFilter() {
        return !startupSearch.isBlank() || startupSort != StartupSort.ACTIVE || !startupSortDescending;
    }

    private void resetTasksTable() {
        tasksSearch = "";
        taskSort = TaskSort.CPU;
        taskSortDescending = true;
        taskEffectiveView = true;
        taskShowSharedRows = false;
        focusedSearchTable = null;
    }

    private void resetGpuTable() {
        gpuSearch = "";
        gpuSort = GpuSort.EST_GPU;
        gpuSortDescending = true;
        gpuEffectiveView = true;
        gpuShowSharedRows = false;
        focusedSearchTable = null;
    }

    private void resetStartupTable() {
        startupSearch = "";
        startupSort = StartupSort.ACTIVE;
        startupSortDescending = true;
        startupSearchFocused = false;
    }

    private void resetMemoryTable() {
        memorySearch = "";
        memorySort = MemorySort.MEMORY_MB;
        memorySortDescending = true;
        memoryEffectiveView = true;
        memoryShowSharedRows = false;
        focusedSearchTable = null;
    }

    private String findingKey(ProfilerManager.RuleFinding finding) {
        return finding.category() + "|" + finding.message();
    }

    void renderResetButton(DrawContext ctx, int x, int y, int width, int height, boolean active) {
        ctx.fill(x, y, x + width, y + height, active ? 0x223A3A3A : 0x14141414);
        ctx.fill(x, y, x + width, y + 1, PANEL_OUTLINE);
        ctx.fill(x, y + height - 1, x + width, y + height, PANEL_OUTLINE);
        ctx.drawText(textRenderer, "Reset", x + 8, y + 4, active ? TEXT_PRIMARY : TEXT_DIM, false);
    }

    void renderSearchBox(DrawContext ctx, int x, int y, int width, int height, String placeholder, String value, boolean focused) {
        ctx.fill(x, y, x + width, y + height, focused ? 0x442A2A2A : 0x22181818);
        ctx.fill(x, y, x + width, y + 1, focused ? ACCENT_GREEN : BORDER_COLOR);
        ctx.fill(x, y + height - 1, x + width, y + height, BORDER_COLOR);
        String content = value == null || value.isBlank() ? placeholder : value + (focused ? "_" : "");
        int color = value == null || value.isBlank() ? TEXT_DIM : TEXT_PRIMARY;
        ctx.drawText(textRenderer, textRenderer.trimToWidth(content, width - 8), x + 4, y + 4, color, false);
    }

    void renderSortSummary(DrawContext ctx, int x, int y, String label, String value, int color) {
        ctx.drawText(textRenderer, label + ": " + value, x, y, color, false);
    }

    String headerLabel(String label, boolean active, boolean descending) {
        if (!active) {
            return label;
        }
        return label + (descending ? " v" : " ^");
    }

    String formatSort(Enum<?> sort, boolean descending) {
        return prettifyKey(sort.name()) + (descending ? " (desc)" : " (asc)");
    }

    void addTooltip(int x, int y, int width, int height, String text) {
        tooltipManager.add(x, y, width, height, text);
    }

    private void renderTooltipOverlay(DrawContext ctx, int mouseX, int mouseY) {
        tooltipManager.render(ctx, mouseX, mouseY, getScreenWidth(), getScreenHeight(), textRenderer, TEXT_PRIMARY, PANEL_OUTLINE);
    }

    String getDisplayName(String modId) {
        if (modId == null || modId.isBlank()) {
            return "Unknown";
        }
        if (modId.startsWith("shared/")) {
            return switch (modId) {
                case "shared/jvm" -> "Shared / JVM";
                case "shared/framework" -> "Shared / Framework";
                case "shared/render" -> "Shared / Render";
                case "shared/gpu-stall" -> "Shared / GPU Stall";
                default -> "Shared / " + cleanProfilerLabel(modId.substring("shared/".length()));
            };
        }
        if (modId.startsWith("runtime/")) {
            return "Runtime / " + cleanProfilerLabel(modId.substring("runtime/".length()));
        }
        return FabricLoader.getInstance().getModContainer(modId)
                .map(mod -> mod.getMetadata().getName())
                .orElseGet(() -> cleanProfilerLabel(modId));
    }

    int getHeatColor(double pct) {
        if (pct >= 50.0) return ACCENT_RED;
        if (pct >= 15.0) return ACCENT_YELLOW;
        return ACCENT_GREEN;
    }

    String formatCount(long value) {
        if (value >= 1_000_000L) return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0);
        if (value >= 1_000L) return String.format(Locale.ROOT, "%.1fk", value / 1_000.0);
        return Long.toString(value);
    }

    String memoryStatusText(long ageMillis) {
        if (ageMillis == Long.MAX_VALUE) return "No memory sample yet";
        return "Updated " + formatDuration(ageMillis) + " ago";
    }

    void renderSharedFamiliesPanel(DrawContext ctx, int x, int y, int width, int height, Map<String, Long> sharedFamilies) {
        drawInsetPanel(ctx, x, y, width, height);
        ctx.drawText(textRenderer, "Shared family detail", x + 8, y + 8, TEXT_PRIMARY, false);
        int rowY = y + 20;
        for (Map.Entry<String, Long> entry : sharedFamilies.entrySet()) {
            String label = textRenderer.trimToWidth(cleanProfilerLabel(entry.getKey()), Math.max(80, width - 50));
            ctx.drawText(textRenderer, label, x + 6, rowY, TEXT_DIM, false);
            String value = formatBytesMb(entry.getValue());
            ctx.drawText(textRenderer, value, x + width - 6 - textRenderer.getWidth(value), rowY, TEXT_PRIMARY, false);
            rowY += 12;
            if (rowY > y + height - 12) break;
        }
    }

    void renderSharedFamilyDetail(DrawContext ctx, int x, int y, int width, int height, Map<String, Long> classes) {
        drawInsetPanel(ctx, x, y, width, height);
        ctx.drawText(textRenderer, "Shared family classes", x + 8, y + 8, TEXT_PRIMARY, false);
        int rowY = y + 20;
        for (Map.Entry<String, Long> entry : classes.entrySet()) {
            String label = textRenderer.trimToWidth(cleanProfilerLabel(entry.getKey()), Math.max(80, width - 50));
            ctx.drawText(textRenderer, label, x + 6, rowY, TEXT_DIM, false);
            String value = formatBytesMb(entry.getValue());
            ctx.drawText(textRenderer, value, x + width - 6 - textRenderer.getWidth(value), rowY, TEXT_PRIMARY, false);
            rowY += 12;
            if (rowY > y + height - 12) break;
        }
    }

    void drawMetricRow(DrawContext ctx, int x, int y, int width, String label, String value) {
        ctx.drawText(textRenderer, label, x, y, TEXT_DIM, false);
        String shown = textRenderer.trimToWidth(value, Math.max(80, width - 120));

        int color = TEXT_PRIMARY;
        if (shown.equalsIgnoreCase("On") || shown.equalsIgnoreCase("Yes")) {
            color = ACCENT_GREEN;
        } else if (shown.equalsIgnoreCase("Off") || shown.equalsIgnoreCase("No")) {
            color = ACCENT_RED;
        }

        ctx.drawText(textRenderer, shown, x + width - textRenderer.getWidth(shown), y, color, false);
    }

    String formatBytesMb(long bytes) {
        if (bytes < 0) return "N/A";
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private String formatPercent(double value) {
        if (value < 0 || !Double.isFinite(value)) return "N/A";
        return String.format(Locale.ROOT, "%.1f%%", value);
    }

    String formatTemperature(double value) {
        if (value < 0 || !Double.isFinite(value)) return "N/A";
        return String.format(Locale.ROOT, "%.1f C", value);
    }

    String formatPercentWithTrend(double value, double deltaPerSecond) {
        String base = formatPercent(value);
        if ("N/A".equals(base)) {
            return base;
        }
        return base + " (" + formatSignedRate(deltaPerSecond, "%/s") + ")";
    }

    String formatTemperatureWithTrend(double value, double deltaPerSecond) {
        String base = formatTemperature(value);
        if ("N/A".equals(base)) {
            return base;
        }
        return base + " (" + formatSignedRate(deltaPerSecond, "C/s") + ")";
    }

    String formatCpuGpuSummary(SystemMetricsProfiler.Snapshot system, boolean cpu) {
        double load = cpu ? system.cpuCoreLoadPercent() : system.gpuCoreLoadPercent();
        double temp = cpu ? system.cpuTemperatureC() : system.gpuTemperatureC();
        double loadDelta = cpu ? system.cpuLoadChangePerSecond() : system.gpuLoadChangePerSecond();
        double tempDelta = cpu ? system.cpuTemperatureChangePerSecond() : system.gpuTemperatureChangePerSecond();
        String loadText = formatPercent(load);
        if (!cpu) {
            String rateText = TelemetryTextFormatter.formatSignedRate(loadDelta, "%/s");
            return loadText + " / " + TelemetryTextFormatter.formatGpuTemperatureCompact(system) + " (" + rateText + ")";
        }
        String tempText = formatTemperature(temp);
        String loadRate = formatSignedRate(loadDelta, "%/s");
        if ("N/A".equals(tempText)) {
            return loadText + " (" + loadRate + ")";
        }
        return loadText + " / " + tempText + " (" + loadRate + " / " + formatSignedRate(tempDelta, "C/s") + ")";
    }

    private String formatSignedRate(double value, String units) {
        if (!Double.isFinite(value)) {
            return "~0 " + units;
        }
        if (Math.abs(value) < 0.05) {
            return "~0 " + units;
        }
        return String.format(Locale.ROOT, "%+.1f %s", value, units);
    }

    String formatCpuInfo() {
        int logicalCores = Runtime.getRuntime().availableProcessors();
        return "CPU: " + HardwareInfoResolver.getCpuDisplayName() + " | " + logicalCores + " logical cores";
    }

    int getCpuGraphColor() {
        return ConfigManager.getCpuGraphColor();
    }

    int getGpuGraphColor() {
        return ConfigManager.getGpuGraphColor();
    }

    int getWorldEntityGraphColor() {
        return ConfigManager.getWorldEntityGraphColor();
    }

    int getWorldLoadedChunkGraphColor() {
        return ConfigManager.getWorldLoadedChunkGraphColor();
    }

    int getWorldRenderedChunkGraphColor() {
        return ConfigManager.getWorldRenderedChunkGraphColor();
    }

    int getMemoryGraphColor() {
        return 0xFF325C99;
    }

    String getColorSettingHex(ColorSetting setting) {
        return switch (setting) {
            case CPU -> ConfigManager.getCpuGraphColorHex();
            case GPU -> ConfigManager.getGpuGraphColorHex();
            case WORLD_ENTITIES -> ConfigManager.getWorldEntityGraphColorHex();
            case WORLD_CHUNKS_LOADED -> ConfigManager.getWorldLoadedChunkGraphColorHex();
            case WORLD_CHUNKS_RENDERED -> ConfigManager.getWorldRenderedChunkGraphColorHex();
        };
    }

    private void applyColorSetting(ColorSetting setting, String value) {
        switch (setting) {
            case CPU -> ConfigManager.setCpuGraphColorHex(value);
            case GPU -> ConfigManager.setGpuGraphColorHex(value);
            case WORLD_ENTITIES -> ConfigManager.setWorldEntityGraphColorHex(value);
            case WORLD_CHUNKS_LOADED -> ConfigManager.setWorldLoadedChunkGraphColorHex(value);
            case WORLD_CHUNKS_RENDERED -> ConfigManager.setWorldRenderedChunkGraphColorHex(value);
        }
    }

    private String normalizeColorEdit(String value) {
        if (value == null || value.isBlank()) return "#";
        String cleaned = value.strip().toUpperCase(Locale.ROOT).replace("#", "");
        if (cleaned.length() > 6) cleaned = cleaned.substring(0, 6);
        cleaned = cleaned.replaceAll("[^0-9A-F]", "");
        return "#" + cleaned;
    }

    String stutterBand(double score) {
        if (score < 5.0) return "Excellent";
        if (score < 10.0) return "Good";
        if (score < 20.0) return "Noticeable";
        if (score < 35.0) return "Bad";
        return "Severe";
    }

    int stutterBandColor(double score) {
        if (score < 5.0) return ACCENT_GREEN;
        if (score < 10.0) return INTEL_COLOR;
        if (score < 20.0) return ACCENT_YELLOW;
        if (score < 35.0) return 0xFFFF8844;
        return ACCENT_RED;
    }

    String formatBytesPerSecond(long value) {
        if (value < 0) return "N/A";
        if (value >= 1024L * 1024L) return String.format(Locale.ROOT, "%.2f MB/s", value / (1024.0 * 1024.0));
        if (value >= 1024L) return String.format(Locale.ROOT, "%.1f KB/s", value / 1024.0);
        return value + " B/s";
    }

    private String currentBottleneckSummary() {
        return ProfilerManager.getInstance().getCurrentBottleneckLabel();
    }

    double percentileGpuFrameLabel() {
        java.util.List<ProfilerManager.SessionPoint> points = new java.util.ArrayList<>(ProfilerManager.getInstance().getSessionHistory());
        if (points.isEmpty()) {
            return snapshot.renderPhases().values().stream().mapToLong(RenderPhaseProfiler.PhaseSnapshot::gpuNanos).sum() / 1_000_000.0;
        }
        java.util.List<Double> values = points.stream().map(ProfilerManager.SessionPoint::gpuFrameTimeMs).sorted().toList();
        int idx = Math.min(values.size() - 1, Math.max(0, (int) Math.ceil(values.size() * 0.95) - 1));
        return values.get(idx);
    }

    String formatFrameHistogram(Map<String, Double> buckets) {
        if (buckets == null || buckets.isEmpty()) {
            return "No frame histogram yet";
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (Map.Entry<String, Double> entry : buckets.entrySet()) {
            parts.add(entry.getKey() + ": " + String.format(Locale.ROOT, "%.0f%%", entry.getValue()));
        }
        return String.join(" | ", parts);
    }

    int getPreferredGraphWidth(int availableWidth) {
        return Math.max(220, Math.min(availableWidth - (PADDING * 2), 1000));
    }

    void renderMetricGraph(DrawContext ctx, int x, int y, int width, int height, long[] primary, long[] secondary, String title, String units, double spanSeconds) {
        int graphHeight = Math.max(96, height);
        renderSeriesGraph(ctx, x + PADDING, y, width - (PADDING * 2), graphHeight, toDoubleArray(primary), toDoubleArray(secondary), title, units, INTEL_COLOR, ACCENT_YELLOW, spanSeconds, true);
    }

    void renderSeriesGraph(DrawContext ctx, int x, int y, int width, int height, double[] primary, double[] secondary, String title, String units, int primaryColor, int secondaryColor, double spanSeconds) {
        renderSeriesGraph(ctx, x, y, width, height, primary, secondary, title, units, primaryColor, secondaryColor, spanSeconds, false);
    }

    void renderSeriesGraph(DrawContext ctx, int x, int y, int width, int height, double[] primary, double[] secondary, String title, String units, int primaryColor, int secondaryColor, double spanSeconds, boolean drawSmallerOnTop) {
        ctx.drawText(textRenderer, title + " (" + units + ")", x, y, TEXT_PRIMARY, false);
        int graphX = x;
        int graphY = y + 14;
        int graphWidth = Math.max(120, width);
        int graphHeight = Math.max(64, height - 30);
        int plotRightPadding = 74;
        int plotWidth = Math.max(72, graphWidth - plotRightPadding);
        int axisX = graphX + plotWidth + 8;
        int cardColor = 0x28101010;
        int borderColor = 0x44383838;
        int majorGridColor = 0x26FFFFFF;
        int minorGridColor = 0x12FFFFFF;
        ctx.fill(graphX - 3, graphY - 3, graphX + graphWidth + 3, graphY + graphHeight + 3, borderColor);
        ctx.fill(graphX - 2, graphY - 2, graphX + graphWidth + 2, graphY + graphHeight + 2, cardColor);

        double max = niceGraphMax(primary, secondary);
        double mid = max / 2.0;
        int midY = graphY + graphHeight / 2;
        int quarterY = graphY + graphHeight / 4;
        int threeQuarterY = graphY + (graphHeight * 3) / 4;

        ctx.fill(graphX, graphY, graphX + plotWidth, graphY + 1, majorGridColor);
        ctx.fill(graphX, midY, graphX + plotWidth, midY + 1, majorGridColor);
        ctx.fill(graphX, quarterY, graphX + plotWidth, quarterY + 1, minorGridColor);
        ctx.fill(graphX, threeQuarterY, graphX + plotWidth, threeQuarterY + 1, minorGridColor);
        ctx.fill(graphX, graphY + graphHeight - 1, graphX + plotWidth, graphY + graphHeight, majorGridColor);

        drawAxisLabel(ctx, axisX, graphY - 4, formatGraphValue(max, units));
        drawAxisLabel(ctx, axisX, midY - 4, formatGraphValue(mid, units));
        drawAxisLabel(ctx, axisX, graphY + graphHeight - 8, formatGraphValue(0.0, units));
        ctx.drawText(textRenderer, formatHistoryWindowLabel(spanSeconds), graphX, graphY + graphHeight + 4, TEXT_DIM, false);
        ctx.drawText(textRenderer, "now", graphX + plotWidth - textRenderer.getWidth("now"), graphY + graphHeight + 4, TEXT_DIM, false);

        if (secondary != null && secondary.length > 0) {
            drawOverlappingSeriesBars(ctx, graphX, graphY, plotWidth, graphHeight, primary, secondary, max, primaryColor, secondaryColor, drawSmallerOnTop);
        } else {
            drawSeriesBars(ctx, graphX, graphY, plotWidth, graphHeight, primary, max, primaryColor);
        }
        drawCurrentValueMarker(ctx, graphX, graphY, plotWidth, graphHeight, axisX, primary, max, primaryColor, units, 0);
        if (secondary != null && secondary.length > 0) {
            drawCurrentValueMarker(ctx, graphX, graphY, plotWidth, graphHeight, axisX, secondary, max, secondaryColor, units, 12);
        }
    }

    int renderGraphLegend(DrawContext ctx, int x, int y, String[] labels, int[] colors) {
        int currentX = x;
        int boxSize = 8;
        for (int i = 0; i < labels.length; i++) {
            int color = colors[i];
            ctx.fill(currentX, y + 2, currentX + boxSize, y + 2 + boxSize, color);
            ctx.fill(currentX, y + 2, currentX + boxSize, y + 3, 0x66FFFFFF);
            ctx.fill(currentX, y + boxSize + 1, currentX + boxSize, y + boxSize + 2, 0x44000000);
            ctx.drawText(textRenderer, labels[i], currentX + boxSize + 6, y, TEXT_DIM, false);
            currentX += boxSize + 6 + textRenderer.getWidth(labels[i]) + 14;
        }
        return 12;
    }

    void renderFixedScaleSeriesGraph(DrawContext ctx, int x, int y, int width, int height, double[] values, String title, String units, int color, double fixedMax, double spanSeconds) {
        renderFixedScaleSeriesGraph(ctx, x, y, width, height, values, null, title, units, color, 0, fixedMax, spanSeconds);
    }

    void renderGraphMetricTabs(DrawContext ctx, int x, int y, int width, GraphMetricTab activeMetricTab) {
        int loadWidth = 84;
        int temperatureWidth = 120;
        drawTopChip(ctx, x, y, loadWidth, 16, activeMetricTab == GraphMetricTab.LOAD);
        drawTopChip(ctx, x + loadWidth + 6, y, temperatureWidth, 16, activeMetricTab == GraphMetricTab.TEMPERATURE);
        ctx.drawText(textRenderer, "Load Graph", x + 12, y + 4, activeMetricTab == GraphMetricTab.LOAD ? TEXT_PRIMARY : TEXT_DIM, false);
        ctx.drawText(textRenderer, "Temperature Graph", x + loadWidth + 18, y + 4, activeMetricTab == GraphMetricTab.TEMPERATURE ? TEXT_PRIMARY : TEXT_DIM, false);
    }

    private void renderFixedScaleLineGraph(DrawContext ctx, int x, int y, int width, int height, double[] values, String title, String units, int color, double fixedMax, double spanSeconds) {
        ctx.drawText(textRenderer, title + " (" + units + ")", x, y, TEXT_PRIMARY, false);
        int graphX = x;
        int graphY = y + 14;
        int graphWidth = Math.max(120, width);
        int graphHeight = Math.max(64, height - 30);
        int plotRightPadding = 74;
        int plotWidth = Math.max(72, graphWidth - plotRightPadding);
        int axisX = graphX + plotWidth + 8;
        int cardColor = 0x28101010;
        int borderColor = 0x44383838;
        int majorGridColor = 0x26FFFFFF;
        int minorGridColor = 0x12FFFFFF;
        ctx.fill(graphX - 3, graphY - 3, graphX + graphWidth + 3, graphY + graphHeight + 3, borderColor);
        ctx.fill(graphX - 2, graphY - 2, graphX + graphWidth + 2, graphY + graphHeight + 2, cardColor);

        double max = Math.max(1.0, fixedMax);
        double mid = max / 2.0;
        int midY = graphY + graphHeight / 2;
        int quarterY = graphY + graphHeight / 4;
        int threeQuarterY = graphY + (graphHeight * 3) / 4;
        ctx.fill(graphX, graphY, graphX + plotWidth, graphY + 1, majorGridColor);
        ctx.fill(graphX, midY, graphX + plotWidth, midY + 1, majorGridColor);
        ctx.fill(graphX, quarterY, graphX + plotWidth, quarterY + 1, minorGridColor);
        ctx.fill(graphX, threeQuarterY, graphX + plotWidth, threeQuarterY + 1, minorGridColor);
        ctx.fill(graphX, graphY + graphHeight - 1, graphX + plotWidth, graphY + graphHeight, majorGridColor);

        drawAxisLabel(ctx, axisX, graphY - 4, formatGraphValue(max, units));
        drawAxisLabel(ctx, axisX, midY - 4, formatGraphValue(mid, units));
        drawAxisLabel(ctx, axisX, graphY + graphHeight - 8, formatGraphValue(0.0, units));
        ctx.drawText(textRenderer, formatHistoryWindowLabel(spanSeconds), graphX, graphY + graphHeight + 4, TEXT_DIM, false);
        ctx.drawText(textRenderer, "now", graphX + plotWidth - textRenderer.getWidth("now"), graphY + graphHeight + 4, TEXT_DIM, false);
        drawSeriesLine(ctx, graphX, graphY, plotWidth, graphHeight, values, max, color);
        drawCurrentValueMarker(ctx, graphX, graphY, plotWidth, graphHeight, axisX, values, max, color, units, 0);
    }

    void renderSensorSeriesGraph(DrawContext ctx, int x, int y, int width, int height, double[] values, String title, String units, int color, double fixedMax, double spanSeconds, boolean sensorAvailable, String unavailableLabel) {
        if (sensorAvailable) {
            renderFixedScaleLineGraph(ctx, x, y, width, height, sanitizeSeries(values), title, units, color, fixedMax, spanSeconds);
            return;
        }

        ctx.drawText(textRenderer, title + " (" + units + ")", x, y, TEXT_PRIMARY, false);
        int graphX = x;
        int graphY = y + 14;
        int graphWidth = Math.max(120, width);
        int graphHeight = Math.max(64, height - 30);
        int plotRightPadding = 74;
        int plotWidth = Math.max(72, graphWidth - plotRightPadding);
        int axisX = graphX + plotWidth + 8;
        int cardColor = 0x28101010;
        int borderColor = 0x44383838;
        ctx.fill(graphX - 3, graphY - 3, graphX + graphWidth + 3, graphY + graphHeight + 3, borderColor);
        ctx.fill(graphX - 2, graphY - 2, graphX + graphWidth + 2, graphY + graphHeight + 2, cardColor);
        drawAxisLabel(ctx, axisX, graphY - 4, formatGraphValue(fixedMax, units));
        drawAxisLabel(ctx, axisX, graphY + (graphHeight / 2) - 4, formatGraphValue(fixedMax / 2.0, units));
        drawAxisLabel(ctx, axisX, graphY + graphHeight - 8, formatGraphValue(0.0, units));
        String overlay = "Sensor is not available";
        int overlayX = graphX + Math.max(0, (plotWidth - textRenderer.getWidth(overlay)) / 2);
        int overlayY = graphY + Math.max(8, (graphHeight / 2) - 4);
        ctx.drawText(textRenderer, overlay, overlayX, overlayY, TEXT_DIM, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth(unavailableLabel, Math.max(80, graphWidth - 12)), x, graphY + graphHeight + 4, TEXT_DIM, false);
    }

    private boolean hasValidSeriesValue(double[] values) {
        if (values == null) {
            return false;
        }
        for (double value : values) {
            if (value >= 0.0 && Double.isFinite(value)) {
                return true;
            }
        }
        return false;
    }

    private double[] sanitizeSeries(double[] values) {
        if (values == null) {
            return new double[0];
        }
        double[] sanitized = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            sanitized[i] = values[i] >= 0.0 && Double.isFinite(values[i]) ? values[i] : 0.0;
        }
        return sanitized;
    }

    void renderFixedScaleSeriesGraph(DrawContext ctx, int x, int y, int width, int height, double[] primary, double[] secondary, String title, String units, int primaryColor, int secondaryColor, double fixedMax, double spanSeconds) {
        ctx.drawText(textRenderer, title + " (" + units + ")", x, y, TEXT_PRIMARY, false);
        int graphX = x;
        int graphY = y + 14;
        int graphWidth = Math.max(120, width);
        int graphHeight = Math.max(64, height - 30);
        int plotRightPadding = 74;
        int plotWidth = Math.max(72, graphWidth - plotRightPadding);
        int axisX = graphX + plotWidth + 8;
        int cardColor = 0x28101010;
        int borderColor = 0x44383838;
        int majorGridColor = 0x26FFFFFF;
        int minorGridColor = 0x12FFFFFF;
        ctx.fill(graphX - 3, graphY - 3, graphX + graphWidth + 3, graphY + graphHeight + 3, borderColor);
        ctx.fill(graphX - 2, graphY - 2, graphX + graphWidth + 2, graphY + graphHeight + 2, cardColor);

        double max = Math.max(1.0, fixedMax);
        double mid = max / 2.0;
        int midY = graphY + graphHeight / 2;
        int quarterY = graphY + graphHeight / 4;
        int threeQuarterY = graphY + (graphHeight * 3) / 4;
        ctx.fill(graphX, graphY, graphX + plotWidth, graphY + 1, majorGridColor);
        ctx.fill(graphX, midY, graphX + plotWidth, midY + 1, majorGridColor);
        ctx.fill(graphX, quarterY, graphX + plotWidth, quarterY + 1, minorGridColor);
        ctx.fill(graphX, threeQuarterY, graphX + plotWidth, threeQuarterY + 1, minorGridColor);
        ctx.fill(graphX, graphY + graphHeight - 1, graphX + plotWidth, graphY + graphHeight, majorGridColor);

        drawAxisLabel(ctx, axisX, graphY - 4, formatGraphValue(max, units));
        drawAxisLabel(ctx, axisX, midY - 4, formatGraphValue(mid, units));
        drawAxisLabel(ctx, axisX, graphY + graphHeight - 8, formatGraphValue(0.0, units));
        ctx.drawText(textRenderer, formatHistoryWindowLabel(spanSeconds), graphX, graphY + graphHeight + 4, TEXT_DIM, false);
        ctx.drawText(textRenderer, "now", graphX + plotWidth - textRenderer.getWidth("now"), graphY + graphHeight + 4, TEXT_DIM, false);
        drawSeriesBars(ctx, graphX, graphY, plotWidth, graphHeight, primary, max, primaryColor);
        if (secondary != null && secondary.length > 0) {
            drawSeriesBars(ctx, graphX, graphY, plotWidth, graphHeight, secondary, max, secondaryColor);
        }
        drawCurrentValueMarker(ctx, graphX, graphY, plotWidth, graphHeight, axisX, primary, max, primaryColor, units, 0);
        if (secondary != null && secondary.length > 0) {
            drawCurrentValueMarker(ctx, graphX, graphY, plotWidth, graphHeight, axisX, secondary, max, secondaryColor, units, 12);
        }
    }

    private void drawSeriesLine(DrawContext ctx, int graphX, int graphY, int graphWidth, int graphHeight, double[] values, double max, int color) {
        if (values == null || values.length == 0) {
            return;
        }
        int previousX = -1;
        int previousY = -1;
        for (int px = 0; px < graphWidth; px++) {
            int sampleIndex = Math.max(0, Math.min(values.length - 1, (int) Math.round(px * (values.length - 1) / (double) Math.max(1, graphWidth - 1))));
            double value = values[sampleIndex];
            int valueHeight = (int) Math.min(graphHeight - 1, Math.round((Math.max(0.0, value) / Math.max(1.0, max)) * (graphHeight - 1)));
            int currentX = graphX + px;
            int currentY = graphY + graphHeight - 1 - valueHeight;
            ctx.fill(currentX, currentY, currentX + 1, currentY + 1, color);
            if (previousX >= 0) {
                drawLineSegment(ctx, previousX, previousY, currentX, currentY, color);
            }
            previousX = currentX;
            previousY = currentY;
        }
    }

    private void drawLineSegment(DrawContext ctx, int x1, int y1, int x2, int y2, int color) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        if (steps <= 0) {
            ctx.fill(x1, y1, x1 + 1, y1 + 1, color);
            return;
        }
        for (int step = 0; step <= steps; step++) {
            int x = x1 + Math.round((x2 - x1) * (step / (float) steps));
            int y = y1 + Math.round((y2 - y1) * (step / (float) steps));
            ctx.fill(x, y, x + 1, y + 1, color);
        }
    }

    private void drawSeriesBars(DrawContext ctx, int graphX, int graphY, int graphWidth, int graphHeight, double[] values, double max, int color) {
        if (values == null || values.length == 0) {
            return;
        }
        for (int px = 0; px < graphWidth; px++) {
            double peak = peakSeriesValue(values, px, graphWidth);
            drawSeriesBarColumn(ctx, graphX, graphY, graphHeight, max, color, px, peak);
        }
    }

    private void drawOverlappingSeriesBars(DrawContext ctx, int graphX, int graphY, int graphWidth, int graphHeight, double[] primary, double[] secondary, double max, int primaryColor, int secondaryColor, boolean drawSmallerOnTop) {
        for (int px = 0; px < graphWidth; px++) {
            double primaryPeak = peakSeriesValue(primary, px, graphWidth);
            double secondaryPeak = peakSeriesValue(secondary, px, graphWidth);
            if (!drawSmallerOnTop || primaryPeak == secondaryPeak) {
                drawSeriesBarColumn(ctx, graphX, graphY, graphHeight, max, primaryColor, px, primaryPeak);
                drawSeriesBarColumn(ctx, graphX, graphY, graphHeight, max, secondaryColor, px, secondaryPeak);
                continue;
            }
            boolean primarySmaller = primaryPeak < secondaryPeak;
            drawSeriesBarColumn(ctx, graphX, graphY, graphHeight, max, primarySmaller ? secondaryColor : primaryColor, px, primarySmaller ? secondaryPeak : primaryPeak);
            drawSeriesBarColumn(ctx, graphX, graphY, graphHeight, max, primarySmaller ? primaryColor : secondaryColor, px, primarySmaller ? primaryPeak : secondaryPeak);
        }
    }

    private double peakSeriesValue(double[] values, int px, int graphWidth) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        int start = (int) Math.floor(px * values.length / (double) graphWidth);
        int end = (int) Math.floor((px + 1) * values.length / (double) graphWidth) - 1;
        if (end < start) {
            end = start;
        }
        start = Math.max(0, Math.min(values.length - 1, start));
        end = Math.max(0, Math.min(values.length - 1, end));
        double peak = 0.0;
        for (int i = start; i <= end; i++) {
            peak = Math.max(peak, values[i]);
        }
        return peak;
    }

    private void drawSeriesBarColumn(DrawContext ctx, int graphX, int graphY, int graphHeight, double max, int color, int px, double peak) {
        int valueHeight = (int) Math.min(graphHeight, Math.round((peak / Math.max(1.0, max)) * graphHeight));
        if (valueHeight <= 0) {
            return;
        }
        int barX = graphX + px;
        ctx.fill(barX, graphY + graphHeight - valueHeight, barX + 1, graphY + graphHeight, color);
    }

    private void drawCurrentValueMarker(DrawContext ctx, int graphX, int graphY, int plotWidth, int graphHeight, int axisX, double[] values, double max, int color, String units, int yOffset) {
        double currentValue = latestGraphValue(values);
        if (!Double.isFinite(currentValue)) {
            return;
        }
        int markerY = graphY + graphHeight - 1 - (int) Math.min(graphHeight - 1, Math.round((Math.max(0.0, currentValue) / Math.max(1.0, max)) * (graphHeight - 1)));
        markerY = Math.max(graphY, Math.min(graphY + graphHeight - 8, markerY + yOffset));
        int tickX = graphX + plotWidth - 4;
        ctx.fill(tickX, markerY, tickX + 8, markerY + 1, color);
        String label = formatGraphValue(currentValue, units);
        int labelY = Math.max(graphY - 4, Math.min(graphY + graphHeight - 8, markerY - 4));
        ctx.drawText(textRenderer, label, axisX, labelY, color, false);
    }

    private double latestGraphValue(double[] values) {
        if (values == null || values.length == 0) {
            return Double.NaN;
        }
        for (int i = values.length - 1; i >= 0; i--) {
            if (Double.isFinite(values[i]) && values[i] >= 0.0) {
                return values[i];
            }
        }
        return Double.NaN;
    }

    private void drawAxisLabel(DrawContext ctx, int x, int y, String text) {
        ctx.drawText(textRenderer, text, x, y, TEXT_DIM, false);
    }

    private double niceGraphMax(double[] primary, double[] secondary) {
        double rawMax = 0.0;
        if (primary != null) {
            for (double value : primary) {
                rawMax = Math.max(rawMax, value);
            }
        }
        if (secondary != null) {
            for (double value : secondary) {
                rawMax = Math.max(rawMax, value);
            }
        }
        if (rawMax <= 0.0) {
            return 1.0;
        }
        double magnitude = Math.pow(10.0, Math.floor(Math.log10(rawMax)));
        double normalized = rawMax / magnitude;
        double niceNormalized = normalized <= 1.0 ? 1.0 : normalized <= 2.0 ? 2.0 : normalized <= 5.0 ? 5.0 : 10.0;
        return niceNormalized * magnitude;
    }

    private String formatHistoryWindowLabel(double spanSeconds) {
        if (spanSeconds <= 0.0) {
            return "start";
        }
        return String.format(Locale.ROOT, "-%.1fs", spanSeconds);
    }

    private String formatGraphValue(double value, String units) {
        if ("B/s".equals(units)) {
            return formatBytesPerSecond(Math.round(value));
        }
        if (units == null || units.isBlank()) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units);
    }
    private String formatDuration(long millis) {
        if (millis == Long.MAX_VALUE) return "never";
        if (millis < 1000) return millis + "ms";
        return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
    }

    private void renderFlamegraph(DrawContext ctx, int x, int y, int w, int h) {
        FlamegraphTabRenderer.render(this, ctx, x, y, w, h);
    }

    private void renderTimeline(DrawContext ctx, int x, int y, int w, int h) {
        TimelineTabRenderer.render(this, ctx, x, y, w, h);
    }

    private ModalLayout getCenteredModalLayout(int screenWidth, int screenHeight, int preferredWidth, int preferredHeight) {
        int width = Math.max(280, Math.min(preferredWidth, screenWidth - 24));
        int height = Math.max(220, Math.min(preferredHeight, screenHeight - 24));
        int x = Math.max(12, (screenWidth - width) / 2);
        int y = Math.max(20, (screenHeight - height) / 2);
        return new ModalLayout(x, y, width, height);
    }

    void renderAttributionHelpOverlay(DrawContext ctx, int screenWidth, int screenHeight, int mouseX, int mouseY) {
        ctx.fill(0, 0, screenWidth, screenHeight, 0xAA000000);
        ModalLayout modal = getCenteredModalLayout(screenWidth, screenHeight, Math.min(900, screenWidth - 32), Math.min(560, screenHeight - 48));
        drawInsetPanel(ctx, modal.x(), modal.y(), modal.width(), modal.height());
        drawTopChip(ctx, modal.x() + modal.width() - 62, modal.y() + 10, 52, 16, false);
        ctx.drawText(textRenderer, "Close", modal.x() + modal.width() - 47, modal.y() + 14, TEXT_DIM, false);
        ctx.drawText(textRenderer, "Attribution Guide", modal.x() + 12, modal.y() + 12, TEXT_PRIMARY, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth("A quick guide to what is measured directly, what is inferred, and what is redistributed for readability.", modal.width() - 90), modal.x() + 12, modal.y() + 24, TEXT_DIM, false);
        int contentX = modal.x() + 12;
        int contentY = modal.y() + 48;
        int contentW = modal.width() - 24;
        ctx.enableScissor(modal.x() + 6, modal.y() + 40, modal.x() + modal.width() - 6, modal.y() + modal.height() - 6);
        contentY = renderWrappedText(ctx, contentX, contentY, contentW, "Measured: data we directly observed, like sampled CPU stacks, JVM class histograms, GPU timer-query phase timings, or hardware counters.", TEXT_PRIMARY) + 8;
        contentY = renderWrappedText(ctx, contentX, contentY, contentW, "Inferred: data we can explain from nearby evidence but do not directly own with perfect certainty, like shared/framework work or render-thread influence.", TEXT_PRIMARY) + 8;
        contentY = renderWrappedText(ctx, contentX, contentY, contentW, "Estimated: GPU per-mod ownership is still an estimate because the driver exposes total timings more readily than exact per-mod boundaries. Tagged phase owners reduce the guesswork, but some render work still lands in shared/render.", TEXT_PRIMARY) + 12;
        contentY = renderWrappedText(ctx, contentX, contentY, contentW, "Raw view keeps true-owned and shared/runtime buckets separate. Effective view redistributes shared/runtime buckets back into concrete mods so the main ranking answers the practical question: which mod is effectively carrying the most load right now?", TEXT_PRIMARY) + 12;
        contentY = renderWrappedText(ctx, contentX, contentY, contentW, "Shared buckets are not errors. Shared / JVM, Shared / Framework, Shared / Render, and Runtime rows are where work genuinely crosses mod boundaries or belongs to the platform more than a single mod.", TEXT_PRIMARY) + 12;
        contentY = renderWrappedText(ctx, contentX, contentY, contentW, "Use Raw when you want honesty about direct ownership. Use Effective when you want faster triage. The export now preserves both so reports match what you saw on screen.", ACCENT_YELLOW);
        ctx.disableScissor();
    }

    void renderRowDrilldownOverlay(DrawContext ctx, int screenWidth, int screenHeight, int mouseX, int mouseY) {
        String modId = getActiveDrilldownModId();
        if (modId == null) {
            activeDrilldownTable = null;
            return;
        }
        ctx.fill(0, 0, screenWidth, screenHeight, 0xAA000000);
        ModalLayout modal = getCenteredModalLayout(screenWidth, screenHeight, Math.min(920, screenWidth - 32), Math.min(620, screenHeight - 48));
        drawInsetPanel(ctx, modal.x(), modal.y(), modal.width(), modal.height());
        drawTopChip(ctx, modal.x() + modal.width() - 62, modal.y() + 10, 52, 16, false);
        ctx.drawText(textRenderer, "Close", modal.x() + modal.width() - 47, modal.y() + 14, TEXT_DIM, false);
        String title = switch (activeDrilldownTable) {
            case TASKS -> "CPU Row Drilldown";
            case GPU -> "GPU Row Drilldown";
            case MEMORY -> "Memory Row Drilldown";
        };
        ctx.drawText(textRenderer, title, modal.x() + 12, modal.y() + 12, TEXT_PRIMARY, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth(getDisplayName(modId), modal.width() - 90), modal.x() + 12, modal.y() + 24, ACCENT_YELLOW, false);
        if (activeDrilldownTable == TableId.TASKS) {
            renderCpuDrilldownContent(ctx, modal, modId);
        } else if (activeDrilldownTable == TableId.GPU) {
            renderGpuDrilldownContent(ctx, modal, modId);
        } else if (activeDrilldownTable == TableId.MEMORY) {
            renderMemoryDrilldownContent(ctx, modal, modId);
        }
    }

    private String getActiveDrilldownModId() {
        if (activeDrilldownTable == null) {
            return null;
        }
        return switch (activeDrilldownTable) {
            case TASKS -> selectedTaskMod;
            case GPU -> selectedGpuMod;
            case MEMORY -> selectedMemoryMod;
        };
    }

    private void renderCpuDrilldownContent(DrawContext ctx, ModalLayout modal, String modId) {
        Map<String, CpuSamplingProfiler.Snapshot> rawCpu = snapshot.cpuMods();
        EffectiveCpuAttribution effectiveCpu = effectiveCpuAttribution();
        CpuSamplingProfiler.Snapshot rawSnapshot = rawCpu.getOrDefault(modId, new CpuSamplingProfiler.Snapshot(0, 0, 0));
        CpuSamplingProfiler.Snapshot effectiveSnapshot = effectiveCpu.displaySnapshots().getOrDefault(modId, rawSnapshot);
        CpuSamplingProfiler.Snapshot displaySnapshot = taskEffectiveView ? effectiveSnapshot : rawSnapshot;
        CpuSamplingProfiler.DetailSnapshot detail = snapshot.cpuDetails().get(modId);
        long redistributedSamples = effectiveCpu.redistributedSamplesByMod().getOrDefault(modId, 0L);
        double[] trend = buildTrendSeries(TableId.TASKS, modId, taskEffectiveView);
        List<ProfilerManager.SessionPoint> recentPoints = getRecentSessionPoints();
        int contentX = modal.x() + 12;
        int contentY = modal.y() + 46;
        int contentW = modal.width() - 24;
        ctx.enableScissor(modal.x() + 6, modal.y() + 40, modal.x() + modal.width() - 6, modal.y() + modal.height() - 6);
        contentY = renderWrappedText(ctx, contentX, contentY, contentW, String.format(Locale.ROOT, "%s view | current %.1f%% CPU | raw %s samples | effective %s samples", taskEffectiveView ? "Effective" : "Raw", cpuMetricValue(displaySnapshot) * 100.0 / Math.max(1L, (taskEffectiveView ? totalCpuMetric(effectiveCpu.displaySnapshots()) : totalCpuMetric(rawCpu))), formatCount(rawSnapshot.totalSamples()), formatCount(effectiveSnapshot.totalSamples())), TEXT_DIM) + 6;
        contentY = renderWrappedText(ctx, contentX, contentY, contentW, "Why this row: sampled stack ownership points here directly, while shared/framework buckets are proportionally redistributed only in Effective view.", TEXT_DIM) + 8;
        String attributionHint = cpuAttributionHint(modId, detail, rawSnapshot.totalSamples(), displaySnapshot.totalSamples(), redistributedSamples);
        if (attributionHint != null) {
            contentY = renderWrappedText(ctx, contentX, contentY, contentW, attributionHint, ACCENT_YELLOW) + 8;
        }
        renderSeriesGraph(ctx, contentX, contentY, contentW, 124, trend, null, "CPU Trend (last ~30s)", "% CPU", ACCENT_GREEN, 0, getTrendSpanSeconds(recentPoints));
        contentY += 142;
        contentY = renderReasonSection(ctx, contentX, contentY, contentW, "Top threads [sampled]", effectiveThreadBreakdown(modId, detail)) + 6;
        contentY = renderReasonSection(ctx, contentX, contentY, contentW, isSharedAttributionBucket(modId) ? "Shared bucket sources" : "Top sampled frames [sampled]", isSharedAttributionBucket(modId) ? buildCpuBucketBreakdown(modId, detail) : (detail == null ? Map.of() : detail.topFrames())) + 6;
        if (isSharedAttributionBucket(modId)) {
            renderReasonSection(ctx, contentX, contentY, contentW, "Top sampled frames [sampled]", detail == null ? Map.of() : detail.topFrames());
        }
        ctx.disableScissor();
    }

    private void renderGpuDrilldownContent(DrawContext ctx, ModalLayout modal, String modId) {
        EffectiveGpuAttribution rawGpu = rawGpuAttribution();
        EffectiveGpuAttribution displayGpu = gpuEffectiveView ? effectiveGpuAttribution() : rawGpu;
        CpuSamplingProfiler.DetailSnapshot detail = snapshot.cpuDetails().get(modId);
        long displayGpuNanos = displayGpu.gpuNanosByMod().getOrDefault(modId, 0L);
        double[] trend = buildTrendSeries(TableId.GPU, modId, gpuEffectiveView);
        List<ProfilerManager.SessionPoint> recentPoints = getRecentSessionPoints();
        int contentX = modal.x() + 12;
        int contentY = modal.y() + 46;
        int contentW = modal.width() - 24;
        ctx.enableScissor(modal.x() + 6, modal.y() + 40, modal.x() + modal.width() - 6, modal.y() + modal.height() - 6);
        contentY = renderWrappedText(ctx, contentX, contentY, contentW, String.format(Locale.ROOT, "%s view | current %.1f%% GPU | raw %.2f ms | effective %.2f ms", gpuEffectiveView ? "Effective" : "Raw", displayGpuNanos * 100.0 / Math.max(1L, displayGpu.totalGpuNanos()), rawGpu.gpuNanosByMod().getOrDefault(modId, 0L) / 1_000_000.0, displayGpuNanos / 1_000_000.0), TEXT_DIM) + 6;
        contentY = renderWrappedText(ctx, contentX, contentY, contentW, "Why this row: tagged render-phase owners get first claim on GPU time, then leftover shared/render work is redistributed only in Effective view.", TEXT_DIM) + 8;
        renderSeriesGraph(ctx, contentX, contentY, contentW, 124, trend, null, "GPU Trend (last ~30s)", "% GPU", getGpuGraphColor(), 0, getTrendSpanSeconds(recentPoints));
        contentY += 142;
        contentY = renderWrappedText(ctx, contentX, contentY, contentW, "Owner source: " + describeGpuOwnerSource(modId), TEXT_DIM) + 6;
        contentY = renderReasonSection(ctx, contentX, contentY, contentW, "Render threads [sampled]", effectiveThreadBreakdown(modId, detail)) + 6;
        if (isSharedAttributionBucket(modId)) {
            contentY = renderReasonSection(ctx, contentX, contentY, contentW, "Likely owners during shared/render [sampled]", buildSharedRenderLikelyOwners()) + 6;
            contentY = renderStringListSection(ctx, contentX, contentY, contentW, "Top shared owner phases [tagged]", buildGpuPhaseBreakdownLines(modId)) + 6;
            contentY = renderReasonSection(ctx, contentX, contentY, contentW, "Likely render frames during shared/render [sampled]", buildSharedRenderLikelyFrames()) + 6;
        } else {
            contentY = renderStringListSection(ctx, contentX, contentY, contentW, "Top owner phases [tagged]", buildGpuPhaseBreakdownLines(modId)) + 6;
        }
        renderReasonSection(ctx, contentX, contentY, contentW, "Top sampled render frames [sampled]", detail == null ? Map.of() : detail.topFrames());
        ctx.disableScissor();
    }

    private void renderMemoryDrilldownContent(DrawContext ctx, ModalLayout modal, String modId) {
        Map<String, Long> rawMemory = snapshot.memoryMods();
        EffectiveMemoryAttribution effectiveMemory = effectiveMemoryAttribution();
        Map<String, Long> displayMemory = memoryEffectiveView ? effectiveMemory.displayBytes() : rawMemory;
        double[] trend = buildTrendSeries(TableId.MEMORY, modId, memoryEffectiveView);
        List<ProfilerManager.SessionPoint> recentPoints = getRecentSessionPoints();
        int contentX = modal.x() + 12;
        int contentY = modal.y() + 46;
        int contentW = modal.width() - 24;
        ctx.enableScissor(modal.x() + 6, modal.y() + 40, modal.x() + modal.width() - 6, modal.y() + modal.height() - 6);
        contentY = renderWrappedText(ctx, contentX, contentY, contentW, String.format(Locale.ROOT, "%s view | current %.1f MB shown | raw %.1f MB | effective %.1f MB", memoryEffectiveView ? "Effective" : "Raw", displayMemory.getOrDefault(modId, 0L) / (1024.0 * 1024.0), rawMemory.getOrDefault(modId, 0L) / (1024.0 * 1024.0), effectiveMemory.displayBytes().getOrDefault(modId, rawMemory.getOrDefault(modId, 0L)) / (1024.0 * 1024.0)), TEXT_DIM) + 6;
        contentY = renderWrappedText(ctx, contentX, contentY, contentW, "Why this row: live class ownership points here directly, while shared/runtime buckets are redistributed only in Effective view.", TEXT_DIM) + 8;
        renderSeriesGraph(ctx, contentX, contentY, contentW, 124, trend, null, "Memory Trend (last ~30s)", "MB", getMemoryGraphColor(), 0, getTrendSpanSeconds(recentPoints));
        contentY += 142;
        contentY = renderReasonSection(ctx, contentX, contentY, contentW, "Top classes", snapshot.memoryClassesByMod().getOrDefault(modId, Map.of())) + 6;
        if (isSharedAttributionBucket(modId)) {
            renderReasonSection(ctx, contentX, contentY, contentW, "Shared family classes", snapshot.sharedFamilyClasses().getOrDefault(modId, Map.of()));
        }
        ctx.disableScissor();
    }

    private List<ProfilerManager.SessionPoint> getRecentSessionPoints() {
        List<ProfilerManager.SessionPoint> history = new ArrayList<>(ProfilerManager.getInstance().getSessionHistory());
        if (history.isEmpty()) {
            return history;
        }
        long cutoff = System.currentTimeMillis() - (ATTRIBUTION_TREND_WINDOW_SECONDS * 1000L);
        List<ProfilerManager.SessionPoint> recent = history.stream()
                .filter(point -> point.capturedAtEpochMillis() >= cutoff)
                .toList();
        return recent.isEmpty() ? history : recent;
    }

    private int getTrendSpanSeconds(List<ProfilerManager.SessionPoint> points) {
        if (points == null || points.size() < 2) {
            return ATTRIBUTION_TREND_WINDOW_SECONDS;
        }
        long spanMs = Math.max(1000L, points.get(points.size() - 1).capturedAtEpochMillis() - points.get(0).capturedAtEpochMillis());
        return (int) Math.max(1L, Math.round(spanMs / 1000.0));
    }

    private double[] buildTrendSeries(TableId tableId, String modId, boolean effectiveView) {
        List<ProfilerManager.SessionPoint> points = getRecentSessionPoints();
        if (points.isEmpty()) {
            return new double[] {0.0};
        }
        double[] values = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            ProfilerManager.SessionPoint point = points.get(i);
            values[i] = switch (tableId) {
                case TASKS -> (effectiveView ? point.cpuEffectivePercentByMod() : point.cpuRawPercentByMod()).getOrDefault(modId, 0.0);
                case GPU -> (effectiveView ? point.gpuEffectivePercentByMod() : point.gpuRawPercentByMod()).getOrDefault(modId, 0.0);
                case MEMORY -> (effectiveView ? point.memoryEffectiveMbByMod() : point.memoryRawMbByMod()).getOrDefault(modId, 0.0);
            };
        }
        return values;
    }

    private void renderSettings(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        SettingsTabRenderer.render(this, ctx, x, y, w, h, mouseX, mouseY);
    }


    private long[] toLongArray(double[] values) {
        if (values == null || values.length == 0) {
            return new long[1];
        }
        long[] result = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = Math.round(values[i]);
        }
        return result;
    }

    private long[] toLongArray(long[] values) {
        return values == null || values.length == 0 ? new long[1] : values;
    }

    private double[] toDoubleArray(long[] values) {
        if (values == null || values.length == 0) {
            return new double[] {0.0};
        }
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[i];
        }
        return result;
    }

    private double[] toFrameMsArray(long[] values) {
        if (values == null || values.length == 0) {
            return new double[] {0.0};
        }
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[i] / 1_000_000.0;
        }
        return result;
    }

    private String cleanEntityName(Entity entity) {
        String name = entity.getName().getString();
        if (name != null && !name.isBlank() && !name.startsWith("entity.")) {
            return name;
        }
        return cleanProfilerLabel(entity.getType().toString());
    }

    String cleanProfilerLabel(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        if (raw.startsWith("entity.minecraft.")) {
            return prettifyKey(raw.substring("entity.minecraft.".length()));
        }
        if (raw.startsWith("minecraft:")) {
            return prettifyKey(raw.substring("minecraft:".length()));
        }
        if (raw.startsWith("[L") && raw.endsWith(";")) {
            return cleanProfilerLabel(raw.substring(2, raw.length() - 1)) + "[]";
        }
        if (raw.startsWith("[")) {
            return switch (raw) {
                case "[B" -> "byte[]";
                case "[C" -> "char[]";
                case "[D" -> "double[]";
                case "[F" -> "float[]";
                case "[I" -> "int[]";
                case "[J" -> "long[]";
                case "[S" -> "short[]";
                case "[Z" -> "boolean[]";
                default -> raw;
            };
        }
        if ("java.lang.Object".equals(raw)) {
            return "Object";
        }
        if (raw.contains(".")) {
            String[] parts = raw.split("\\.");
            return prettifyKey(parts[parts.length - 1]);
        }
        return prettifyKey(raw);
    }

    String prettifyKey(String key) {
        String cleaned = key == null ? "unknown" : key.replace('_', ' ').replace('-', ' ').replace(':', ' ');
        String[] parts = cleaned.split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.isEmpty() ? "unknown" : builder.toString();
    }

    String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
