package wueffi.taskmanager.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import wueffi.taskmanager.client.util.ConfigManager;
import wueffi.taskmanager.client.util.KeyBindHandler;
import wueffi.taskmanager.client.util.ModClassIndex;

public class taskmanagerClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("taskmanager");
    private boolean startupClosed = false;

    @Override
    public void onInitializeClient() {
        ModClassIndex.build();
        KeyBindHandler.register();
        ConfigManager.loadConfig();
        ProfilerManager.getInstance().initialize();

        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> HudOverlayRenderer.render(drawContext));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!startupClosed) {
                startupClosed = true;
                StartupTimingProfiler.getInstance().close();
            }

            ProfilerManager.getInstance().onClientTickEnd(client);
        });
    }
}
