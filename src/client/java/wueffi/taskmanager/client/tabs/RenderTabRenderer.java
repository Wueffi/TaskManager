package wueffi.taskmanager.client;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;

final class RenderTabRenderer {

    private RenderTabRenderer() {
    }

    static void render(TaskManagerScreen screen, GuiGraphicsExtractor ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        var textRenderer = screen.uiTextRenderer();
        int controlsY = y + TaskManagerScreen.PADDING;
        screen.drawTopChip(ctx, x + TaskManagerScreen.PADDING, controlsY, 100, 16, false);
        ctx.text(textRenderer, screen.renderPassView ? "View: Passes [V]" : "View: Phases [V]", x + TaskManagerScreen.PADDING + 4, controlsY + 4, TaskManagerScreen.TEXT_PRIMARY, false);
        screen.drawTopChip(ctx, x + TaskManagerScreen.PADDING + 106, controlsY, 116, 16, false);
        ctx.text(textRenderer, "Collect: " + (ShaderPassProfiler.isEnabled() ? "ON" : "OFF") + " [C]", x + TaskManagerScreen.PADDING + 110, controlsY + 4, TaskManagerScreen.TEXT_PRIMARY, false);
        if (screen.renderPassView) {
            screen.drawTopChip(ctx, x + TaskManagerScreen.PADDING + 228, controlsY, 100, 16, false);
            ctx.text(textRenderer, screen.renderPassSortDescending ? "Frame ms: desc [S]" : "Frame ms: asc [S]", x + TaskManagerScreen.PADDING + 232, controlsY + 4, TaskManagerScreen.TEXT_PRIMARY, false);
            renderPasses(screen, ctx, x, y + 24, w, h - 24, mouseX, mouseY);
            return;
        }
        y += 24;
        h -= 24;
        List<String> phases = screen.snapshot.renderPhases().entrySet().stream()
                .filter(entry -> screen.matchesGlobalSearch((entry.getKey() + " " + screen.getDisplayName(entry.getValue().ownerMod() == null ? "shared/render" : entry.getValue().ownerMod())).toLowerCase(Locale.ROOT)))
                .map(Map.Entry::getKey)
                .toList();
        if (phases.isEmpty()) {
            ctx.text(textRenderer, screen.globalSearch.isBlank() ? "No render data." : "No render phases match the universal search.", x + TaskManagerScreen.PADDING, y + TaskManagerScreen.PADDING + 4, TaskManagerScreen.TEXT_DIM, false);
            return;
        }

        long totalCpuNanos = screen.snapshot.renderPhases().values().stream().mapToLong(RenderPhaseProfiler.PhaseSnapshot::cpuNanos).sum();
        ctx.text(textRenderer, "Owner shows the tagged mod bucket used by GPU attribution. Shared / Render means the phase still falls back to the shared render pool.", x + TaskManagerScreen.PADDING, y + TaskManagerScreen.PADDING, TaskManagerScreen.TEXT_DIM, false);

        int headerY = y + TaskManagerScreen.PADDING + 18;
        ctx.fill(x, headerY, x + w, headerY + 14, TaskManagerScreen.HEADER_COLOR);
        ctx.text(textRenderer, "PHASE", x + TaskManagerScreen.PADDING, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(x + TaskManagerScreen.PADDING, headerY + 1, 44, 14, "Render phase name.");
        int ownerX = w - 300;
        int shareX = w - 175;
        int cpuMsX = w - 120;
        int gpuMsX = w - 72;
        int callsX = w - 34;
        ctx.text(textRenderer, "OWNER", ownerX, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(ownerX, headerY + 1, 42, 14, "Tagged owner mod for this phase. The GPU tab uses this first before redistributing any leftover shared render work.");
        ctx.text(textRenderer, "%CPU", shareX, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(shareX, headerY + 1, 38, 14, "CPU share of this render phase in the current window.");
        ctx.text(textRenderer, "CPU", cpuMsX, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(cpuMsX, headerY + 1, 28, 14, "Average CPU milliseconds per call for this phase.");
        ctx.text(textRenderer, "GPU", gpuMsX, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(gpuMsX, headerY + 1, 28, 14, "Average GPU milliseconds per call when timer queries are available.");
        ctx.text(textRenderer, "C", callsX, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(callsX, headerY + 1, 12, 14, "Approximate call count for this phase in the rolling window.");

        int listY = headerY + 16;
        int listH = h - (listY - y);
        ctx.enableScissor(x, listY, x + w, listY + listH);

        int rowY = listY - screen.scrollOffset;
        int rowIdx = 0;
        for (String phase : phases) {
            if (rowY + 20 > listY && rowY < listY + listH) {
                screen.renderStripedRow(ctx, x, w, rowY, rowIdx, mouseX, mouseY);
                RenderPhaseProfiler.PhaseSnapshot phaseSnapshot = screen.snapshot.renderPhases().get(phase);
                long phaseCalls = Math.max(phaseSnapshot.cpuCalls(), phaseSnapshot.gpuCalls());
                double pct = totalCpuNanos > 0 ? phaseSnapshot.cpuNanos() * 100.0 / totalCpuNanos : 0;
                double avgCpuMs = phaseSnapshot.cpuCalls() > 0 ? (phaseSnapshot.cpuNanos() / 1_000_000.0) / phaseSnapshot.cpuCalls() : 0;
                double avgGpuMs = phaseSnapshot.gpuCalls() > 0 ? (phaseSnapshot.gpuNanos() / 1_000_000.0) / phaseSnapshot.gpuCalls() : 0;
                String owner = phaseSnapshot.ownerMod() == null || phaseSnapshot.ownerMod().isBlank() ? "shared/render" : phaseSnapshot.ownerMod();
                String phaseLabel = textRenderer.plainSubstrByWidth(phase, Math.max(120, ownerX - (x + TaskManagerScreen.PADDING) - 8));

                ctx.text(textRenderer, phaseLabel, x + TaskManagerScreen.PADDING, rowY + 6, TaskManagerScreen.TEXT_PRIMARY, false);
                ctx.text(textRenderer, textRenderer.plainSubstrByWidth(screen.getDisplayName(owner), Math.max(70, shareX - ownerX - 6)), ownerX, rowY + 6, screen.isSharedAttributionBucket(owner) ? TaskManagerScreen.TEXT_DIM : TaskManagerScreen.TEXT_PRIMARY, false);
                ctx.text(textRenderer, String.format("%.1f%%", pct), shareX, rowY + 6, screen.getHeatColor(pct), false);
                ctx.text(textRenderer, phaseSnapshot.cpuCalls() > 0 ? String.format("%.2f", avgCpuMs) : "-", cpuMsX, rowY + 6, TaskManagerScreen.TEXT_DIM, false);
                ctx.text(textRenderer, phaseSnapshot.gpuCalls() > 0 ? String.format("%.2f", avgGpuMs) : "-", gpuMsX, rowY + 6, TaskManagerScreen.TEXT_DIM, false);
                ctx.text(textRenderer, screen.formatCount(phaseCalls), callsX, rowY + 6, TaskManagerScreen.TEXT_DIM, false);
            }
            if (rowY > listY + listH) {
                break;
            }
            rowY += 20;
            rowIdx++;
        }
        ctx.disableScissor();
    }
    private static void renderPasses(TaskManagerScreen screen, GuiGraphicsExtractor ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        var font = screen.uiTextRenderer();
        String summary = ShaderPassProfiler.supportSummary();
        ctx.text(font, font.plainSubstrByWidth(summary, w - 2 * TaskManagerScreen.PADDING), x + TaskManagerScreen.PADDING, y + TaskManagerScreen.PADDING, TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(x + TaskManagerScreen.PADDING, y + TaskManagerScreen.PADDING, w - 2 * TaskManagerScreen.PADDING, 12, summary);
        int headerY = y + TaskManagerScreen.PADDING + 18;
        ctx.fill(x, headerY, x + w, headerY + 14, TaskManagerScreen.HEADER_COLOR);
        ctx.text(font, "IRIS PASS / STAGE", x + TaskManagerScreen.PADDING, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(x + TaskManagerScreen.PADDING, headerY, w - 190, 14, ShaderPassProfiler.PASS_COVERAGE + ".");
        ctx.text(font, "MS/FRAME", x + w - 174, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        ctx.text(font, "MS/CALL", x + w - 108, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        ctx.text(font, "CALLS", x + w - 52, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(x + w - 174, headerY, 66, 14, "Average GPU milliseconds per fully completed executing sample frame.");
        screen.addTooltip(x + w - 108, headerY, 56, 14, "Average GPU milliseconds per completed invocation.");
        Comparator<ShaderPassProfiler.PassRow> order = Comparator.comparingDouble(ShaderPassProfiler.PassRow::avgFrameMs);
        if (screen.renderPassSortDescending) order = order.reversed();
        order = Comparator.<ShaderPassProfiler.PassRow, Boolean>comparing(row -> row.sampledFrames() == 0)
                .thenComparing(order);
        List<ShaderPassProfiler.PassRow> rows = ShaderPassProfiler.rows().stream()
                .filter(row -> screen.matchesGlobalSearch((row.dimension() + " " + row.stage() + " " + row.name()).toLowerCase(Locale.ROOT)))
                .sorted(order.thenComparing(ShaderPassProfiler.PassRow::name)).toList();
        int listY = headerY + 16;
        int listH = Math.max(0, h - (listY - y));
        ctx.enableScissor(x, listY, x + w, listY + listH);
        if (rows.isEmpty()) {
            ctx.text(font, screen.globalSearch.isBlank() ? "No measured pass samples." : "No passes match the universal search.", x + TaskManagerScreen.PADDING, listY + 6, TaskManagerScreen.TEXT_DIM, false);
        }
        int rowY = listY - screen.scrollOffset;
        int rowIndex = 0;
        for (ShaderPassProfiler.PassRow row : rows) {
            if (rowY + 30 > listY && rowY < listY + listH) {
                screen.renderStripedRow(ctx, x, w, rowY, rowIndex, mouseX, mouseY);
                String label = row.dimension() + " / " + row.stage() + " / " + row.name();
                ctx.text(font, font.plainSubstrByWidth(label, Math.max(0, w - 190)), x + TaskManagerScreen.PADDING, rowY + 3, TaskManagerScreen.TEXT_PRIMARY, false);
                ctx.text(font, row.sampledFrames() > 0 ? String.format(Locale.ROOT, "%.3f", row.avgFrameMs()) : "-", x + w - 174, rowY + 3, TaskManagerScreen.TEXT_DIM, false);
                ctx.text(font, row.completed() > 0 ? String.format(Locale.ROOT, "%.3f", row.avgInvocationMs()) : "-", x + w - 108, rowY + 3, TaskManagerScreen.TEXT_DIM, false);
                ctx.text(font, screen.formatCount(row.invocations()), x + w - 52, rowY + 3, TaskManagerScreen.TEXT_DIM, false);
                String detail = "Completed " + row.completed() + " | pending " + row.pending() + " | dropped " + row.dropped() + " | frames " + row.sampledFrames() + " | not executed " + row.didNotExecuteFrames() + " | unavailable frames " + row.unavailableFrames() + " | " + row.status();
                ctx.text(font, font.plainSubstrByWidth(detail, w - 2 * TaskManagerScreen.PADDING), x + TaskManagerScreen.PADDING, rowY + 16, TaskManagerScreen.TEXT_DIM, false);
                screen.addTooltip(x + TaskManagerScreen.PADDING, rowY, w - 2 * TaskManagerScreen.PADDING, 30, label + " - " + detail);
            }
            if (rowY >= listY + listH) break;
            rowY += 30;
            rowIndex++;
        }
        ctx.disableScissor();
    }

}
