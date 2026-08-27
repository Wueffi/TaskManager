package wueffi.taskmanager.client.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wueffi.taskmanager.client.TaskManagerScreen;
import wueffi.taskmanager.client.TickProfiler;

import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void taskmanager$onServerTickStart(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        if (!TaskManagerScreen.isLiveMetricsActive()) return;
        TickProfiler.getInstance().beginServerTick();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void taskmanager$onServerTickEnd(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        if (!TaskManagerScreen.isLiveMetricsActive()) return;
        TickProfiler.getInstance().endServerTick();
    }
}
