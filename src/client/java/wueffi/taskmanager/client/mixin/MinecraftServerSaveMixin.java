package wueffi.taskmanager.client.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import wueffi.taskmanager.client.ProfilerManager;
import wueffi.taskmanager.client.TaskManagerScreen;

@Mixin(MinecraftServer.class)
public class MinecraftServerSaveMixin {

    @Inject(method = "save", at = @At("HEAD"), require = 0)
    private void taskmanager$onSaveHead(boolean suppressLogs, boolean flush, boolean force, CallbackInfoReturnable<Boolean> cir) {
        if (!TaskManagerScreen.isLiveMetricsActive()) {
            return;
        }
        ProfilerManager.getInstance().beginSaveEvent(force ? "manual-save" : "autosave");
    }

    @Inject(method = "save", at = @At("TAIL"), require = 0)
    private void taskmanager$onSaveTail(boolean suppressLogs, boolean flush, boolean force, CallbackInfoReturnable<Boolean> cir) {
        if (!TaskManagerScreen.isLiveMetricsActive()) {
            return;
        }
        ProfilerManager.getInstance().endSaveEvent();
    }
}
