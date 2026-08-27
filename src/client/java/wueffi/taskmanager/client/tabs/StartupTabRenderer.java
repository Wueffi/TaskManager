package wueffi.taskmanager.client;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import wueffi.taskmanager.client.util.ModIconCache;

import java.util.List;
import java.util.Locale;

final class StartupTabRenderer {

    private StartupTabRenderer() {
    }

    static void render(TaskManagerScreen screen, DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        var textRenderer = screen.uiTextRenderer();
        screen.beginFullPageScissor(ctx, x, y, w, h);
        int left = x + TaskManagerScreen.PADDING;
        int top = screen.getFullPageScrollTop(y);
        boolean measuredEntrypoints = screen.snapshot.startupRows().stream().anyMatch(StartupTimingProfiler.StartupRow::measuredEntrypoints);
        String startupIntro = measuredEntrypoints
                ? "Measured Fabric startup activity by mod in explicit wall-clock milliseconds. Search, sort, and compare entrypoint timing here."
                : "Observed startup registration timing by mod in explicit wall-clock milliseconds. Search and sort rows to isolate slow paths.";
        top = screen.renderSectionHeader(ctx, left, top, "Startup", startupIntro);

        List<StartupTimingProfiler.StartupRow> rows = screen.getStartupRows();
        long totalSpan = Math.max(screen.snapshot.startupLast() - screen.snapshot.startupFirst(), 1);
        int searchY = top;
        screen.renderSearchBox(ctx, x + w - 160, searchY, 152, 16, "Search mods", screen.startupSearch, screen.startupSearchFocused);
        screen.renderResetButton(ctx, x + w - 214, searchY, 48, 16, screen.hasStartupFilter());
        int sortY = searchY + 20;
        screen.renderSortSummary(ctx, left, sortY + 4, "Sort", screen.formatSort(screen.startupSort, screen.startupSortDescending), TaskManagerScreen.TEXT_DIM);
        ctx.drawText(textRenderer, rows.size() + " mods", left + 132, sortY + 4, TaskManagerScreen.TEXT_DIM, false);

        int headerY = sortY + 20;
        ctx.fill(x, headerY, x + w, headerY + 14, TaskManagerScreen.HEADER_COLOR);
        int regsX = x + w - 34;
        int epX = regsX - 30;
        int activeMsX = epX - 62;
        int endMsX = activeMsX - 56;
        int startMsX = endMsX - 56;
        int barW = Math.max(110, Math.min(180, w / 8));
        int barX = startMsX - barW - 22;
        int nameW = Math.max(150, barX - (left + 32));
        ctx.drawText(textRenderer, screen.headerLabel("MOD", screen.startupSort == TaskManagerScreen.StartupSort.NAME, screen.startupSortDescending), left + 22, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(left + 22, headerY + 1, 44, 14, "Sort by mod display name.");
        ctx.drawText(textRenderer, "TIMELINE", barX, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(barX, headerY + 1, 64, 14, "Observed startup span across the global startup window.");
        ctx.drawText(textRenderer, screen.headerLabel("START", screen.startupSort == TaskManagerScreen.StartupSort.START, screen.startupSortDescending), startMsX, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(startMsX, headerY + 1, 42, 14, "Milliseconds from startup begin until this mod first became active.");
        ctx.drawText(textRenderer, screen.headerLabel("END", screen.startupSort == TaskManagerScreen.StartupSort.END, screen.startupSortDescending), endMsX, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(endMsX, headerY + 1, 34, 14, "Milliseconds from startup begin until this mod last appeared active.");
        ctx.drawText(textRenderer, screen.headerLabel("ACTIVE", screen.startupSort == TaskManagerScreen.StartupSort.ACTIVE, screen.startupSortDescending), activeMsX, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(activeMsX, headerY + 1, 48, 14, "Measured active wall-clock milliseconds attributed to this mod.");
        ctx.drawText(textRenderer, screen.headerLabel("EP", screen.startupSort == TaskManagerScreen.StartupSort.ENTRYPOINTS, screen.startupSortDescending), epX, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(epX, headerY + 1, 18, 14, "Entrypoint count observed for this mod.");
        ctx.drawText(textRenderer, screen.headerLabel("REG", screen.startupSort == TaskManagerScreen.StartupSort.REGISTRATIONS, screen.startupSortDescending), regsX, headerY + 3, TaskManagerScreen.TEXT_DIM, false);
        screen.addTooltip(regsX, headerY + 1, 24, 14, "Registration events observed during startup fallback timing.");

        int listY = headerY + 16;
        int listH = h - (listY - y) - 16;
        if (rows.isEmpty()) {
            ctx.drawText(textRenderer, screen.startupSearch.isBlank() ? "No startup data captured yet." : "No startup rows match the current search/filter.", left, listY + 6, TaskManagerScreen.TEXT_DIM, false);
        } else {
            ctx.enableScissor(x, listY, x + w, listY + listH);
            int rowY = listY - screen.scrollOffset;
            int rowIdx = 0;
            for (StartupTimingProfiler.StartupRow row : rows) {
                if (rowY + 28 > listY && rowY < listY + listH) {
                    screen.renderStripedRowVariable(ctx, x, w, rowY, 28, rowIdx, mouseX, mouseY);
                    Identifier icon = ModIconCache.getInstance().getIcon(row.modId());
                    ctx.drawTexture(RenderPipelines.GUI_TEXTURED, icon, left, rowY + 5, 0f, 0f, 16, 16, 16, 16, 0xFFFFFFFF);
                    ctx.drawText(textRenderer, textRenderer.trimToWidth(screen.getDisplayName(row.modId()), nameW), left + 22, rowY + 3, TaskManagerScreen.TEXT_PRIMARY, false);
                    String startupMeta = row.measuredEntrypoints() ? row.stageSummary() : "fallback registration timing";
                    String startupHint = row.definitionSummary().isBlank() ? startupMeta : (startupMeta + " | " + row.definitionSummary());
                    ctx.drawText(textRenderer, textRenderer.trimToWidth(startupHint, nameW), left + 22, rowY + 14, TaskManagerScreen.TEXT_DIM, false);

                    int barStart = (int) ((row.first() - screen.snapshot.startupFirst()) * barW / totalSpan);
                    int barLen = Math.max(1, (int) ((row.last() - row.first()) * barW / totalSpan));
                    ctx.fill(barX, rowY + 11, barX + barW, rowY + 16, 0x33FFFFFF);
                    ctx.fill(barX + barStart, rowY + 10, Math.min(barX + barW, barX + barStart + barLen), rowY + 17, TaskManagerScreen.ACCENT_YELLOW);

                    double startMs = (row.first() - screen.snapshot.startupFirst()) / 1_000_000.0;
                    double endMs = (row.last() - screen.snapshot.startupFirst()) / 1_000_000.0;
                    double activeMs = row.activeNanos() / 1_000_000.0;
                    ctx.drawText(textRenderer, String.format(Locale.ROOT, "%.1f", startMs), startMsX, rowY + 8, TaskManagerScreen.TEXT_DIM, false);
                    ctx.drawText(textRenderer, String.format(Locale.ROOT, "%.1f", endMs), endMsX, rowY + 8, TaskManagerScreen.TEXT_DIM, false);
                    ctx.drawText(textRenderer, String.format(Locale.ROOT, "%.1f", activeMs), activeMsX, rowY + 8, TaskManagerScreen.ACCENT_YELLOW, false);
                    ctx.drawText(textRenderer, String.valueOf(row.entrypoints()), epX, rowY + 8, TaskManagerScreen.TEXT_DIM, false);
                    ctx.drawText(textRenderer, String.valueOf(row.registrations()), regsX, rowY + 8, TaskManagerScreen.TEXT_DIM, false);
                }
                if (rowY > listY + listH) break;
                rowY += 28;
                rowIdx++;
            }
            ctx.disableScissor();
        }

        ctx.fill(x, y + h - 14, x + w, y + h, TaskManagerScreen.HEADER_COLOR);
        ctx.drawText(textRenderer, String.format(Locale.ROOT, "Startup span %.1f ms | %d mods | %s", totalSpan / 1_000_000.0, screen.snapshot.startupRows().size(), measuredEntrypoints ? "measured entrypoints" : "fallback registration path"), left, y + h - 10, TaskManagerScreen.TEXT_DIM, false);
        ctx.disableScissor();
    }
}
