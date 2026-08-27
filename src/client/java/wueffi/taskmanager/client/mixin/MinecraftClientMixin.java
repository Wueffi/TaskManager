package wueffi.taskmanager.client.mixin;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import wueffi.taskmanager.client.TickProfiler;
import wueffi.taskmanager.client.TaskManagerScreen;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void taskmanager$onTickStart(CallbackInfo ci) {
        if (!TaskManagerScreen.isLiveMetricsActive()) return;
        TickProfiler.getInstance().beginTick();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void taskmanager$onTickEnd(CallbackInfo ci) {
        if (!TaskManagerScreen.isLiveMetricsActive()) return;
        TickProfiler.getInstance().endTick();
    }
}