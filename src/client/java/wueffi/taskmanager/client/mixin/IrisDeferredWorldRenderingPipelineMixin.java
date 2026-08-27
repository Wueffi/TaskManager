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
@Mixin(targets = "net.irisshaders.iris.pipeline.DeferredWorldRenderingPipeline", remap = false)
public class IrisDeferredWorldRenderingPipelineMixin {

    @Unique
    private static void taskmanager$beginPhase(String phase) {
        if (!ProfilerManager.getInstance().shouldCollectDetailedMetrics()) {
            return;
        }
        RenderPhaseProfiler.getInstance().beginCpuPhase(phase, "iris");
        GpuTimer.begin(phase);
    }

    @Unique
    private static void taskmanager$endPhase(String phase) {
        GpuTimer.end(phase);
        RenderPhaseProfiler.getInstance().endCpuPhase(phase);
    }

    @Inject(method = "renderShadows", at = @At("HEAD"), require = 0)
    private void taskmanager$onRenderShadowsHead(CallbackInfo ci) { taskmanager$beginPhase("iris.renderShadows"); }

    @Inject(method = "renderShadows", at = @At("TAIL"), require = 0)
    private void taskmanager$onRenderShadowsTail(CallbackInfo ci) { taskmanager$endPhase("iris.renderShadows"); }

    @Inject(method = "renderTranslucents", at = @At("HEAD"), require = 0)
    private void taskmanager$onRenderTranslucentsHead(CallbackInfo ci) { taskmanager$beginPhase("iris.renderTranslucents"); }

    @Inject(method = "renderTranslucents", at = @At("TAIL"), require = 0)
    private void taskmanager$onRenderTranslucentsTail(CallbackInfo ci) { taskmanager$endPhase("iris.renderTranslucents"); }

    @Inject(method = "renderWeather", at = @At("HEAD"), require = 0)
    private void taskmanager$onRenderWeatherHead(CallbackInfo ci) { taskmanager$beginPhase("iris.renderWeather"); }

    @Inject(method = "renderWeather", at = @At("TAIL"), require = 0)
    private void taskmanager$onRenderWeatherTail(CallbackInfo ci) { taskmanager$endPhase("iris.renderWeather"); }

    @Inject(method = "renderHand", at = @At("HEAD"), require = 0)
    private void taskmanager$onRenderHandHead(CallbackInfo ci) { taskmanager$beginPhase("iris.renderHand"); }

    @Inject(method = "renderHand", at = @At("TAIL"), require = 0)
    private void taskmanager$onRenderHandTail(CallbackInfo ci) { taskmanager$endPhase("iris.renderHand"); }

    @Inject(method = "beginSodiumTerrainRendering", at = @At("HEAD"), require = 0)
    private void taskmanager$onBeginSodiumTerrainRenderingHead(CallbackInfo ci) { taskmanager$beginPhase("iris.beginSodiumTerrainRendering"); }

    @Inject(method = "beginSodiumTerrainRendering", at = @At("TAIL"), require = 0)
    private void taskmanager$onBeginSodiumTerrainRenderingTail(CallbackInfo ci) { taskmanager$endPhase("iris.beginSodiumTerrainRendering"); }

    @Inject(method = "endSodiumTerrainRendering", at = @At("HEAD"), require = 0)
    private void taskmanager$onEndSodiumTerrainRenderingHead(CallbackInfo ci) { taskmanager$beginPhase("iris.endSodiumTerrainRendering"); }

    @Inject(method = "endSodiumTerrainRendering", at = @At("TAIL"), require = 0)
    private void taskmanager$onEndSodiumTerrainRenderingTail(CallbackInfo ci) { taskmanager$endPhase("iris.endSodiumTerrainRendering"); }

    @Inject(method = "compositePass", at = @At("HEAD"), require = 0)
    private void taskmanager$onCompositePassHead(CallbackInfo ci) { taskmanager$beginPhase("iris.compositePass"); }

    @Inject(method = "compositePass", at = @At("TAIL"), require = 0)
    private void taskmanager$onCompositePassTail(CallbackInfo ci) { taskmanager$endPhase("iris.compositePass"); }

    @Inject(method = "prepareRenderTargets", at = @At("HEAD"), require = 0)
    private void taskmanager$onPrepareRenderTargetsHead(CallbackInfo ci) { taskmanager$beginPhase("iris.prepareRenderTargets"); }

    @Inject(method = "prepareRenderTargets", at = @At("TAIL"), require = 0)
    private void taskmanager$onPrepareRenderTargetsTail(CallbackInfo ci) { taskmanager$endPhase("iris.prepareRenderTargets"); }

    @Inject(method = "destroyBuffers", at = @At("HEAD"), require = 0)
    private void taskmanager$onDestroyBuffersHead(CallbackInfo ci) { taskmanager$beginPhase("iris.destroyBuffers"); }

    @Inject(method = "destroyBuffers", at = @At("TAIL"), require = 0)
    private void taskmanager$onDestroyBuffersTail(CallbackInfo ci) { taskmanager$endPhase("iris.destroyBuffers"); }
}
