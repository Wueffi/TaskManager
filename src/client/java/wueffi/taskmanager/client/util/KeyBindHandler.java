package wueffi.taskmanager.client.util;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import wueffi.taskmanager.client.TaskManagerScreen;

public class KeyBindHandler {

    private static KeyBinding openKey;

    public static void register() {
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.taskmanager.open",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F12,
                "category.taskmanager"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) {
                client.setScreen(new TaskManagerScreen());
            }
        });
    }
}