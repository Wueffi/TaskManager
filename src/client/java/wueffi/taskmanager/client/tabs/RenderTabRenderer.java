package wueffi.taskmanager.client;

import net.minecraft.client.gui.DrawContext;

import java.util.List;
import java.util.Locale;
import java.util.Map;

final class RenderTabRenderer {

    private RenderTabRenderer() {
    }

    static void render(TaskManagerScreen screen, DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        var textRenderer = screen.uiTextRenderer();
        List<String> phases = screen.snapshot.renderPhases().entrySet().stream()
                .filter(entry -> screen.matchesGlobalSearch((entry.getKey() + " " + screen.getDisplayName(entry.getValue().ownerMod() == null ? "shared/render" : entry.getValue().ownerMod())).toLowerCase(Locale.ROOT)))
                .map(Map.Entry::getKey)
                .toList();
        if (phases.isEmpty()) {
            ctx.drawText(textRenderer, screen.globalSearch.isBlank() ? "No render data." : "No render phases match the universal search.", x + TaskManagerScreen.PADDING, y + TaskManagerScreen.PADDING + 4, TaskManagerScreen.TEXT_DIM, false);
            return;
        }

        long totalCpuNanos = screen.snapshot.renderPhases().values().stream().mapToLong(RenderPhaseProfiler.PhaseSnapshot::cpuNanos).sum();
        ctx.drawText(textRenderer, "Owner shows the tagged mod bucket used by GPU attribution. Shared / Render means the phase still falls back to the shared render pool.", x + TaskManagerScreen.PADDING, y + TaskManagerScreen.PADDING, TaskManagerScreen.TEXT_DIM, false);

        int headerY = y + TaskManagerScreen.PADDING + 18;
        ctx.fill(x, headerY, x + w, headerY + 14, TaskManagerScreen.HEADER_COLOR);
        ctx.drawText(textRenderer, "PHASE", x + TaskManagerScreen.PADDING, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(x + TaskManagerScreen.PADDING, headerY + 1, 44, 14, "Render phase name.");
        int ownerX = w - 300;
        int shareX = w - 175;
        int cpuMsX = w - 120;
        int gpuMsX = w - 72;
        int callsX = w - 34;
        ctx.drawText(textRenderer, "OWNER", ownerX, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(ownerX, headerY + 1, 42, 14, "Tagged owner mod for this phase. The GPU tab uses this first before redistributing any leftover shared render work.");
        ctx.drawText(textRenderer, "%CPU", shareX, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(shareX, headerY + 1, 38, 14, "CPU share of this render phase in the current window.");
        ctx.drawText(textRenderer, "CPU", cpuMsX, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(cpuMsX, headerY + 1, 28, 14, "Average CPU milliseconds per call for this phase.");
        ctx.drawText(textRenderer, "GPU", gpuMsX, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(gpuMsX, headerY + 1, 28, 14, "Average GPU milliseconds per call when timer queries are available.");
        ctx.drawText(textRenderer, "C", callsX, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
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
                double avgCpuMs = phaseCalls > 0 ? (phaseSnapshot.cpuNanos() / 1_000_000.0) / phaseCalls : 0;
                double avgGpuMs = phaseCalls > 0 ? (phaseSnapshot.gpuNanos() / 1_000_000.0) / phaseCalls : 0;
                String owner = phaseSnapshot.ownerMod() == null || phaseSnapshot.ownerMod().isBlank() ? "shared/render" : phaseSnapshot.ownerMod();
                String phaseLabel = textRenderer.trimToWidth(phase, Math.max(120, ownerX - (x + TaskManagerScreen.PADDING) - 8));

                ctx.drawText(textRenderer, phaseLabel, x + TaskManagerScreen.PADDING, rowY + 6, TaskManagerScreen.TEXT_PRIMARY, false);
                ctx.drawText(textRenderer, textRenderer.trimToWidth(screen.getDisplayName(owner), Math.max(70, shareX - ownerX - 6)), ownerX, rowY + 6, screen.isSharedAttributionBucket(owner) ? TaskManagerScreen.TEXT_DIM : TaskManagerScreen.TEXT_PRIMARY, false);
                ctx.drawText(textRenderer, String.format("%.1f%%", pct), shareX, rowY + 6, screen.getHeatColor(pct), false);
                ctx.drawText(textRenderer, String.format("%.2f", avgCpuMs), cpuMsX, rowY + 6, TaskManagerScreen.TEXT_DIM, false);
                ctx.drawText(textRenderer, phaseSnapshot.gpuNanos() > 0 ? String.format("%.2f", avgGpuMs) : "-", gpuMsX, rowY + 6, TaskManagerScreen.TEXT_DIM, false);
                ctx.drawText(textRenderer, screen.formatCount(phaseCalls), callsX, rowY + 6, TaskManagerScreen.TEXT_DIM, false);
            }
            if (rowY > listY + listH) {
                break;
            }
            rowY += 20;
            rowIdx++;
        }
        ctx.disableScissor();
    }
}
