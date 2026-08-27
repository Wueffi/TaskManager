package wueffi.taskmanager.client.mixin;

import net.minecraft.server.world.ServerChunkManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import wueffi.taskmanager.client.ChunkWorkProfiler;
import wueffi.taskmanager.client.ProfilerManager;

@Mixin(ServerChunkManager.class)
public abstract class ServerChunkManagerMixin {

    @Inject(method = "getChunkFutureSyncOnMainThread", at = @At("HEAD"))
    private void taskmanager$beginChunkLoad(CallbackInfoReturnable<?> cir) {
        if (!ProfilerManager.getInstance().shouldCollectDetailedMetrics()) {
            return;
        }
        ChunkWorkProfiler.getInstance().beginPhase("minecraft | main-thread chunk load");
    }

    @Inject(method = "getChunkFutureSyncOnMainThread", at = @At("RETURN"))
    private void taskmanager$endChunkLoad(CallbackInfoReturnable<?> cir) {
        ChunkWorkProfiler.getInstance().endPhase();
    }
}
