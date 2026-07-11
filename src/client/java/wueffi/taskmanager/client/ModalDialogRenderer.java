package wueffi.taskmanager.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;

final class ModalDialogRenderer {

    private ModalDialogRenderer() {
    }

    static void render(TaskManagerScreen screen, GuiGraphicsExtractor ctx, int width, int height, int mouseX, int mouseY) {
        if (screen.attributionHelpOpen) {
            screen.renderAttributionHelpOverlay(ctx, width, height, mouseX, mouseY);
        } else if (screen.activeDrilldownTable != null) {
            screen.renderRowDrilldownOverlay(ctx, width, height, mouseX, mouseY);
        }
    }
}
