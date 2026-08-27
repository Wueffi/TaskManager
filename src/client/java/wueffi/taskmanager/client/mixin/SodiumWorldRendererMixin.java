package wueffi.taskmanager.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wueffi.taskmanager.client.ProfilerManager;
import wueffi.taskmanager.client.RenderPhaseProfiler;
import wueffi.taskmanager.client.util.GpuTimer;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public class SodiumWorldRendererMixin {

    @Unique
    private static void taskmanager$beginPhase(String phase) {
        if (!ProfilerManager.getInstance().shouldCollectDetailedMetrics()) {
            return;
        }
        RenderPhaseProfiler.getInstance().beginCpuPhase(phase, "sodium");
        GpuTimer.begin(phase);
    }

    @Unique
    private static void taskmanager$endPhase(String phase) {
        GpuTimer.end(phase);
        RenderPhaseProfiler.getInstance().endCpuPhase(phase);
    }

    @Inject(method = "drawChunkLayer", at = @At("HEAD"), require = 0)
    private void taskmanager$onDrawChunkLayerHead(CallbackInfo ci) {
        taskmanager$beginPhase("sodium.drawChunkLayer");
    }

    @Inject(method = "drawChunkLayer", at = @At("TAIL"), require = 0)
    private void taskmanager$onDrawChunkLayerTail(CallbackInfo ci) {
        taskmanager$endPhase("sodium.drawChunkLayer");
    }

    @Inject(method = "renderBlockEntities", at = @At("HEAD"), require = 0)
    private void taskmanager$onRenderBlockEntitiesHead(CallbackInfo ci) {
        taskmanager$beginPhase("sodium.renderBlockEntities");
    }

    @Inject(method = "renderBlockEntities", at = @At("TAIL"), require = 0)
    private void taskmanager$onRenderBlockEntitiesTail(CallbackInfo ci) {
        taskmanager$endPhase("sodium.renderBlockEntities");
    }

    @Inject(method = "renderTileEntities", at = @At("HEAD"), require = 0)
    private void taskmanager$onRenderTileEntitiesHead(CallbackInfo ci) {
        taskmanager$beginPhase("sodium.renderTileEntities");
    }

    @Inject(method = "renderTileEntities", at = @At("TAIL"), require = 0)
    private void taskmanager$onRenderTileEntitiesTail(CallbackInfo ci) {
        taskmanager$endPhase("sodium.renderTileEntities");
    }

    @Inject(method = "setupTerrain", at = @At("HEAD"), require = 0)
    private void taskmanager$onSetupTerrainHead(CallbackInfo ci) {
        taskmanager$beginPhase("sodium.setupTerrain");
    }

    @Inject(method = "setupTerrain", at = @At("TAIL"), require = 0)
    private void taskmanager$onSetupTerrainTail(CallbackInfo ci) {
        taskmanager$endPhase("sodium.setupTerrain");
    }

    @Inject(method = "updateChunks", at = @At("HEAD"), require = 0)
    private void taskmanager$onUpdateChunksHead(CallbackInfo ci) {
        taskmanager$beginPhase("sodium.updateChunks");
    }

    @Inject(method = "updateChunks", at = @At("TAIL"), require = 0)
    private void taskmanager$onUpdateChunksTail(CallbackInfo ci) {
        taskmanager$endPhase("sodium.updateChunks");
    }

    @Inject(method = "renderLayer", at = @At("HEAD"), require = 0)
    private void taskmanager$onRenderLayerHead(CallbackInfo ci) {
        taskmanager$beginPhase("sodium.renderLayer");
    }

    @Inject(method = "renderLayer", at = @At("TAIL"), require = 0)
    private void taskmanager$onRenderLayerTail(CallbackInfo ci) {
        taskmanager$endPhase("sodium.renderLayer");
    }
}
