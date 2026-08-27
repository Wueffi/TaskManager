package wueffi.taskmanager.client;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

final class TooltipManager {

    private record TooltipTarget(int x, int y, int width, int height, String text) {}

    private final List<TooltipTarget> tooltipTargets = new ArrayList<>();

    void clear() {
        tooltipTargets.clear();
    }

    void add(int x, int y, int width, int height, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        tooltipTargets.add(new TooltipTarget(x, y, width, height, text));
    }

    void render(DrawContext ctx, int mouseX, int mouseY, int screenWidth, int screenHeight, TextRenderer textRenderer, int textColor, int borderColor) {
        TooltipTarget target = null;
        for (TooltipTarget candidate : tooltipTargets) {
            if (isInside(mouseX, mouseY, candidate.x(), candidate.y(), candidate.width(), candidate.height())) {
                target = candidate;
            }
        }
        if (target == null) {
            return;
        }
        int maxWidth = Math.min(320, screenWidth - 24);
        List<OrderedText> wrapped = textRenderer.wrapLines(Text.literal(target.text()), maxWidth);
        int widest = 0;
        for (OrderedText line : wrapped) {
            widest = Math.max(widest, textRenderer.getWidth(line));
        }
        int boxW = Math.min(maxWidth + 10, Math.max(100, widest + 10));
        int boxH = Math.max(18, wrapped.size() * 12 + 6);
        int boxX = Math.max(0, Math.min(screenWidth - boxW - 8, mouseX + 10));
        int boxY = Math.max(0, Math.min(screenHeight - boxH - 8, mouseY + 10));
        ctx.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xE0121212);
        ctx.fill(boxX, boxY, boxX + boxW, boxY + 1, borderColor);
        ctx.fill(boxX, boxY + boxH - 1, boxX + boxW, boxY + boxH, borderColor);
        ctx.fill(boxX, boxY, boxX + 1, boxY + boxH, borderColor);
        ctx.fill(boxX + boxW - 1, boxY, boxX + boxW, boxY + boxH, borderColor);
        int textY = boxY + 4;
        for (OrderedText line : wrapped) {
            ctx.drawText(textRenderer, line, boxX + 5, textY, textColor, false);
            textY += 12;
        }
    }

    private static boolean isInside(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }
}
