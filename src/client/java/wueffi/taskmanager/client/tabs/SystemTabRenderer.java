package wueffi.taskmanager.client;

import net.minecraft.client.gui.DrawContext;

import java.util.Locale;

final class SystemTabRenderer {

    private SystemTabRenderer() {
    }

    static void render(TaskManagerScreen screen, DrawContext ctx, int x, int y, int w, int h) {
        int left = x + TaskManagerScreen.PADDING;
        int top = screen.getFullPageScrollTop(y);
        screen.beginFullPageScissor(ctx, x, y, w, h);
        ProfilerManager.ProfilerSnapshot snapshot = screen.currentSnapshot();
        SystemMetricsProfiler.Snapshot system = snapshot.systemMetrics();
        SystemMetricsProfiler metrics = SystemMetricsProfiler.getInstance();

        top = screen.renderSectionHeader(ctx, left, top, "System", "Runtime health, sensors, and CPU/GPU load history.");
        screen.drawTopChip(ctx, left, top, 78, 16, screen.currentSystemMiniTab() == TaskManagerScreen.SystemMiniTab.OVERVIEW);
        screen.drawTopChip(ctx, left + 84, top, 88, 16, screen.currentSystemMiniTab() == TaskManagerScreen.SystemMiniTab.CPU_GRAPH);
        screen.drawTopChip(ctx, left + 178, top, 88, 16, screen.currentSystemMiniTab() == TaskManagerScreen.SystemMiniTab.GPU_GRAPH);
        screen.drawTopChip(ctx, left + 272, top, 108, 16, screen.currentSystemMiniTab() == TaskManagerScreen.SystemMiniTab.MEMORY_GRAPH);
        ctx.drawText(screen.uiTextRenderer(), "Overview", left + 14, top + 4,
                screen.currentSystemMiniTab() == TaskManagerScreen.SystemMiniTab.OVERVIEW ? TaskManagerScreen.TEXT_PRIMARY : TaskManagerScreen.TEXT_DIM, false);
        ctx.drawText(screen.uiTextRenderer(), "CPU Graph", left + 100, top + 4,
                screen.currentSystemMiniTab() == TaskManagerScreen.SystemMiniTab.CPU_GRAPH ? TaskManagerScreen.TEXT_PRIMARY : TaskManagerScreen.TEXT_DIM, false);
        ctx.drawText(screen.uiTextRenderer(), "GPU Graph", left + 194, top + 4,
                screen.currentSystemMiniTab() == TaskManagerScreen.SystemMiniTab.GPU_GRAPH ? TaskManagerScreen.TEXT_PRIMARY : TaskManagerScreen.TEXT_DIM, false);
        ctx.drawText(screen.uiTextRenderer(), "Memory Graph", left + 286, top + 4,
                screen.currentSystemMiniTab() == TaskManagerScreen.SystemMiniTab.MEMORY_GRAPH ? TaskManagerScreen.TEXT_PRIMARY : TaskManagerScreen.TEXT_DIM, false);
        top += 24;

        if (screen.currentSystemMiniTab() == TaskManagerScreen.SystemMiniTab.CPU_GRAPH) {
            int graphWidth = screen.getPreferredGraphWidth(w);
            int graphLeft = x + Math.max(TaskManagerScreen.PADDING, (w - graphWidth) / 2);
            screen.renderGraphMetricTabs(ctx, graphLeft, top, graphWidth, screen.currentCpuGraphMetricTab());
            top += 24;
            if (screen.currentCpuGraphMetricTab() == TaskManagerScreen.GraphMetricTab.LOAD) {
                screen.renderFixedScaleSeriesGraph(ctx, graphLeft, top, graphWidth, 146, metrics.getOrderedCpuLoadHistory(), "CPU Load", "%", screen.getCpuGraphColor(), 100.0, metrics.getHistorySpanSeconds());
                top += 164;
                top += screen.renderGraphLegend(ctx, graphLeft, top, new String[]{"CPU Load"}, new int[]{screen.getCpuGraphColor()}) + 8;
            } else {
                screen.renderSensorSeriesGraph(ctx, graphLeft, top, graphWidth, 146, metrics.getOrderedCpuTemperatureHistory(), "CPU Temperature", "C", screen.getCpuGraphColor(), 110.0, metrics.getHistorySpanSeconds(), system.cpuTemperatureC() >= 0.0, screen.uiTextRenderer().trimToWidth(system.cpuTemperatureUnavailableReason(), Math.max(80, graphWidth - 12)));
                top += 164;
                top += screen.renderGraphLegend(ctx, graphLeft, top, new String[]{"CPU Temperature"}, new int[]{screen.getCpuGraphColor()}) + 8;
            }
            screen.drawMetricRow(ctx, graphLeft, top, graphWidth, "Current CPU Load", screen.formatPercentWithTrend(system.cpuCoreLoadPercent(), system.cpuLoadChangePerSecond()));
            top += 16;
            screen.drawMetricRow(ctx, graphLeft, top, graphWidth, "CPU Temperature", screen.formatTemperatureWithTrend(system.cpuTemperatureC(), system.cpuTemperatureChangePerSecond()));
            top += 16;
            screen.drawMetricRow(ctx, graphLeft, top, graphWidth, "CPU Info", screen.formatCpuInfo());
            ctx.disableScissor();
            return;
        }

        if (screen.currentSystemMiniTab() == TaskManagerScreen.SystemMiniTab.GPU_GRAPH) {
            int graphWidth = screen.getPreferredGraphWidth(w);
            int graphLeft = x + Math.max(TaskManagerScreen.PADDING, (w - graphWidth) / 2);
            screen.renderGraphMetricTabs(ctx, graphLeft, top, graphWidth, screen.currentGpuGraphMetricTab());
            top += 24;
            if (screen.currentGpuGraphMetricTab() == TaskManagerScreen.GraphMetricTab.LOAD) {
                screen.renderFixedScaleSeriesGraph(ctx, graphLeft, top, graphWidth, 146, metrics.getOrderedGpuLoadHistory(), "GPU Load", "%", screen.getGpuGraphColor(), 100.0, metrics.getHistorySpanSeconds());
                top += 164;
                top += screen.renderGraphLegend(ctx, graphLeft, top, new String[]{"GPU Load"}, new int[]{screen.getGpuGraphColor()}) + 8;
            } else {
                screen.renderSensorSeriesGraph(ctx, graphLeft, top, graphWidth, 146, metrics.getOrderedGpuTemperatureHistory(), "GPU Core Temperature", "C", screen.getGpuGraphColor(), 110.0, metrics.getHistorySpanSeconds(), system.gpuTemperatureC() >= 0.0, "GPU core temperature is unavailable");
                top += 164;
                top += screen.renderGraphLegend(ctx, graphLeft, top, new String[]{"GPU Core Temperature"}, new int[]{screen.getGpuGraphColor()}) + 8;
            }
            double vramMaxMb = Math.max(1.0, system.vramTotalBytes() > 0L ? system.vramTotalBytes() / (1024.0 * 1024.0) : 1.0);
            screen.renderFixedScaleSeriesGraph(ctx, graphLeft, top, graphWidth, 118, metrics.getOrderedVramUsedHistory(), "VRAM Usage", "MB", screen.getGpuGraphColor(), vramMaxMb, metrics.getHistorySpanSeconds());
            top += 132;
            top += screen.renderGraphLegend(ctx, graphLeft, top, new String[]{"VRAM Used"}, new int[]{screen.getGpuGraphColor()}) + 8;
            screen.drawMetricRow(ctx, graphLeft, top, graphWidth, "Current GPU Load", screen.formatPercentWithTrend(system.gpuCoreLoadPercent(), system.gpuLoadChangePerSecond()));
            top += 16;
            screen.drawMetricRow(ctx, graphLeft, top, graphWidth, "GPU Core Temperature", TelemetryTextFormatter.formatGpuTemperatureWithTrend(system));
            top += 16;
            screen.drawMetricRow(ctx, graphLeft, top, graphWidth, "GPU Core / Hot Spot", TelemetryTextFormatter.formatGpuTemperatureSummary(system));
            top += 16;
            screen.drawMetricRow(ctx, graphLeft, top, graphWidth, "GPU Info", screen.blankToUnknown(system.gpuVendor()) + " | " + screen.blankToUnknown(system.gpuRenderer()));
            top += 16;
            screen.drawMetricRow(ctx, graphLeft, top, graphWidth, "VRAM Usage", screen.formatBytesMb(system.vramUsedBytes()) + " / " + screen.formatBytesMb(system.vramTotalBytes()));
            ctx.disableScissor();
            return;
        }

        if (screen.currentSystemMiniTab() == TaskManagerScreen.SystemMiniTab.MEMORY_GRAPH) {
            int graphWidth = screen.getPreferredGraphWidth(w);
            int graphLeft = x + Math.max(TaskManagerScreen.PADDING, (w - graphWidth) / 2);
            long heapMaxBytes = snapshot.memory().heapMaxBytes() > 0 ? snapshot.memory().heapMaxBytes() : Runtime.getRuntime().maxMemory();
            double heapMaxMb = Math.max(1.0, heapMaxBytes / (1024.0 * 1024.0));
            screen.renderFixedScaleSeriesGraph(ctx, graphLeft, top, graphWidth, 146,
                    metrics.getOrderedMemoryUsedHistory(),
                    metrics.getOrderedMemoryCommittedHistory(),
                    "Memory Load", "MB", screen.getMemoryGraphColor(), 0x6688B5FF, heapMaxMb,
                    metrics.getHistorySpanSeconds());
            top += 164;
            top += screen.renderGraphLegend(ctx, graphLeft, top, new String[]{"Heap Used", "Heap Allocated"}, new int[]{screen.getMemoryGraphColor(), 0x6688B5FF}) + 8;
            screen.drawMetricRow(ctx, graphLeft, top, graphWidth, "Heap Used", screen.formatBytesMb(snapshot.memory().heapUsedBytes()));
            top += 16;
            screen.drawMetricRow(ctx, graphLeft, top, graphWidth, "Heap Allocated", screen.formatBytesMb(snapshot.memory().heapCommittedBytes()));
            top += 16;
            screen.drawMetricRow(ctx, graphLeft, top, graphWidth, "Heap Max", screen.formatBytesMb(heapMaxBytes));
            top += 16;
            screen.drawMetricRow(ctx, graphLeft, top, graphWidth, "Non-Heap", screen.formatBytesMb(snapshot.memory().nonHeapUsedBytes()));
            ctx.disableScissor();
            return;
        }

        screen.drawMetricRow(ctx, left, top, w - 32, "CPU Info", screen.formatCpuInfo());
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "GPU Info", screen.blankToUnknown(system.gpuVendor()) + " | " + screen.blankToUnknown(system.gpuRenderer()));
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "VRAM Usage", screen.formatBytesMb(system.vramUsedBytes()) + " / " + screen.formatBytesMb(system.vramTotalBytes()));
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "VRAM Paging", system.vramPagingActive() ? screen.formatBytesMb(system.vramPagingBytes()) : "none detected");
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "Committed Virtual Memory", screen.formatBytesMb(system.committedVirtualMemoryBytes()));
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "Off-Heap Direct", screen.formatBytesMb(system.directMemoryUsedBytes()) + " / " + screen.formatBytesMb(system.directMemoryMaxBytes()));
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "CPU", screen.formatCpuGpuSummary(system, true));
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "GPU", screen.formatCpuGpuSummary(system, false));
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "CPU Temperature", screen.formatTemperature(system.cpuTemperatureC()));
        top += 16;
        if (system.cpuTemperatureC() < 0) {
            ctx.drawText(screen.uiTextRenderer(), screen.uiTextRenderer().trimToWidth("Why CPU temp is unavailable: " + screen.blankToUnknown(system.cpuTemperatureUnavailableReason()), w - 24), left + 6, top, TaskManagerScreen.ACCENT_YELLOW, false);
            top += 14;
        }
        screen.drawMetricRow(ctx, left, top, w - 32, "GPU Core Temperature", TelemetryTextFormatter.formatGpuTemperatureWithTrend(system));
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "GPU Core / Hot Spot", TelemetryTextFormatter.formatGpuTemperatureSummary(system));
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "Main Logic", screen.blankToUnknown(system.mainLogicSummary()));
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "Background", screen.blankToUnknown(system.backgroundSummary()));
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "CPU Parallelism", screen.blankToUnknown(system.cpuParallelismFlag()));
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "Parallelism Efficiency [inferred]", screen.blankToUnknown(system.parallelismEfficiency()));
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "Thread Load", String.format(Locale.ROOT, "%.1f%% total", system.totalThreadLoadPercent()));
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "High-Load Threads [inferred]", system.activeHighLoadThreads() + " >50% | est physical cores " + system.estimatedPhysicalCores());
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "Server Wait-Time", system.serverThreadWaitMs() + " ms waited | " + system.serverThreadBlockedMs() + " ms blocked");
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "Worker Ratio", system.activeWorkers() + " active / " + system.idleWorkers() + " idle (" + String.format(Locale.ROOT, "%.2f", system.activeToIdleWorkerRatio()) + ")");
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "CPU Sensor Status", screen.blankToUnknown(system.cpuSensorStatus()));
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "Off-Heap Allocation Rate", screen.formatBytesPerSecond(system.offHeapAllocationRateBytesPerSecond()));
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "Current Biome", screen.prettifyKey(screen.blankToUnknown(system.currentBiome())));
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "Light Update Queue [best-effort]", screen.blankToUnknown(system.lightUpdateQueue()));
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "Max Entities In Hot Chunk", String.valueOf(system.maxEntitiesInHotChunk()));
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "Packet Latency", system.packetProcessingLatencyMs() < 0 ? "unavailable" : String.format(Locale.ROOT, "%.1f ms [estimated]", system.packetProcessingLatencyMs()));
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "Packet Buffer Pressure", screen.blankToUnknown(system.networkBufferSaturation()));
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "Graphics Stack", screen.blankToUnknown(ProfilerManager.getInstance().getGraphicsPipelineSummary()));
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "Collector Governor", screen.blankToUnknown(system.collectorGovernorMode()));
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "GPU Coverage", screen.blankToUnknown(system.gpuCoverageSummary()));
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "Resource Packs / Texture Uploads", screen.summarizeResourcePackAndTextureState(system));
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 32, "Alloc Pressure", screen.summarizeAllocationPressure());
        top += 22;
        screen.renderSensorsPanel(ctx, left, top, w - 24, system);
        top += 142;
        screen.renderProfilerSelfCostPanel(ctx, left, top, w - 24, system);
        top += 84;
        top = screen.renderStringListSection(ctx, left, top, w - 24, "Thread Drilldown", screen.buildThreadDrilldownLines(system)) + 10;
        top = screen.renderStringListSection(ctx, left, top, w - 24, "Shader Compile Stutter [measured CPU]", screen.buildShaderCompileLines()) + 10;
        top = screen.renderStringListSection(ctx, left, top, w - 24, "JVM Tuning Advisor", ProfilerManager.getInstance().getJvmTuningAdvisor()) + 10;
        top = screen.renderStringListSection(ctx, left, top, w - 24, "Chunk Pipeline Drill-Down", screen.buildChunkPipelineDrilldownLines()) + 10;
        ctx.drawText(screen.uiTextRenderer(), screen.uiTextRenderer().trimToWidth("Export sessions keep the current runtime summary, findings, hotspots, and HTML report for offline inspection.", w - 24), left, top, TaskManagerScreen.TEXT_DIM, false);
        ctx.disableScissor();
    }
}
