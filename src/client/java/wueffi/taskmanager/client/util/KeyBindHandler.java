package wueffi.taskmanager.client.util;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;
import wueffi.taskmanager.client.TaskManagerScreen;

public class KeyBindHandler {

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(ResourceLocation.parse("taskmanager"));

    private static KeyBinding openKey;

    public static void register() {
        openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.taskmanager.open",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F12,
                CATEGORY
        ));
        
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) {
                client.setScreen(new TaskManagerScreen());
            }
        });
    }
}
