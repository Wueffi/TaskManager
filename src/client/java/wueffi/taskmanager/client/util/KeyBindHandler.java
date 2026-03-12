package wueffi.taskmanager.client.util;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import wueffi.taskmanager.client.ProfilerManager;
import wueffi.taskmanager.client.TaskManagerScreen;

public class KeyBindHandler {

    private static KeyBinding openKey;
    private static KeyBinding sessionKey;
    private static KeyBinding hudToggleKey;

    public static boolean matchesOpenKey(int keyCode, int scanCode) {
        return openKey != null && openKey.matchesKey(keyCode, scanCode);
    }

    public static void register() {
        sessionKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.taskmanager.session",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F9,
                "taskmanager"
        ));
        hudToggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.taskmanager.hud_toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F10,
                "taskmanager"
        ));
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.taskmanager.open",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F12,
                "taskmanager"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) {
                if (client.currentScreen instanceof TaskManagerScreen) {
                    client.setScreen(null);
                } else {
                    client.setScreen(new TaskManagerScreen());
                }
            }
            while (sessionKey.wasPressed()) {
                ProfilerManager.getInstance().toggleSessionLogging();
            }
            while (hudToggleKey.wasPressed()) {
                ConfigManager.setHudEnabled(!ConfigManager.isHudEnabled());
            }
        });
    }
}
