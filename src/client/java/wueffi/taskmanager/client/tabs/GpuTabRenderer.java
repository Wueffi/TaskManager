package wueffi.taskmanager.client;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import wueffi.taskmanager.client.util.ModIconCache;

import java.util.List;
import java.util.Locale;
import java.util.Map;

final class GpuTabRenderer {

    private GpuTabRenderer() {
    }

    static void render(TaskManagerScreen screen, DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        var textRenderer = screen.uiTextRenderer();
        Map<String, CpuSamplingProfiler.DetailSnapshot> cpuDetails = screen.snapshot.cpuDetails();
        AttributionModelBuilder.EffectiveGpuAttribution rawGpu = screen.rawGpuAttribution();
        AttributionModelBuilder.EffectiveGpuAttribution displayGpu = screen.gpuEffectiveView ? screen.effectiveGpuAttribution() : rawGpu;
        List<String> rows = screen.getGpuRows(displayGpu, cpuDetails, !screen.gpuEffectiveView && screen.gpuShowSharedRows);

        int detailW = Math.min(420, Math.max(320, w / 3));
        int gap = TaskManagerScreen.PADDING;
        int listW = w - detailW - gap;
        int infoY = y + TaskManagerScreen.PADDING;
        int descriptionBottomY = screen.renderWrappedText(ctx, x + TaskManagerScreen.PADDING, infoY, Math.max(260, listW - 16), screen.gpuEffectiveView ? "Estimated GPU share by tagged render phases with shared render work folded into concrete mods." : "Raw GPU ownership by tagged render phases. Shared render buckets stay separate until you switch back to Effective view.", TaskManagerScreen.TEXT_DIM);
        ctx.drawText(textRenderer, screen.cpuStatusText(screen.snapshot.gpuReady(), displayGpu.totalRenderSamples(), screen.snapshot.cpuSampleAgeMillis()), x + TaskManagerScreen.PADDING, descriptionBottomY + 2, screen.getGpuStatusColor(screen.snapshot.gpuReady()), false);
        ctx.drawText(textRenderer, "Tip: phase ownership assigns direct GPU time first, then Effective view redistributes leftover shared render work.", x + TaskManagerScreen.PADDING, descriptionBottomY + 12, TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(x + TaskManagerScreen.PADDING, descriptionBottomY + 12, 460, 10, "GPU rows now use tagged render-phase ownership. Effective view redistributes leftover shared render work into concrete mods for readability.");
        int controlsY = descriptionBottomY + 26;
        screen.drawTopChip(ctx, x + TaskManagerScreen.PADDING, controlsY, 78, 16, false);
        ctx.drawText(textRenderer, "GPU Graph", x + TaskManagerScreen.PADDING + 18, controlsY + 4, TaskManagerScreen.TEXT_DIM, false);
        screen.drawTopChip(ctx, x + TaskManagerScreen.PADDING + 84, controlsY, 98, 16, screen.gpuEffectiveView);
        ctx.drawText(textRenderer, screen.gpuEffectiveView ? "Effective" : "Raw", x + TaskManagerScreen.PADDING + 110, controlsY + 4, screen.gpuEffectiveView ? TaskManagerScreen.TEXT_PRIMARY : TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(x + TaskManagerScreen.PADDING + 84, controlsY, 98, 16, "Toggle between raw tagged ownership and effective ownership with redistributed shared render work.");
        screen.drawTopChip(ctx, x + TaskManagerScreen.PADDING + 188, controlsY, 112, 16, !screen.gpuEffectiveView && screen.gpuShowSharedRows);
        ctx.drawText(textRenderer, screen.gpuShowSharedRows ? "Shared Rows" : "Hide Shared", x + TaskManagerScreen.PADDING + 204, controlsY + 4, (!screen.gpuEffectiveView && screen.gpuShowSharedRows) ? TaskManagerScreen.TEXT_PRIMARY : TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(x + TaskManagerScreen.PADDING + 188, controlsY, 112, 16, "In Raw view, show or hide shared/render rows. Effective view already folds them into mod rows.");
        screen.renderSearchBox(ctx, x + listW - 160, controlsY, 152, 16, "Search mods", screen.gpuSearch, screen.focusedSearchTable == TaskManagerScreen.TableId.GPU);
        screen.renderResetButton(ctx, x + listW - 214, controlsY, 48, 16, screen.hasGpuFilter());
        screen.renderSortSummary(ctx, x + TaskManagerScreen.PADDING, controlsY + 22, "Sort", screen.formatSort(screen.gpuSort, screen.gpuSortDescending), TaskManagerScreen.TEXT_DIM);
        ctx.drawText(textRenderer, rows.size() + " rows", x + TaskManagerScreen.PADDING + 108, controlsY + 22, TaskManagerScreen.TEXT_DIM, false);

        if (displayGpu.totalGpuNanos() <= 0L) {
            ctx.drawText(textRenderer, "No GPU attribution yet. Render some frames with timer queries enabled.", x + TaskManagerScreen.PADDING, infoY + 52, TaskManagerScreen.TEXT_DIM, false);
            screen.renderGpuDetailPanel(ctx, x + listW + gap, y + TaskManagerScreen.PADDING, detailW, h - (TaskManagerScreen.PADDING * 2), screen.selectedGpuMod, 0L, 0L, 0L, 0L, 0L, 0L, screen.selectedGpuMod == null ? null : cpuDetails.get(screen.selectedGpuMod), displayGpu.totalRenderSamples(), displayGpu.totalGpuNanos(), screen.gpuEffectiveView);
            return;
        }

        if (!rows.isEmpty() && (screen.selectedGpuMod == null || !rows.contains(screen.selectedGpuMod))) {
            screen.selectedGpuMod = rows.getFirst();
        }

        int headerY = controlsY + 42;
        ctx.fill(x, headerY, x + listW, headerY + 14, TaskManagerScreen.HEADER_COLOR);
        ctx.drawText(textRenderer, screen.headerLabel("MOD", screen.gpuSort == TaskManagerScreen.GpuSort.NAME, screen.gpuSortDescending), x + TaskManagerScreen.PADDING + 16 + 6, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(x + TaskManagerScreen.PADDING + 16 + 6, headerY + 1, 44, 14, "Sort by mod display name.");
        int pctX = x + listW - 232;
        int threadsX = x + listW - 172;
        int gpuMsX = x + listW - 108;
        int renderSamplesX = x + listW - 42;
        if (screen.isColumnVisible(TaskManagerScreen.TableId.GPU, "pct")) { ctx.drawText(textRenderer, screen.headerLabel("EST %GPU", screen.gpuSort == TaskManagerScreen.GpuSort.EST_GPU, screen.gpuSortDescending), pctX, headerY + 3, TaskManagerScreen.TEXT_DIM, false); screen.addTooltip(pctX, headerY + 1, 58, 14, screen.gpuEffectiveView ? "Tagged GPU share in the effective ownership view." : "Tagged GPU share in the raw ownership view."); }
        if (screen.isColumnVisible(TaskManagerScreen.TableId.GPU, "threads")) { ctx.drawText(textRenderer, screen.headerLabel("THREADS", screen.gpuSort == TaskManagerScreen.GpuSort.THREADS, screen.gpuSortDescending), threadsX, headerY + 3, TaskManagerScreen.TEXT_DIM, false); screen.addTooltip(threadsX, headerY + 1, 58, 14, "Distinct sampled render threads contributing to this row."); }
        if (screen.isColumnVisible(TaskManagerScreen.TableId.GPU, "gpums")) { ctx.drawText(textRenderer, screen.headerLabel("Est ms", screen.gpuSort == TaskManagerScreen.GpuSort.GPU_MS, screen.gpuSortDescending), gpuMsX, headerY + 3, TaskManagerScreen.TEXT_DIM, false); screen.addTooltip(gpuMsX, headerY + 1, 48, 14, "Attributed GPU milliseconds in the rolling window."); }
        if (screen.isColumnVisible(TaskManagerScreen.TableId.GPU, "rsamples")) { ctx.drawText(textRenderer, screen.headerLabel("R.S", screen.gpuSort == TaskManagerScreen.GpuSort.RENDER_SAMPLES, screen.gpuSortDescending), renderSamplesX, headerY + 3, TaskManagerScreen.TEXT_DIM, false); screen.addTooltip(renderSamplesX, headerY + 1, 26, 14, "Render samples attributed to this row."); }

        int listY = headerY + 16;
        int listH = h - (listY - y);
        if (rows.isEmpty()) {
            ctx.drawText(textRenderer, screen.gpuSearch.isBlank() ? "Waiting for render-thread samples..." : "No GPU rows match the current search/filter.", x + TaskManagerScreen.PADDING, listY + 6, TaskManagerScreen.TEXT_DIM, false);
        } else {
            ctx.enableScissor(x, listY, x + listW, listY + listH);
            int rowY = listY - screen.scrollOffset;
            int rowIdx = 0;
            for (String modId : rows) {
                if (rowY + TaskManagerScreen.ATTRIBUTION_ROW_HEIGHT > listY && rowY < listY + listH) {
                    screen.renderStripedRowVariable(ctx, x, listW, rowY, TaskManagerScreen.ATTRIBUTION_ROW_HEIGHT, rowIdx, mouseX, mouseY);
                    if (modId.equals(screen.selectedGpuMod)) {
                        ctx.fill(x, rowY, x + 3, rowY + TaskManagerScreen.ATTRIBUTION_ROW_HEIGHT, TaskManagerScreen.ACCENT_GREEN);
                    }
                    long gpuNanos = displayGpu.gpuNanosByMod().getOrDefault(modId, 0L);
                    long renderSamples = displayGpu.renderSamplesByMod().getOrDefault(modId, 0L);
                    long rawGpuNanos = rawGpu.gpuNanosByMod().getOrDefault(modId, 0L);
                    long rawRenderSamples = rawGpu.renderSamplesByMod().getOrDefault(modId, 0L);
                    CpuSamplingProfiler.DetailSnapshot detailSnapshot = cpuDetails.get(modId);
                    double pct = gpuNanos * 100.0 / Math.max(1L, displayGpu.totalGpuNanos());
                    double gpuMs = gpuNanos / 1_000_000.0;
                    int threadCount = detailSnapshot == null ? 0 : detailSnapshot.sampledThreadCount();
                    long redistributedGpuNanos = displayGpu.redistributedGpuNanosByMod().getOrDefault(modId, 0L);
                    String confidence = AttributionInsights.gpuConfidence(modId, rawGpuNanos, gpuNanos, redistributedGpuNanos, rawRenderSamples, renderSamples).label();
                    String provenance = AttributionInsights.gpuProvenance(rawGpuNanos, redistributedGpuNanos, rawRenderSamples, renderSamples);
                    int modRight = screen.firstVisibleMetricX(x + listW - 8, pctX, screen.isColumnVisible(TaskManagerScreen.TableId.GPU, "pct"), threadsX, screen.isColumnVisible(TaskManagerScreen.TableId.GPU, "threads"), gpuMsX, screen.isColumnVisible(TaskManagerScreen.TableId.GPU, "gpums"), renderSamplesX, screen.isColumnVisible(TaskManagerScreen.TableId.GPU, "rsamples"));
                    int nameX = x + TaskManagerScreen.PADDING + 16 + 6;
                    int chipWidth = screen.confidenceChipWidth(confidence);
                    int chipX = Math.max(nameX + 48, modRight - chipWidth);
                    int nameWidth = Math.max(48, chipX - nameX - 6);

                    Identifier icon = ModIconCache.getInstance().getIcon(modId);
                    ctx.drawTexture(RenderPipelines.GUI_TEXTURED, icon, x + TaskManagerScreen.PADDING, rowY + 6, 0f, 0f, 16, 16, 16, 16, 0xFFFFFFFF);
                    ctx.drawText(textRenderer, textRenderer.trimToWidth(screen.getDisplayName(modId), nameWidth), nameX, rowY + 4, TaskManagerScreen.TEXT_PRIMARY, false);
                    screen.renderConfidenceChip(ctx, chipX, rowY + 3, confidence);
                    ctx.drawText(textRenderer, textRenderer.trimToWidth(provenance, Math.max(60, modRight - nameX)), nameX, rowY + 16, TaskManagerScreen.TEXT_DIM, false);
                    if (screen.isColumnVisible(TaskManagerScreen.TableId.GPU, "pct")) ctx.drawText(textRenderer, String.format(Locale.ROOT, "%.1f%%", pct), pctX, rowY + 9, screen.getHeatColor(pct), false);
                    if (screen.isColumnVisible(TaskManagerScreen.TableId.GPU, "threads")) ctx.drawText(textRenderer, Integer.toString(threadCount), threadsX, rowY + 9, TaskManagerScreen.TEXT_DIM, false);
                    if (screen.isColumnVisible(TaskManagerScreen.TableId.GPU, "gpums")) ctx.drawText(textRenderer, String.format(Locale.ROOT, "%.2f", gpuMs), gpuMsX, rowY + 9, TaskManagerScreen.TEXT_DIM, false);
                    if (screen.isColumnVisible(TaskManagerScreen.TableId.GPU, "rsamples")) ctx.drawText(textRenderer, screen.formatCount(renderSamples), renderSamplesX, rowY + 9, TaskManagerScreen.TEXT_DIM, false);
                }
                if (rowY > listY + listH) break;
                rowY += TaskManagerScreen.ATTRIBUTION_ROW_HEIGHT;
                rowIdx++;
            }
            ctx.disableScissor();
        }

        screen.renderGpuDetailPanel(ctx, x + listW + gap, y + TaskManagerScreen.PADDING, detailW, h - (TaskManagerScreen.PADDING * 2), screen.selectedGpuMod,
                screen.selectedGpuMod == null ? 0L : rawGpu.gpuNanosByMod().getOrDefault(screen.selectedGpuMod, 0L),
                screen.selectedGpuMod == null ? 0L : displayGpu.gpuNanosByMod().getOrDefault(screen.selectedGpuMod, 0L),
                screen.selectedGpuMod == null ? 0L : rawGpu.renderSamplesByMod().getOrDefault(screen.selectedGpuMod, 0L),
                screen.selectedGpuMod == null ? 0L : displayGpu.renderSamplesByMod().getOrDefault(screen.selectedGpuMod, 0L),
                screen.selectedGpuMod == null ? 0L : displayGpu.redistributedGpuNanosByMod().getOrDefault(screen.selectedGpuMod, 0L),
                screen.selectedGpuMod == null ? 0L : displayGpu.redistributedRenderSamplesByMod().getOrDefault(screen.selectedGpuMod, 0L),
                screen.selectedGpuMod == null ? null : cpuDetails.get(screen.selectedGpuMod), displayGpu.totalRenderSamples(), displayGpu.totalGpuNanos(), screen.gpuEffectiveView);
    }
}
