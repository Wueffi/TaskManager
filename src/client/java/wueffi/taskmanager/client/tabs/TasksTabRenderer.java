package wueffi.taskmanager.client;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import wueffi.taskmanager.client.util.ModIconCache;
import wueffi.taskmanager.client.util.ModTimingSnapshot;

import java.util.List;
import java.util.Locale;
import java.util.Map;

final class TasksTabRenderer {

    private TasksTabRenderer() {
    }

    static void render(TaskManagerScreen screen, DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        var textRenderer = screen.uiTextRenderer();
        Map<String, CpuSamplingProfiler.Snapshot> cpu = screen.snapshot.cpuMods();
        Map<String, CpuSamplingProfiler.DetailSnapshot> cpuDetails = screen.snapshot.cpuDetails();
        Map<String, ModTimingSnapshot> invokes = screen.snapshot.modInvokes();
        AttributionModelBuilder.EffectiveCpuAttribution effectiveCpu = screen.effectiveCpuAttribution();
        Map<String, CpuSamplingProfiler.Snapshot> displayCpu = screen.taskEffectiveView ? effectiveCpu.displaySnapshots() : cpu;
        List<String> rows = screen.getTaskRows(displayCpu, cpuDetails, invokes, !screen.taskEffectiveView && screen.taskShowSharedRows);

        int detailW = Math.min(420, Math.max(320, w / 3));
        int gap = TaskManagerScreen.PADDING;
        int listW = w - detailW - gap;
        int infoY = y + TaskManagerScreen.PADDING;
        int descriptionBottomY = screen.renderWrappedText(ctx, x + TaskManagerScreen.PADDING, infoY, Math.max(260, listW - 16), screen.taskEffectiveView ? "Effective CPU share by mod from rolling sampled stack windows. Shared/framework work is folded into concrete mods for comparison." : "Raw CPU ownership by mod from rolling sampled stack windows. Shared/framework buckets stay separate until you switch back to Effective view.", TaskManagerScreen.TEXT_DIM);
        ctx.drawText(textRenderer, screen.cpuStatusText(screen.snapshot.cpuReady(), screen.snapshot.totalCpuSamples(), screen.snapshot.cpuSampleAgeMillis()), x + TaskManagerScreen.PADDING, descriptionBottomY + 2, screen.getCpuStatusColor(screen.snapshot.cpuReady()), false);
        ctx.drawText(textRenderer, "Tip: Effective view proportionally folds shared/runtime work into mod rows for readability.", x + TaskManagerScreen.PADDING, descriptionBottomY + 12, TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(x + TaskManagerScreen.PADDING, descriptionBottomY + 12, 420, 10, "Effective view proportionally folds shared/runtime work into concrete mods. Raw view keeps true-owned and shared buckets separate.");
        int controlsY = descriptionBottomY + 26;
        screen.drawTopChip(ctx, x + TaskManagerScreen.PADDING, controlsY, 78, 16, false);
        ctx.drawText(textRenderer, "CPU Graph", x + TaskManagerScreen.PADDING + 18, controlsY + 4, TaskManagerScreen.TEXT_DIM, false);
        screen.drawTopChip(ctx, x + TaskManagerScreen.PADDING + 84, controlsY, 98, 16, screen.taskEffectiveView);
        ctx.drawText(textRenderer, screen.taskEffectiveView ? "Effective" : "Raw", x + TaskManagerScreen.PADDING + 110, controlsY + 4, screen.taskEffectiveView ? TaskManagerScreen.TEXT_PRIMARY : TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(x + TaskManagerScreen.PADDING + 84, controlsY, 98, 16, "Toggle between raw ownership and effective ownership with redistributed shared/framework samples.");
        screen.drawTopChip(ctx, x + TaskManagerScreen.PADDING + 188, controlsY, 112, 16, !screen.taskEffectiveView && screen.taskShowSharedRows);
        ctx.drawText(textRenderer, screen.taskShowSharedRows ? "Shared Rows" : "Hide Shared", x + TaskManagerScreen.PADDING + 204, controlsY + 4, (!screen.taskEffectiveView && screen.taskShowSharedRows) ? TaskManagerScreen.TEXT_PRIMARY : TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(x + TaskManagerScreen.PADDING + 188, controlsY, 112, 16, "In Raw view, show or hide shared/jvm, shared/framework, and runtime rows. Effective view already folds them into mod rows.");
        screen.renderSearchBox(ctx, x + listW - 160, controlsY, 152, 16, "Search mods", screen.tasksSearch, screen.focusedSearchTable == TaskManagerScreen.TableId.TASKS);
        screen.renderResetButton(ctx, x + listW - 214, controlsY, 48, 16, screen.hasTaskFilter());
        screen.renderSortSummary(ctx, x + TaskManagerScreen.PADDING, controlsY + 22, "Sort", screen.formatSort(screen.taskSort, screen.taskSortDescending), TaskManagerScreen.TEXT_DIM);
        ctx.drawText(textRenderer, rows.size() + " rows", x + TaskManagerScreen.PADDING + 108, controlsY + 22, TaskManagerScreen.TEXT_DIM, false);

        if (!rows.isEmpty() && (screen.selectedTaskMod == null || !rows.contains(screen.selectedTaskMod))) {
            screen.selectedTaskMod = rows.getFirst();
        }

        int headerY = controlsY + 42;
        ctx.fill(x, headerY, x + listW, headerY + 14, TaskManagerScreen.HEADER_COLOR);
        ctx.drawText(textRenderer, screen.headerLabel("MOD", screen.taskSort == TaskManagerScreen.TaskSort.NAME, screen.taskSortDescending), x + TaskManagerScreen.PADDING + 16 + 6, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(x + TaskManagerScreen.PADDING + 16 + 6, headerY + 1, 44, 14, "Sort by mod display name.");
        int pctX = x + listW - 206;
        int threadsX = x + listW - 146;
        int samplesX = x + listW - 92;
        int invokesX = x + listW - 42;
        if (screen.isColumnVisible(TaskManagerScreen.TableId.TASKS, "cpu")) { ctx.drawText(textRenderer, screen.headerLabel("%CPU", screen.taskSort == TaskManagerScreen.TaskSort.CPU, screen.taskSortDescending), pctX, headerY + 3, TaskManagerScreen.TEXT_DIM, false); screen.addTooltip(pctX, headerY + 1, 42, 14, "Sampled CPU share from rolling stack windows."); }
        if (screen.isColumnVisible(TaskManagerScreen.TableId.TASKS, "threads")) { ctx.drawText(textRenderer, screen.headerLabel("THREADS", screen.taskSort == TaskManagerScreen.TaskSort.THREADS, screen.taskSortDescending), threadsX, headerY + 3, TaskManagerScreen.TEXT_DIM, false); screen.addTooltip(threadsX, headerY + 1, 58, 14, "Distinct sampled threads attributed to this mod."); }
        if (screen.isColumnVisible(TaskManagerScreen.TableId.TASKS, "samples")) { ctx.drawText(textRenderer, screen.headerLabel("SAMPLES", screen.taskSort == TaskManagerScreen.TaskSort.SAMPLES, screen.taskSortDescending), samplesX, headerY + 3, TaskManagerScreen.TEXT_DIM, false); screen.addTooltip(samplesX, headerY + 1, 56, 14, "Total CPU samples attributed in the rolling window."); }
        if (screen.isColumnVisible(TaskManagerScreen.TableId.TASKS, "invokes")) { ctx.drawText(textRenderer, screen.headerLabel("INVOKES", screen.taskSort == TaskManagerScreen.TaskSort.INVOKES, screen.taskSortDescending), invokesX, headerY + 3, TaskManagerScreen.TEXT_DIM, false); screen.addTooltip(invokesX, headerY + 1, 54, 14, "Tracked event invokes, shown separately from sampled CPU ownership."); }

        int listY = headerY + 16;
        int listH = h - (listY - y);
        if (rows.isEmpty()) {
            ctx.drawText(textRenderer, screen.tasksSearch.isBlank() ? "Waiting for CPU samples..." : "No task rows match the current search/filter.", x + TaskManagerScreen.PADDING, listY + 6, TaskManagerScreen.TEXT_DIM, false);
        } else {
            ctx.enableScissor(x, listY, x + listW, listY + listH);
            int rowY = listY - screen.scrollOffset;
            int rowIdx = 0;
            for (String modId : rows) {
                if (rowY + TaskManagerScreen.ATTRIBUTION_ROW_HEIGHT > listY && rowY < listY + listH) {
                    screen.renderStripedRowVariable(ctx, x, listW, rowY, TaskManagerScreen.ATTRIBUTION_ROW_HEIGHT, rowIdx, mouseX, mouseY);
                    if (modId.equals(screen.selectedTaskMod)) {
                        ctx.fill(x, rowY, x + 3, rowY + TaskManagerScreen.ATTRIBUTION_ROW_HEIGHT, TaskManagerScreen.ACCENT_GREEN);
                    }
                    Identifier icon = ModIconCache.getInstance().getIcon(modId);
                    ctx.drawTexture(RenderPipelines.GUI_TEXTURED, icon, x + TaskManagerScreen.PADDING, rowY + 6, 0f, 0f, 16, 16, 16, 16, 0xFFFFFFFF);

                    CpuSamplingProfiler.Snapshot cpuSnapshot = displayCpu.getOrDefault(modId, new CpuSamplingProfiler.Snapshot(0, 0, 0));
                    CpuSamplingProfiler.DetailSnapshot detailSnapshot = cpuDetails.get(modId);
                    CpuSamplingProfiler.Snapshot rawCpuSnapshot = cpu.getOrDefault(modId, new CpuSamplingProfiler.Snapshot(0, 0, 0));
                    long invokesCount = invokes.getOrDefault(modId, new ModTimingSnapshot(0, 0)).calls();
                    double pct = screen.cpuMetricValue(cpuSnapshot) * 100.0 / Math.max(1L, screen.taskEffectiveView ? screen.totalCpuMetric(displayCpu) : screen.totalCpuMetric(cpu));
                    int threadCount = detailSnapshot == null ? 0 : detailSnapshot.sampledThreadCount();
                    long redistributedSamples = effectiveCpu.redistributedSamplesByMod().getOrDefault(modId, 0L);
                    String confidence = AttributionInsights.cpuConfidence(modId, detailSnapshot, rawCpuSnapshot.totalSamples(), cpuSnapshot.totalSamples(), redistributedSamples).label();
                    String provenance = AttributionInsights.cpuProvenance(rawCpuSnapshot.totalSamples(), redistributedSamples, detailSnapshot);
                    int modRight = screen.firstVisibleMetricX(x + listW - 8, pctX, screen.isColumnVisible(TaskManagerScreen.TableId.TASKS, "cpu"), threadsX, screen.isColumnVisible(TaskManagerScreen.TableId.TASKS, "threads"), samplesX, screen.isColumnVisible(TaskManagerScreen.TableId.TASKS, "samples"), invokesX, screen.isColumnVisible(TaskManagerScreen.TableId.TASKS, "invokes"));
                    int nameX = x + TaskManagerScreen.PADDING + 16 + 6;
                    int chipWidth = screen.confidenceChipWidth(confidence);
                    int chipX = Math.max(nameX + 48, modRight - chipWidth);
                    int nameWidth = Math.max(48, chipX - nameX - 6);
                    ctx.drawText(textRenderer, textRenderer.trimToWidth(screen.getDisplayName(modId), nameWidth), nameX, rowY + 4, TaskManagerScreen.TEXT_PRIMARY, false);
                    screen.renderConfidenceChip(ctx, chipX, rowY + 3, confidence);
                    ctx.drawText(textRenderer, textRenderer.trimToWidth(provenance, Math.max(60, modRight - nameX)), nameX, rowY + 16, TaskManagerScreen.TEXT_DIM, false);
                    if (screen.isColumnVisible(TaskManagerScreen.TableId.TASKS, "cpu")) ctx.drawText(textRenderer, String.format(Locale.ROOT, "%.1f%%", pct), pctX, rowY + 9, screen.getHeatColor(pct), false);
                    if (screen.isColumnVisible(TaskManagerScreen.TableId.TASKS, "threads")) ctx.drawText(textRenderer, Integer.toString(threadCount), threadsX, rowY + 9, TaskManagerScreen.TEXT_DIM, false);
                    if (screen.isColumnVisible(TaskManagerScreen.TableId.TASKS, "samples")) ctx.drawText(textRenderer, screen.formatCount(cpuSnapshot.totalSamples()), samplesX, rowY + 9, TaskManagerScreen.TEXT_DIM, false);
                    if (screen.isColumnVisible(TaskManagerScreen.TableId.TASKS, "invokes")) ctx.drawText(textRenderer, screen.formatCount(invokesCount), invokesX, rowY + 9, TaskManagerScreen.TEXT_DIM, false);
                }
                if (rowY > listY + listH) break;
                rowY += TaskManagerScreen.ATTRIBUTION_ROW_HEIGHT;
                rowIdx++;
            }
            ctx.disableScissor();
        }

        screen.renderCpuDetailPanel(ctx, x + listW + gap, y + TaskManagerScreen.PADDING, detailW, h - (TaskManagerScreen.PADDING * 2), screen.selectedTaskMod, screen.selectedTaskMod == null ? null : cpu.get(screen.selectedTaskMod), screen.selectedTaskMod == null ? null : effectiveCpu.displaySnapshots().get(screen.selectedTaskMod), screen.selectedTaskMod == null ? null : displayCpu.get(screen.selectedTaskMod), screen.selectedTaskMod == null ? 0L : effectiveCpu.redistributedSamplesByMod().getOrDefault(screen.selectedTaskMod, 0L), screen.selectedTaskMod == null ? null : cpuDetails.get(screen.selectedTaskMod), screen.selectedTaskMod == null ? null : invokes.get(screen.selectedTaskMod), screen.taskEffectiveView ? screen.totalCpuMetric(effectiveCpu.displaySnapshots()) : screen.cachedRawCpuTotalMetric, screen.taskEffectiveView);
    }
}
