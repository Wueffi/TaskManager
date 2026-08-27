package wueffi.taskmanager.client;

import net.minecraft.client.gui.DrawContext;

import java.util.Map;

final class NetworkTabRenderer {

    private NetworkTabRenderer() {
    }

    static void render(TaskManagerScreen screen, DrawContext ctx, int x, int y, int w, int h) {
        var textRenderer = screen.uiTextRenderer();
        SystemMetricsProfiler metrics = SystemMetricsProfiler.getInstance();
        int left = x + TaskManagerScreen.PADDING;
        int top = screen.getFullPageScrollTop(y);
        int graphWidth = screen.getPreferredGraphWidth(w);
        int graphX = x + Math.max(TaskManagerScreen.PADDING, (w - graphWidth) / 2);
        int columnGap = 20;
        int columnWidth = Math.max(120, (w - (TaskManagerScreen.PADDING * 2) - columnGap) / 2);
        int rightColumnX = left + columnWidth + columnGap;
        screen.beginFullPageScissor(ctx, x, y, w, h);
        ctx.drawText(textRenderer, "Network throughput and packet/channel attribution during capture.", left, top, TaskManagerScreen.TEXT_DIM, false);
        top += 14;
        if (screen.snapshot.systemMetrics().bytesReceivedPerSecond() < 0 && screen.snapshot.systemMetrics().bytesSentPerSecond() < 0) {
            ctx.drawText(textRenderer, "Network counters are unavailable right now. Packet attribution can still populate while throughput stays unavailable.", left, top, TaskManagerScreen.ACCENT_YELLOW, false);
            top += 14;
        }
        screen.drawMetricRow(ctx, left, top, w - 16, "Inbound", screen.formatBytesPerSecond(screen.snapshot.systemMetrics().bytesReceivedPerSecond()));
        top += 16;
        screen.drawMetricRow(ctx, left, top, w - 16, "Outbound", screen.formatBytesPerSecond(screen.snapshot.systemMetrics().bytesSentPerSecond()));
        top += 20;
        int graphHeight = 132;
        screen.renderMetricGraph(ctx, graphX - TaskManagerScreen.PADDING, top, graphWidth + (TaskManagerScreen.PADDING * 2), graphHeight, metrics.getOrderedNetworkInHistory(), metrics.getOrderedNetworkOutHistory(), "Network In/Out", "B/s", metrics.getHistorySpanSeconds());
        top += graphHeight + 2;
        top += screen.renderGraphLegend(ctx, graphX, top, new String[]{"Inbound", "Outbound"}, new int[]{TaskManagerScreen.INTEL_COLOR, TaskManagerScreen.ACCENT_YELLOW}) + 14;

        java.util.List<NetworkPacketProfiler.Snapshot> packetHistory = NetworkPacketProfiler.getInstance().getHistory();
        NetworkPacketProfiler.Snapshot latestPackets = packetHistory.isEmpty() ? null : packetHistory.get(packetHistory.size() - 1);
        ctx.drawText(textRenderer, "Inbound categories", left, top, TaskManagerScreen.TEXT_PRIMARY, false);
        ctx.drawText(textRenderer, "Outbound categories", rightColumnX, top, TaskManagerScreen.TEXT_PRIMARY, false);
        top += 14;
        int categoryHeight = Math.max(
                screen.renderPacketBreakdownColumn(ctx, left, top, columnWidth, latestPackets != null ? latestPackets.inboundByCategory() : Map.of()),
                screen.renderPacketBreakdownColumn(ctx, rightColumnX, top, columnWidth, latestPackets != null ? latestPackets.outboundByCategory() : Map.of())
        );
        top += categoryHeight + 12;

        ctx.drawText(textRenderer, "Inbound packet types", left, top, TaskManagerScreen.TEXT_PRIMARY, false);
        ctx.drawText(textRenderer, "Outbound packet types", rightColumnX, top, TaskManagerScreen.TEXT_PRIMARY, false);
        top += 14;
        int typeHeight = Math.max(
                screen.renderPacketBreakdownColumn(ctx, left, top, columnWidth, latestPackets != null ? latestPackets.inboundByType() : Map.of()),
                screen.renderPacketBreakdownColumn(ctx, rightColumnX, top, columnWidth, latestPackets != null ? latestPackets.outboundByType() : Map.of())
        );
        top += typeHeight + 12;

        ctx.drawText(textRenderer, "Packet spike bookmarks", left, top, TaskManagerScreen.TEXT_PRIMARY, false);
        top += 14;
        top += screen.renderPacketSpikeBookmarks(ctx, left, top, w - 16, NetworkPacketProfiler.getInstance().getSpikeHistory());
        ctx.disableScissor();
    }
}
