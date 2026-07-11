package wueffi.taskmanager.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;

@FunctionalInterface
public interface TabRenderer {

    void render(GuiGraphicsExtractor ctx, TaskManagerScreen screen, int x, int y, int w, int h, int mouseX, int mouseY);

    default boolean mouseClicked(TaskManagerScreen screen, double mouseX, double mouseY, int button) {
        return false;
    }

    default boolean mouseScrolled(TaskManagerScreen screen, double mouseX, double mouseY, double amount) {
        return false;
    }

    default boolean charTyped(TaskManagerScreen screen, char chr, int modifiers) {
        return false;
    }

    default boolean keyPressed(TaskManagerScreen screen, int keyCode, int scanCode, int modifiers) {
        return false;
    }
}
