package wueffi.taskmanager.client.mixin;

import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import wueffi.taskmanager.client.ChunkWorkProfiler;
import wueffi.taskmanager.client.TaskManagerScreen;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkManagerMixin {

    @Inject(method = "getChunkFuture", at = @At("HEAD"))
    private void taskmanager$beginChunkLoad(CallbackInfoReturnable<?> cir) {
        if (!TaskManagerScreen.isLiveMetricsActive()) {
            return;
        }
        ChunkWorkProfiler.getInstance().beginPhase("minecraft | main-thread chunk load");
    }

    @Inject(method = "getChunkFuture", at = @At("RETURN"))
    private void taskmanager$endChunkLoad(CallbackInfoReturnable<?> cir) {
        if (!TaskManagerScreen.isLiveMetricsActive()) {
            return;
        }
        ChunkWorkProfiler.getInstance().endPhase();
    }
}
