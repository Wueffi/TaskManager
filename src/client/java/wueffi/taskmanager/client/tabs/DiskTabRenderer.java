package wueffi.taskmanager.client;

import net.minecraft.client.gui.DrawContext;

final class DiskTabRenderer {

    private DiskTabRenderer() {
    }

    static void render(TaskManagerScreen screen, DrawContext ctx, int x, int y, int w, int h) {
        var textRenderer = screen.uiTextRenderer();
        SystemMetricsProfiler metrics = SystemMetricsProfiler.getInstance();
        int left = x + TaskManagerScreen.PADDING;
        int top = screen.getFullPageScrollTop(y);
        int graphWidth = screen.getPreferredGraphWidth(w);
        int graphX = x + Math.max(TaskManagerScreen.PADDING, (w - graphWidth) / 2);
        screen.beginFullPageScissor(ctx, x, y, w, h);
        ctx.drawText(textRenderer, "Disk throughput from OS counters during capture. Unsupported platforms may show unavailable.", left, top, TaskManagerScreen.TEXT_DIM, false);
        top += 14;
        if (screen.snapshot.systemMetrics().diskReadBytesPerSecond() < 0 && screen.snapshot.systemMetrics().diskWriteBytesPerSecond() < 0) {
            ctx.drawText(textRenderer, "Disk throughput counters are unavailable on this provider right now.", left, top, TaskManagerScreen.ACCENT_YELLOW, false);
            top += 14;
        }
        screen.drawMetricRow(ctx, left, top, w - 16, "Read", screen.formatBytesPerSecond(screen.snapshot.systemMetrics().diskReadBytesPerSecond()));
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 16, "Write", screen.formatBytesPerSecond(screen.snapshot.systemMetrics().diskWriteBytesPerSecond()));
        top += 20;
        int graphHeight = 132;
        screen.renderMetricGraph(ctx, graphX - TaskManagerScreen.PADDING, top, graphWidth + (TaskManagerScreen.PADDING * 2), graphHeight, metrics.getOrderedDiskReadHistory(), metrics.getOrderedDiskWriteHistory(), "Disk Read/Write", "B/s", metrics.getHistorySpanSeconds());
        top += graphHeight + 2;
        screen.renderGraphLegend(ctx, graphX, top, new String[]{"Read", "Write"}, new int[]{TaskManagerScreen.INTEL_COLOR, TaskManagerScreen.ACCENT_YELLOW});
        ctx.disableScissor();
    }
}
