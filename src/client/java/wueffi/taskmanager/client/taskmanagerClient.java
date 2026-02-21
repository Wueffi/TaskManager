package wueffi.taskmanager.client;

import wueffi.taskmanager.client.util.KeyBindHandler;
import wueffi.taskmanager.client.util.ModClassIndex;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class taskmanagerClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("taskmanager");
    private boolean startupClosed = false;

    @Override
    public void onInitializeClient() {
        ModClassIndex.build();
        KeyBindHandler.register();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!startupClosed) {
                startupClosed = true;
                StartupTimingProfiler.getInstance().close();
            }
        });
    }
}