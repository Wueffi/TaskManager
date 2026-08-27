package wueffi.taskmanager.client;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import wueffi.taskmanager.client.util.ModIconCache;

import java.util.List;
import java.util.Locale;
import java.util.Map;

final class MemoryTabRenderer {

    private MemoryTabRenderer() {
    }

    static void render(TaskManagerScreen screen, DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        var textRenderer = screen.uiTextRenderer();
        MemoryProfiler.Snapshot memory = screen.snapshot.memory();
        TextureUploadProfiler.Snapshot textureUploads = TextureUploadProfiler.getInstance().getSnapshot();
        Map<String, Long> rawMemoryMods = screen.snapshot.memoryMods();
        Map<String, Long> sharedFamilies = screen.snapshot.sharedMemoryFamilies();
        Map<String, Map<String, Long>> sharedFamilyClasses = screen.snapshot.sharedFamilyClasses();
        Map<String, Map<String, Long>> memoryClassesByMod = screen.snapshot.memoryClassesByMod();
        AttributionModelBuilder.EffectiveMemoryAttribution effectiveMemory = screen.effectiveMemoryAttribution();
        Map<String, Long> memoryMods = screen.memoryEffectiveView ? effectiveMemory.displayBytes() : rawMemoryMods;

        if (screen.selectedSharedFamily == null && !sharedFamilies.isEmpty()) {
            screen.selectedSharedFamily = sharedFamilies.keySet().iterator().next();
        }

        List<String> rows = screen.getMemoryRows(memoryMods, memoryClassesByMod, !screen.memoryEffectiveView && screen.memoryShowSharedRows);
        if (screen.selectedMemoryMod == null || !rows.contains(screen.selectedMemoryMod)) {
            screen.selectedMemoryMod = rows.isEmpty() ? null : rows.getFirst();
        }

        int sharedPanelW = sharedFamilies.isEmpty() ? 0 : Math.min(280, Math.max(220, w / 4));
        int detailH = 116;
        int panelGap = sharedPanelW > 0 ? TaskManagerScreen.PADDING : 0;
        int tableW = w - sharedPanelW - panelGap;
        int left = x + TaskManagerScreen.PADDING;
        int top = y + TaskManagerScreen.PADDING;
        int descriptionBottomY = screen.renderWrappedText(ctx, left, top, Math.max(260, tableW - 16), screen.memoryEffectiveView ? "Effective live heap by mod with shared/runtime buckets folded into concrete mods for comparison. Updated asynchronously." : "Raw live heap by owner/class family. Shared/runtime buckets stay separate until you switch back to Effective view.", TaskManagerScreen.TEXT_DIM);
        ctx.drawText(textRenderer, screen.memoryStatusText(screen.snapshot.memoryAgeMillis()), left, descriptionBottomY + 2, screen.snapshot.memoryAgeMillis() <= 15000 ? TaskManagerScreen.ACCENT_GREEN : TaskManagerScreen.ACCENT_YELLOW, false);

        long heapMax = memory.heapMaxBytes() > 0 ? memory.heapMaxBytes() : memory.heapCommittedBytes();
        double usedPct = heapMax > 0 ? (memory.heapUsedBytes() * 100.0 / heapMax) : 0;

        ctx.drawText(textRenderer, "Tip: Effective view proportionally folds shared/runtime memory into mod rows for readability.", left, descriptionBottomY + 12, TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(left, descriptionBottomY + 12, 430, 10, "Effective view proportionally folds shared/runtime memory into concrete mods. Raw view keeps true-owned and shared buckets separate.");
        int controlsTopY = descriptionBottomY + 26;
        screen.drawTopChip(ctx, x + tableW - 222, controlsTopY, 112, 16, !screen.memoryEffectiveView && screen.memoryShowSharedRows);
        ctx.drawText(textRenderer, screen.memoryShowSharedRows ? "Shared Rows" : "Hide Shared", x + tableW - 206, controlsTopY + 4, (!screen.memoryEffectiveView && screen.memoryShowSharedRows) ? TaskManagerScreen.TEXT_PRIMARY : TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(x + tableW - 222, controlsTopY, 112, 16, "In Raw view, show or hide shared/jvm, shared/framework, and runtime rows. Effective view already folds them into mod rows.");
        screen.drawTopChip(ctx, x + tableW - 112, controlsTopY, 98, 16, screen.memoryEffectiveView);
        ctx.drawText(textRenderer, screen.memoryEffectiveView ? "Effective" : "Raw", x + tableW - 86, controlsTopY + 4, screen.memoryEffectiveView ? TaskManagerScreen.TEXT_PRIMARY : TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(x + tableW - 112, controlsTopY, 98, 16, "Toggle between raw memory ownership and effective ownership with redistributed shared/runtime memory.");
        screen.drawTopChip(ctx, x + tableW - 106, controlsTopY + 18, 98, 16, false);
        ctx.drawText(textRenderer, "Memory Graph", x + tableW - 92, controlsTopY + 22, TaskManagerScreen.TEXT_DIM, false);

        int controlsY = controlsTopY + 42;

        screen.renderSearchBox(ctx, x + tableW - 160, controlsY, 152, 16, "Search mods", screen.memorySearch, screen.focusedSearchTable == TaskManagerScreen.TableId.MEMORY);
        screen.renderResetButton(ctx, x + tableW - 214, controlsY, 48, 16, screen.hasMemoryFilter());
        screen.renderSortSummary(ctx, left, controlsY + 4, "Sort", screen.formatSort(screen.memorySort, screen.memorySortDescending), TaskManagerScreen.TEXT_DIM);
        ctx.drawText(textRenderer, rows.size() + " rows", left + 108, controlsY + 4, TaskManagerScreen.TEXT_DIM, false);

        int barY = controlsY + 24;
        int barW = Math.min(320, tableW - (TaskManagerScreen.PADDING * 2));
        ctx.fill(left, barY, left + barW, barY + 10, 0x33FFFFFF);
        ctx.fill(left, barY, left + (int) (barW * Math.min(1.0, usedPct / 100.0)), barY + 10, usedPct > 85 ? 0x99FF4444 : usedPct > 70 ? 0x99FFB300 : 0x994CAF50);

        ctx.drawText(textRenderer, String.format(Locale.ROOT, "Heap used %.1f MB / allocated %.1f MB | Non-heap %.1f MB | GC %d (%d ms)",
                memory.heapUsedBytes() / (1024.0 * 1024.0),
                memory.heapCommittedBytes() / (1024.0 * 1024.0),
                memory.nonHeapUsedBytes() / (1024.0 * 1024.0),
                memory.gcCount(),
                memory.gcTimeMillis()), left, barY + 16, TaskManagerScreen.TEXT_PRIMARY, false);
        ctx.drawText(textRenderer, String.format(Locale.ROOT, "Young GC %d | Old/Full GC %d | Last pause %d ms | Last GC %s",
                memory.youngGcCount(),
                memory.oldGcCount(),
                memory.gcPauseDurationMs(),
                memory.gcType()), left, barY + 28, TaskManagerScreen.TEXT_DIM, false);
        ctx.drawText(textRenderer, String.format(Locale.ROOT, "Off-heap direct %.1f MB / %s",
                (memory.directBufferBytes() + memory.mappedBufferBytes()) / (1024.0 * 1024.0),
                screen.formatBytesMb(memory.directMemoryMaxBytes())), left, barY + 40, TaskManagerScreen.TEXT_DIM, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth(String.format(Locale.ROOT,
                "Native tracked: direct %.1f MB | mapped %.1f MB | metaspace %.1f MB | code cache %.1f MB",
                memory.directBufferBytes() / (1024.0 * 1024.0),
                memory.mappedBufferBytes() / (1024.0 * 1024.0),
                memory.metaspaceBytes() / (1024.0 * 1024.0),
                memory.codeCacheBytes() / (1024.0 * 1024.0)), tableW - 24), left, barY + 52, TaskManagerScreen.TEXT_DIM, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth(String.format(Locale.ROOT,
                "Buffer pools: direct %d (%s cap) | mapped %d (%s cap)",
                memory.directBufferCount(),
                screen.formatBytesMb(memory.directBufferCapacityBytes()),
                memory.mappedBufferCount(),
                screen.formatBytesMb(memory.mappedBufferCapacityBytes())), tableW - 24), left, barY + 64, TaskManagerScreen.TEXT_DIM, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth("Allocation pressure: " + screen.summarizeAllocationPressure(), tableW - 24), left, barY + 76, TaskManagerScreen.TEXT_DIM, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth(String.format(Locale.ROOT,
                "VRAM tracked: used %s | reserved %s | uploads %s/s | texture uploads %s",
                screen.formatBytesMb(screen.snapshot.systemMetrics().vramUsedBytes()),
                screen.formatBytesMb(screen.snapshot.systemMetrics().vramTotalBytes()),
                screen.formatBytesMb(screen.snapshot.systemMetrics().textureUploadRate()),
                screen.formatBytesMb(textureUploads.totalBytes())), tableW - 24), left, barY + 88, TaskManagerScreen.TEXT_DIM, false);
        String topUploadMods = textureUploads.bytesByMod().entrySet().stream()
                .limit(3)
                .map(entry -> screen.getDisplayName(entry.getKey()) + " " + screen.formatBytesMb(entry.getValue()))
                .reduce((a, b) -> a + " | " + b)
                .orElse("none");
        ctx.drawText(textRenderer, textRenderer.trimToWidth("Top texture upload mods: " + topUploadMods, tableW - 24), left, barY + 100, TaskManagerScreen.TEXT_DIM, false);

        if (sharedPanelW > 0) {
            screen.renderSharedFamiliesPanel(ctx, x + tableW + panelGap, y + TaskManagerScreen.PADDING, sharedPanelW, h - (TaskManagerScreen.PADDING * 2), sharedFamilies);
        }

        if (rows.isEmpty()) {
            ctx.drawText(textRenderer, screen.memorySearch.isBlank() ? "Per-mod memory attribution is still warming up. Open Memory Graph for live JVM totals in the meantime." : "No memory rows match the current search/filter.", left, barY + 62, TaskManagerScreen.TEXT_DIM, false);
            return;
        }

        int headerY = barY + 120;
        ctx.fill(x, headerY, x + tableW, headerY + 14, TaskManagerScreen.HEADER_COLOR);
        ctx.drawText(textRenderer, "MOD", x + TaskManagerScreen.PADDING + 22, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(x + TaskManagerScreen.PADDING + 22, headerY + 1, 44, 14, "Sort by mod display name.");
        int classesX = x + tableW - 140;
        int mbX = x + tableW - 94;
        int pctX = x + tableW - 42;
        if (screen.isColumnVisible(TaskManagerScreen.TableId.MEMORY, "classes")) { ctx.drawText(textRenderer, screen.headerLabel("CLS", screen.memorySort == TaskManagerScreen.MemorySort.CLASS_COUNT, screen.memorySortDescending), classesX, headerY + 3, TaskManagerScreen.TEXT_DIM, false); screen.addTooltip(classesX, headerY + 1, 28, 14, "Distinct live class families attributed to this mod."); }
        if (screen.isColumnVisible(TaskManagerScreen.TableId.MEMORY, "mb")) { ctx.drawText(textRenderer, screen.headerLabel("MB", screen.memorySort == TaskManagerScreen.MemorySort.MEMORY_MB, screen.memorySortDescending), mbX, headerY + 3, TaskManagerScreen.TEXT_DIM, false); screen.addTooltip(mbX, headerY + 1, 22, 14, "Attributed live heap in megabytes."); }
        if (screen.isColumnVisible(TaskManagerScreen.TableId.MEMORY, "pct")) { ctx.drawText(textRenderer, screen.headerLabel("%", screen.memorySort == TaskManagerScreen.MemorySort.PERCENT, screen.memorySortDescending), pctX, headerY + 3, TaskManagerScreen.TEXT_DIM, false); screen.addTooltip(pctX, headerY + 1, 16, 14, screen.memoryEffectiveView ? "Share of effective attributed live heap." : "Share of raw attributed live heap."); }

        long totalAttributedBytes = screen.memoryEffectiveView ? effectiveMemory.totalBytes() : screen.cachedRawMemoryTotalBytes;
        int listY = headerY + 16;
        int listH = h - (listY - y) - detailH;
        ctx.enableScissor(x, listY, x + tableW, listY + listH);

        int rowY = listY - screen.scrollOffset;
        int rowIdx = 0;
        for (String modId : rows) {
            long bytes = memoryMods.getOrDefault(modId, 0L);
            if (rowY + TaskManagerScreen.ATTRIBUTION_ROW_HEIGHT > listY && rowY < listY + listH) {
                screen.renderStripedRowVariable(ctx, x, tableW, rowY, TaskManagerScreen.ATTRIBUTION_ROW_HEIGHT, rowIdx, mouseX, mouseY);
                if (modId.equals(screen.selectedMemoryMod)) {
                    ctx.fill(x, rowY, x + 3, rowY + TaskManagerScreen.ATTRIBUTION_ROW_HEIGHT, TaskManagerScreen.ACCENT_GREEN);
                }
                double mb = bytes / (1024.0 * 1024.0);
                double pct = bytes * 100.0 / totalAttributedBytes;
                int classCount = memoryClassesByMod.getOrDefault(modId, Map.of()).size();
                long rawBytes = rawMemoryMods.getOrDefault(modId, 0L);
                long redistributedBytes = effectiveMemory.redistributedBytesByMod().getOrDefault(modId, 0L);
                String confidence = AttributionInsights.memoryConfidence(modId, rawBytes, bytes, redistributedBytes, screen.snapshot.memoryAgeMillis()).label();
                String provenance = AttributionInsights.memoryProvenance(rawBytes, redistributedBytes, screen.snapshot.memoryAgeMillis());
                int modRight = screen.firstVisibleMetricX(x + tableW - 8, classesX, screen.isColumnVisible(TaskManagerScreen.TableId.MEMORY, "classes"), mbX, screen.isColumnVisible(TaskManagerScreen.TableId.MEMORY, "mb"), pctX, screen.isColumnVisible(TaskManagerScreen.TableId.MEMORY, "pct"));
                int nameX = x + TaskManagerScreen.PADDING + 22;
                int chipWidth = screen.confidenceChipWidth(confidence);
                int chipX = Math.max(nameX + 48, modRight - chipWidth);
                int nameWidth = Math.max(48, chipX - nameX - 6);

                Identifier icon = ModIconCache.getInstance().getIcon(modId);
                ctx.drawTexture(RenderPipelines.GUI_TEXTURED, icon, x + TaskManagerScreen.PADDING, rowY + 6, 0f, 0f, 16, 16, 16, 16, 0xFFFFFFFF);
                ctx.drawText(textRenderer, textRenderer.trimToWidth(screen.getDisplayName(modId), nameWidth), nameX, rowY + 4, TaskManagerScreen.TEXT_PRIMARY, false);
                screen.renderConfidenceChip(ctx, chipX, rowY + 3, confidence);
                ctx.drawText(textRenderer, textRenderer.trimToWidth(provenance, Math.max(60, modRight - nameX)), nameX, rowY + 16, TaskManagerScreen.TEXT_DIM, false);
                if (screen.isColumnVisible(TaskManagerScreen.TableId.MEMORY, "classes")) ctx.drawText(textRenderer, Integer.toString(classCount), classesX, rowY + 9, TaskManagerScreen.TEXT_DIM, false);
                if (screen.isColumnVisible(TaskManagerScreen.TableId.MEMORY, "mb")) ctx.drawText(textRenderer, String.format(Locale.ROOT, "%.1f", mb), mbX, rowY + 9, TaskManagerScreen.TEXT_DIM, false);
                if (screen.isColumnVisible(TaskManagerScreen.TableId.MEMORY, "pct")) ctx.drawText(textRenderer, String.format(Locale.ROOT, "%.1f%%", pct), pctX, rowY + 9, screen.getHeatColor(pct), false);
            }
            if (rowY > listY + listH) break;
            rowY += TaskManagerScreen.ATTRIBUTION_ROW_HEIGHT;
            rowIdx++;
        }
        ctx.disableScissor();

        if (detailH > 0) {
            if (screen.selectedMemoryMod != null) {
                screen.renderMemoryDetailPanel(ctx, x, y + h - detailH, tableW, detailH, screen.selectedMemoryMod, rawMemoryMods.getOrDefault(screen.selectedMemoryMod, 0L), effectiveMemory.displayBytes().getOrDefault(screen.selectedMemoryMod, rawMemoryMods.getOrDefault(screen.selectedMemoryMod, 0L)), memoryMods.getOrDefault(screen.selectedMemoryMod, 0L), memoryClassesByMod.getOrDefault(screen.selectedMemoryMod, Map.of()), effectiveMemory.redistributedBytesByMod().getOrDefault(screen.selectedMemoryMod, 0L), totalAttributedBytes, screen.memoryEffectiveView);
            } else {
                screen.renderSharedFamilyDetail(ctx, x, y + h - detailH, tableW, detailH, sharedFamilyClasses.getOrDefault(screen.selectedSharedFamily, Map.of()));
            }
        }
    }
}
