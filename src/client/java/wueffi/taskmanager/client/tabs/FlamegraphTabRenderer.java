package wueffi.taskmanager.client;

import net.minecraft.client.gui.DrawContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

final class FlamegraphTabRenderer {

    private FlamegraphTabRenderer() {
    }

    static void render(TaskManagerScreen screen, DrawContext ctx, int x, int y, int w, int h) {
        var textRenderer = screen.uiTextRenderer();
        screen.beginFullPageScissor(ctx, x, y, w, h);
        int top = screen.getFullPageScrollTop(y);
        top = screen.renderSectionHeader(ctx, x + TaskManagerScreen.PADDING, top, "Flamegraph", "Captured stack samples from the current profiling window.");
        int rowY = top + 4;
        Map<String, Long> stacks = new LinkedHashMap<>();
        screen.snapshot.flamegraphStacks().forEach((stack, count) -> {
            if (screen.matchesGlobalSearch(stack.toLowerCase(Locale.ROOT))) {
                stacks.put(stack, count);
            }
        });
        if (stacks.isEmpty()) {
            ctx.drawText(textRenderer, screen.globalSearch.isBlank() ? "No flamegraph samples yet." : "No flamegraph stacks match the universal search.", x + TaskManagerScreen.PADDING, rowY, TaskManagerScreen.TEXT_DIM, false);
        } else {
            int shown = 0;
            for (Map.Entry<String, Long> entry : stacks.entrySet()) {
                ctx.drawText(textRenderer, textRenderer.trimToWidth(entry.getKey(), w - 120), x + TaskManagerScreen.PADDING, rowY, TaskManagerScreen.TEXT_DIM, false);
                String count = screen.formatCount(entry.getValue());
                ctx.drawText(textRenderer, count, x + w - TaskManagerScreen.PADDING - textRenderer.getWidth(count), rowY, TaskManagerScreen.TEXT_PRIMARY, false);
                rowY += 12;
                if (++shown >= 20) {
                    break;
                }
            }
        }
        ctx.disableScissor();
    }
}
