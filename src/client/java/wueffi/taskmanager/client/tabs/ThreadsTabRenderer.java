package wueffi.taskmanager.client;

import net.minecraft.client.gui.DrawContext;

import java.util.List;
import java.util.Locale;

final class ThreadsTabRenderer {

    private ThreadsTabRenderer() {
    }

    static void render(TaskManagerScreen screen, DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        List<SystemMetricsProfiler.ThreadDrilldown> rows = screen.getThreadRows();
        int detailW = Math.min(420, Math.max(320, w / 3));
        int gap = TaskManagerScreen.PADDING;
        int listW = w - detailW - gap;
        int infoY = y + TaskManagerScreen.PADDING;
        int descriptionBottomY = screen.renderWrappedText(ctx, x + TaskManagerScreen.PADDING, infoY, Math.max(260, listW - 16),
                "Measured live thread CPU/allocation snapshots with sampled owner and confidence. Use this when a mod row is really a thread question.",
                TaskManagerScreen.TEXT_DIM);
        ctx.drawText(screen.uiTextRenderer(), "Tip: the universal search filters thread name, owner, reason frame, and sampled top frames.",
                x + TaskManagerScreen.PADDING, descriptionBottomY + 2, TaskManagerScreen.TEXT_DIM, false);
        String freezeState = screen.isThreadFreezeActive() ? "Frozen snapshot" : "Live snapshot";
        ctx.drawText(screen.uiTextRenderer(), rows.size() + " live threads | sort " + screen.currentThreadSortLabel() + " | " + freezeState,
                x + TaskManagerScreen.PADDING, descriptionBottomY + 14, TaskManagerScreen.TEXT_DIM, false);
        int controlsY = descriptionBottomY + 26;
        screen.renderThreadToolbar(ctx, x + TaskManagerScreen.PADDING, controlsY);

        if (!rows.isEmpty() && rows.stream().noneMatch(row -> row.threadId() == screen.currentSelectedThreadId())) {
            screen.setCurrentSelectedThreadId(rows.getFirst().threadId());
        }

        int headerY = descriptionBottomY + 52;
        ctx.fill(x, headerY, x + listW, headerY + 14, TaskManagerScreen.HEADER_COLOR);
        int ownerX = x + listW - 252;
        int cpuX = x + listW - 156;
        int allocX = x + listW - 102;
        int stateX = x + listW - 54;
        ctx.drawText(screen.uiTextRenderer(), "THREAD", x + TaskManagerScreen.PADDING, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        ctx.drawText(screen.uiTextRenderer(), "OWNER", ownerX, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        ctx.drawText(screen.uiTextRenderer(), "%CPU", cpuX, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        ctx.drawText(screen.uiTextRenderer(), "ALLOC", allocX, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        ctx.drawText(screen.uiTextRenderer(), "STATE", stateX, headerY + 3, TaskManagerScreen.TEXT_DIM, false);

        int listY = headerY + 16;
        int listH = h - (listY - y);
        if (rows.isEmpty()) {
            ctx.drawText(screen.uiTextRenderer(),
                    screen.currentGlobalSearch().isBlank() ? "Waiting for live thread samples..." : "No threads match the current universal search.",
                    x + TaskManagerScreen.PADDING, listY + 6, TaskManagerScreen.TEXT_DIM, false);
        } else {
            ctx.enableScissor(x, listY, x + listW, listY + listH);
            int rowY = listY - screen.currentScrollOffset();
            int rowIdx = 0;
            for (SystemMetricsProfiler.ThreadDrilldown thread : rows) {
                if (rowY + TaskManagerScreen.ATTRIBUTION_ROW_HEIGHT > listY && rowY < listY + listH) {
                    screen.renderStripedRowVariable(ctx, x, listW, rowY, TaskManagerScreen.ATTRIBUTION_ROW_HEIGHT, rowIdx, mouseX, mouseY);
                    if (thread.threadId() == screen.currentSelectedThreadId()) {
                        ctx.fill(x, rowY, x + 3, rowY + TaskManagerScreen.ATTRIBUTION_ROW_HEIGHT, TaskManagerScreen.ACCENT_GREEN);
                    }
                    String confidence = screen.blankToUnknown(thread.confidence());
                    int modRight = screen.firstVisibleMetricX(x + listW - 8, ownerX, true, cpuX, true, allocX, true, stateX, true);
                    int chipWidth = screen.confidenceChipWidth(confidence);
                    int chipX = Math.max(x + TaskManagerScreen.PADDING + 96, modRight - chipWidth);
                    int threadNameX = x + TaskManagerScreen.PADDING;
                    int threadNameWidth = Math.max(48, chipX - threadNameX - 6);
                    ctx.drawText(screen.uiTextRenderer(), screen.uiTextRenderer().trimToWidth(screen.cleanProfilerLabel(thread.threadName()), threadNameWidth),
                            threadNameX, rowY + 4, TaskManagerScreen.TEXT_PRIMARY, false);
                    screen.renderConfidenceChip(ctx, chipX, rowY + 3, confidence);
                    ctx.drawText(screen.uiTextRenderer(),
                            screen.uiTextRenderer().trimToWidth("Reason: " + screen.cleanProfilerLabel(thread.reasonFrame()), Math.max(60, modRight - threadNameX)),
                            threadNameX, rowY + 16, TaskManagerScreen.TEXT_DIM, false);
                    ctx.drawText(screen.uiTextRenderer(),
                            screen.uiTextRenderer().trimToWidth(screen.getDisplayName(thread.ownerMod()), Math.max(44, cpuX - ownerX - 8)),
                            ownerX, rowY + 9, TaskManagerScreen.TEXT_DIM, false);
                    ctx.drawText(screen.uiTextRenderer(), String.format(Locale.ROOT, "%.1f%%", thread.cpuLoadPercent()), cpuX, rowY + 9,
                            screen.getHeatColor(thread.cpuLoadPercent()), false);
                    ctx.drawText(screen.uiTextRenderer(), screen.uiTextRenderer().trimToWidth(screen.formatBytesPerSecond(thread.allocationRateBytesPerSecond()), 48),
                            allocX, rowY + 9, TaskManagerScreen.TEXT_DIM, false);
                    ctx.drawText(screen.uiTextRenderer(), screen.uiTextRenderer().trimToWidth(screen.blankToUnknown(thread.state()), 44),
                            stateX, rowY + 9, TaskManagerScreen.TEXT_DIM, false);
                }
                if (rowY > listY + listH) {
                    break;
                }
                rowY += TaskManagerScreen.ATTRIBUTION_ROW_HEIGHT;
                rowIdx++;
            }
            ctx.disableScissor();
        }

        screen.renderThreadDetailPanel(ctx, x + listW + gap, y + TaskManagerScreen.PADDING, detailW, h - (TaskManagerScreen.PADDING * 2),
                rows.stream().filter(row -> row.threadId() == screen.currentSelectedThreadId()).findFirst().orElse(null));
    }
}
