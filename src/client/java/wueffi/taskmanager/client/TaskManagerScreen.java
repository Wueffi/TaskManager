package wueffi.taskmanager.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import wueffi.taskmanager.client.util.ModIconCache;
import wueffi.taskmanager.client.util.ModTimingSnapshot;

import java.util.Map;

public class TaskManagerScreen extends Screen {

    private static final int TAB_HEIGHT    = 24;
    private static final int PADDING       = 8;
    private static final int ROW_HEIGHT    = 20;
    private static final int ICON_SIZE     = 16;

    private static final int BG_COLOR      = 0xE0101010;
    private static final int PANEL_COLOR   = 0xCC1A1A1A;
    private static final int TAB_ACTIVE    = 0xFF2A2A2A;
    private static final int TAB_INACTIVE  = 0xFF161616;
    private static final int BORDER_COLOR  = 0xFF3A3A3A;
    private static final int TEXT_PRIMARY  = 0xFFE0E0E0;
    private static final int TEXT_DIM      = 0xFF888888;
    private static final int ACCENT_GREEN  = 0xFF4CAF50;
    private static final int ACCENT_YELLOW = 0xFFFFB300;
    private static final int HEADER_COLOR  = 0xFF222222;
    private static final int ROW_ALT       = 0x11FFFFFF;

    private int activeTab = 0;
    private int scrollOffset = 0;
    private static boolean onlyProfileWhenOpen = false;

