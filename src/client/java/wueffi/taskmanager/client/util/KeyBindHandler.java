package wueffi.taskmanager.client.util;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import wueffi.taskmanager.client.ProfilerManager;
import wueffi.taskmanager.client.TaskManagerScreen;
import wueffi.taskmanager.client.util.ConfigManager;

public class KeyBindHandler {

    private static KeyMapping openKey;
    private static KeyMapping sessionKey;
    private static KeyMapping hudToggleKey;

    public static boolean matchesOpenKey(KeyEvent input) {
        return openKey != null && input != null && openKey.matches(input);
    }

    public static void register() {
        KeyMapping.Category taskManagerCategory = new KeyMapping.Category(Identifier.fromNamespaceAndPath("taskmanager", "taskmanager"));
        sessionKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.taskmanager.session",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F9,
                taskManagerCategory
        ));
        hudToggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.taskmanager.hud_toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F10,
                taskManagerCategory
        ));
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.taskmanager.open",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F12,
                taskManagerCategory
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                if (client.screen instanceof TaskManagerScreen) {
                    client.setScreen(null);
                } else {
                    client.setScreen(new TaskManagerScreen());
                }
            }
            while (sessionKey.consumeClick()) {
                ProfilerManager.getInstance().toggleSessionLogging();
            }
            while (hudToggleKey.consumeClick()) {
                ConfigManager.setHudEnabled(!ConfigManager.isHudEnabled());
            }
        });
    }
}