    public TaskManagerScreen() {
        super(Text.literal("Task Manager"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int w = this.width;
        int h = this.height;

        ctx.fill(0, 0, w, h, BG_COLOR);
        ctx.fill(0, 0, w, 20, 0xFF0A0A0A);
        ctx.drawText(textRenderer, "Task Manager", PADDING, 6, TEXT_PRIMARY, false);

        int checkX = w - 160;
        int checkY = 4;
        boolean hovered = mouseX >= checkX && mouseX < w - PADDING && mouseY >= checkY && mouseY < checkY + 12;
        ctx.fill(checkX, checkY, checkX + 10, checkY + 10, hovered ? 0xFF444444 : 0xFF2A2A2A);
        ctx.fill(checkX + 1, checkY + 1, checkX + 9, checkY + 9, 0xFF1A1A1A);
        if (onlyProfileWhenOpen) ctx.fill(checkX + 2, checkY + 2, checkX + 8, checkY + 8, ACCENT_GREEN);
        ctx.drawText(textRenderer, "Only profile when open", checkX + 13, checkY + 1, hovered ? TEXT_PRIMARY : TEXT_DIM, false);

        int tabY = 20;
        String[] tabs = {"Tasks", "Render Times", "Startup Times"};
        int tabW = 90;
        for (int i = 0; i < tabs.length; i++) {
            int tx = PADDING + i * (tabW + 2);
            ctx.fill(tx, tabY, tx + tabW, tabY + TAB_HEIGHT, i == activeTab ? TAB_ACTIVE : TAB_INACTIVE);
            if (i == activeTab) ctx.fill(tx, tabY, tx + tabW, tabY + 2, ACCENT_GREEN);
            ctx.fill(tx, tabY + TAB_HEIGHT - 1, tx + tabW, tabY + TAB_HEIGHT, BORDER_COLOR);
            int textX = tx + (tabW - textRenderer.getWidth(tabs[i])) / 2;
            ctx.drawText(textRenderer, tabs[i], textX, tabY + 8, i == activeTab ? TEXT_PRIMARY : TEXT_DIM, false);
        }

        int contentY = tabY + TAB_HEIGHT + 1;
        int contentH = h - contentY - PADDING;
        ctx.fill(0, contentY, w, h, PANEL_COLOR);
        ctx.fill(0, contentY, w, contentY + 1, BORDER_COLOR);

        if (activeTab == 0) renderProcesses(ctx, 0, contentY, w, contentH, mouseX, mouseY);
        else if (activeTab == 1) renderRender(ctx, 0, contentY, w, contentH, mouseX, mouseY);
        else renderStartup(ctx, 0, contentY, w, contentH, mouseX, mouseY);

        super.render(ctx, mouseX, mouseY, delta);
    }

    private void renderProcesses(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        Map<String, ModTimingSnapshot> data = ModTimingProfiler.getInstance().getSnapshot();

        if (data.isEmpty()) {
            ctx.drawText(textRenderer, "Waiting for data...", x + PADDING, y + PADDING + 4, TEXT_DIM, false);
            return;
        }

        long totalNanos = data.values().stream().mapToLong(ModTimingSnapshot::totalNanos).sum();

        int headerY = y + PADDING;
        ctx.fill(x, headerY, x + w, headerY + 14, HEADER_COLOR);
        ctx.drawText(textRenderer, "MOD",   x + PADDING + ICON_SIZE + 6, headerY + 3, TEXT_DIM, false);
        int cpuX   = w - 160;
        int callsX = w - 50;
        ctx.drawText(textRenderer, "CPU %",  cpuX,   headerY + 3, TEXT_DIM, false);
        ctx.drawText(textRenderer, "CALLS",  callsX, headerY + 3, TEXT_DIM, false);

        int listY = headerY + 16;
        int listH = h - (listY - y);

        ctx.enableScissor(x, listY, x + w, listY + listH);

        int rowY = listY - scrollOffset;
        int rowIdx = 0;

        for (var entry : data.entrySet()) {
            if (rowY + ROW_HEIGHT > listY + listH) break;
            if (rowY + ROW_HEIGHT > listY) {
                String modId = entry.getKey();
                ModTimingSnapshot d = entry.getValue();

                if (rowIdx % 2 == 0) ctx.fill(x, rowY, x + w, rowY + ROW_HEIGHT, ROW_ALT);
                if (mouseX >= x && mouseX < x + w && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) {
                    ctx.fill(x, rowY, x + w, rowY + ROW_HEIGHT, 0x22FFFFFF);
                }

                Identifier icon = ModIconCache.getInstance().getIcon(modId);
                ctx.drawTexture(RenderPipelines.GUI_TEXTURED, icon, x + PADDING, rowY + 2, 0f, 0f, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE, 0xFFFFFFFF);

                String name = FabricLoader.getInstance().getModContainer(modId).map(m -> m.getMetadata().getName()).orElse(modId);
                if (name.startsWith("Fabric ")) name = name.substring(7);
                ctx.drawText(textRenderer, name, x + PADDING + ICON_SIZE + 6, rowY + 6, TEXT_PRIMARY, false);

                double pct = totalNanos > 0 ? (d.totalNanos() * 100.0 / totalNanos) : 0;
                int barW = (int)(pct / 100.0 * 70);
                int barColor = pct > 30 ? 0x66FF4444 : pct > 10 ? 0x66FFB300 : 0x664CAF50;
                ctx.fill(cpuX, rowY + 7, cpuX + barW, rowY + 13, barColor);
                int pctColor = pct > 30 ? 0xFFFF6666 : pct > 10 ? ACCENT_YELLOW : ACCENT_GREEN;
                ctx.drawText(textRenderer, String.format("%.1f%%", pct), cpuX + 74, rowY + 6, pctColor, false);

                String callStr = d.calls() >= 1_000_000 ? String.format("%.1fM", d.calls() / 1_000_000.0) : d.calls() >= 1_000 ? String.format("%.1fk", d.calls() / 1_000.0) : String.valueOf(d.calls());
                ctx.drawText(textRenderer, callStr, callsX, rowY + 6, TEXT_DIM, false);
            }
            rowY += ROW_HEIGHT;
            rowIdx++;
        }

        ctx.disableScissor();
    }

    private void renderStartup(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        StartupTimingProfiler profiler = StartupTimingProfiler.getInstance();
        if (!profiler.hasData()) {
            ctx.drawText(textRenderer, "No startup data.", x + PADDING, y + PADDING + 4, TEXT_DIM, false);
            return;
        }

        long globalFirst = profiler.getGlobalFirst();
        long globalLast  = profiler.getGlobalLast();
        long totalSpan   = Math.max(globalLast - globalFirst, 1);

        int headerY = y + PADDING;
        ctx.fill(x, headerY, x + w, headerY + 14, HEADER_COLOR);
        ctx.drawText(textRenderer, "MOD",      x + PADDING + ICON_SIZE + 6, headerY + 3, TEXT_DIM, false);
        int barX  = w - 240;
        int pctX  = w - 80;
        int regsX = w - 30;
        ctx.drawText(textRenderer, "TIMELINE", barX,  headerY + 3, TEXT_DIM, false);
        ctx.drawText(textRenderer, "%",        pctX,  headerY + 3, TEXT_DIM, false);
        ctx.drawText(textRenderer, "REG",      regsX, headerY + 3, TEXT_DIM, false);

        int listY = headerY + 16;
        int listH = h - (listY - y) - 14;

        ctx.enableScissor(x, listY, x + w, listY + listH);

        int rowY = listY - scrollOffset;
        int rowIdx = 0;

        for (var row : profiler.getSortedRows()) {
            if (rowY + ROW_HEIGHT > listY + listH) break;
            if (rowY + ROW_HEIGHT > listY) {
                if (rowIdx % 2 == 0) ctx.fill(x, rowY, x + w, rowY + ROW_HEIGHT, ROW_ALT);
                if (mouseX >= x && mouseX < x + w && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) {
                    ctx.fill(x, rowY, x + w, rowY + ROW_HEIGHT, 0x22FFFFFF);
                }

                Identifier icon = ModIconCache.getInstance().getIcon(row.modId());
                ctx.drawTexture(RenderPipelines.GUI_TEXTURED, icon, x + PADDING, rowY + 2, 0f, 0f, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE, 0xFFFFFFFF);

                String name = FabricLoader.getInstance().getModContainer(row.modId()).map(m -> m.getMetadata().getName()).orElse(row.modId());
                if (name.startsWith("Fabric ")) name = name.substring(7);
                ctx.drawText(textRenderer, name, x + PADDING + ICON_SIZE + 6, rowY + 6, TEXT_PRIMARY, false);

                int barTotalW = 120;
                int barStart = (int)((row.first() - globalFirst) * barTotalW / totalSpan);
                int barLen   = Math.max(1, (int)((row.last() - row.first()) * barTotalW / totalSpan));
                ctx.fill(barX, rowY + 8, barX + barTotalW, rowY + 12, 0x33FFFFFF);
                ctx.fill(barX + barStart, rowY + 7, barX + barStart + barLen, rowY + 13, ACCENT_YELLOW);

                double spanMs = (row.last() - row.first()) / 1_000_000.0;
                double pct    = (row.last() - row.first()) * 100.0 / totalSpan;
                ctx.drawText(textRenderer, String.format("%.1fms", spanMs), barX + 124, rowY + 6, TEXT_DIM, false);
                ctx.drawText(textRenderer, String.format("%.1f%%", pct), pctX, rowY + 6, ACCENT_YELLOW, false);
                ctx.drawText(textRenderer, String.valueOf(row.registrations()), regsX, rowY + 6, TEXT_DIM, false);
            }
            rowY += ROW_HEIGHT;
            rowIdx++;
        }

        ctx.disableScissor();

        ctx.fill(x, y + h - 14, x + w, y + h, HEADER_COLOR);
        ctx.drawText(textRenderer,
                String.format("Total init span: %.1f ms", totalSpan / 1_000_000.0),
                x + PADDING, y + h - 10, TEXT_DIM, false);
    }

    private void renderRender(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        RenderPhaseProfiler profiler = RenderPhaseProfiler.getInstance();

        Map<String, Long> cpuNanos = profiler.getCpuNanos();
        Map<String, Long> cpuCalls = profiler.getCpuCalls();
        Map<String, Long> gpuNanos = profiler.getGpuNanos();
        Map<String, Long> gpuCalls = profiler.getGpuCalls();

        if (cpuNanos.isEmpty() && gpuNanos.isEmpty()) {
            ctx.drawText(textRenderer, "No render data.", x + PADDING, y + PADDING + 4, TEXT_DIM, false);
            return;
        }

        java.util.Set<String> allPhases = new java.util.LinkedHashSet<>();
        allPhases.addAll(cpuNanos.keySet());
        allPhases.addAll(gpuNanos.keySet());

        long totalCombinedNanos = allPhases.stream()
                .mapToLong(p -> cpuNanos.getOrDefault(p, 0L) + gpuNanos.getOrDefault(p, 0L))
                .sum();

        int headerY = y + PADDING;
        ctx.fill(x, headerY, x + w, headerY + 14, HEADER_COLOR);
        ctx.drawText(textRenderer, "PHASE", x + PADDING, headerY + 3, TEXT_DIM, false);
        int barX   = w - 240;
        int callsX = w - 50;
        ctx.drawText(textRenderer, "GPU", barX,   headerY + 3, TEXT_DIM, false);
        ctx.drawText(textRenderer, "CALLS", callsX, headerY + 3, TEXT_DIM, false);

        int listY = headerY + 16;
        int listH = h - (listY - y);

        ctx.enableScissor(x, listY, x + w, listY + listH);

        int rowY = listY - scrollOffset;
        int rowIdx = 0;

        for (String phase : allPhases) {
            if (rowY + ROW_HEIGHT > listY + listH) break;
            if (rowY + ROW_HEIGHT > listY) {
                if (rowIdx % 2 == 0) ctx.fill(x, rowY, x + w, rowY + ROW_HEIGHT, ROW_ALT);
                if (mouseX >= x && mouseX < x + w && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) {
                    ctx.fill(x, rowY, x + w, rowY + ROW_HEIGHT, 0x22FFFFFF);
                }

                ctx.drawText(textRenderer, phase, x + PADDING, rowY + 6, TEXT_PRIMARY, false);

                long combinedNs = cpuNanos.getOrDefault(phase, 0L) + gpuNanos.getOrDefault(phase, 0L);
                double pct = totalCombinedNanos > 0 ? (combinedNs * 100.0 / totalCombinedNanos) : 0;
                int barW = (int)(pct / 100.0 * 70);
                int barColor = pct > 30 ? 0x66FF4444 : pct > 10 ? 0x66FFB300 : 0x664CAF50;
                int pctColor = pct > 30 ? 0xFFFF6666 : pct > 10 ? ACCENT_YELLOW : ACCENT_GREEN;
                ctx.fill(barX, rowY + 7, barX + barW, rowY + 13, barColor);
                ctx.drawText(textRenderer, String.format("%.1f%%", pct), barX + 74, rowY + 6, pctColor, false);

                long calls = cpuCalls.getOrDefault(phase, 0L) + gpuCalls.getOrDefault(phase, 0L);
                String callStr = calls >= 1_000_000 ? String.format("%.1fM", calls / 1_000_000.0) : calls >= 1_000 ? String.format("%.1fk", calls / 1_000.0) : String.valueOf(calls);
                ctx.drawText(textRenderer, callStr, callsX, rowY + 6, TEXT_DIM, false);
            }
            rowY += ROW_HEIGHT;
            rowIdx++;
        }

        ctx.disableScissor();
    }

    private int getMaxScrollOffset(int contentH) {
        int headerH = PADDING + 14 + 16;
        int listH = contentH - headerH;

        int rows;
        if (activeTab == 0) {
            rows = ModTimingProfiler.getInstance().getSnapshot().size();
        } else if (activeTab == 1) {
            rows = StartupTimingProfiler.getInstance().getSortedRows().size();
        } else {
            RenderPhaseProfiler p = RenderPhaseProfiler.getInstance();
            java.util.Set<String> phases = new java.util.LinkedHashSet<>();
            phases.addAll(p.getCpuNanos().keySet());
            phases.addAll(p.getGpuNanos().keySet());
            rows = phases.size();
        }

        return Math.max(0, rows * ROW_HEIGHT - listH);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        int contentH = this.height - (20 + TAB_HEIGHT + 1) - PADDING;
        int maxScroll = getMaxScrollOffset(contentH);
        scrollOffset = Math.max(0, Math.min(scrollOffset - (int)(vertical * ROW_HEIGHT), maxScroll));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int checkX = width - 160;
        if (mouseX >= checkX && mouseX < width - PADDING && mouseY >= 4 && mouseY < 16) {
            onlyProfileWhenOpen = !onlyProfileWhenOpen;
            return true;
        }

        int tabY = 20;
        int tabW = 90;
        for (int i = 0; i < 3; i++) {
            int tx = PADDING + i * (tabW + 2);
            if (mouseX >= tx && mouseX < tx + tabW && mouseY >= tabY && mouseY < tabY + TAB_HEIGHT) {
                activeTab = i;
                scrollOffset = 0;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    public static boolean isProfilingActive() {
        return !onlyProfileWhenOpen || MinecraftClient.getInstance().currentScreen instanceof TaskManagerScreen;
    }
}