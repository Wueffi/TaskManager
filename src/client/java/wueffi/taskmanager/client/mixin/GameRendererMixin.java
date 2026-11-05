package wueffi.taskmanager.client.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wueffi.taskmanager.client.FrameTimelineProfiler;
import wueffi.taskmanager.client.InputLatencyProfiler;
import wueffi.taskmanager.client.ProfilerManager;
import wueffi.taskmanager.client.RenderPhaseProfiler;
import wueffi.taskmanager.client.TaskManagerScreen;
import wueffi.taskmanager.client.util.GpuTimer;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Unique
    private boolean taskmanager$detailedFrame;

    @Unique
    private boolean taskmanager$liveFrame;

    @Inject(method = "render", at = @At("HEAD"))
    private void taskmanager$onRenderHead(CallbackInfo ci) {
        wueffi.taskmanager.client.ShaderPassProfiler.beginFrame();
        taskmanager$liveFrame = TaskManagerScreen.isLiveMetricsActive();
        if (!taskmanager$liveFrame) return;

        FrameTimelineProfiler.getInstance().beginFrame();
        taskmanager$detailedFrame = ProfilerManager.getInstance().shouldCollectDetailedMetrics();
        if (taskmanager$detailedFrame) {
            RenderPhaseProfiler.getInstance().beginCpuPhase("frame.total", "minecraft");
            GpuTimer.begin("frame.total");
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void taskmanager$onRenderTail(CallbackInfo ci) {
        if (!taskmanager$liveFrame) return;

        if (taskmanager$detailedFrame) {
            GpuTimer.end("frame.total");
            RenderPhaseProfiler.getInstance().endCpuPhase("frame.total");
            taskmanager$detailedFrame = false;
        }
        FrameTimelineProfiler.getInstance().endFrame();
        InputLatencyProfiler.getInstance().onFramePresented();
        taskmanager$liveFrame = false;
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void taskmanager$onRenderWorldHead(CallbackInfo ci) {
        if (!ProfilerManager.getInstance().shouldCollectDetailedMetrics()) return;
        RenderPhaseProfiler.getInstance().beginCpuPhase("gameRenderer.renderWorld", "shared/render");
        GpuTimer.begin("gameRenderer.renderWorld");
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void taskmanager$onRenderWorldTail(CallbackInfo ci) {
        if (!ProfilerManager.getInstance().shouldCollectDetailedMetrics()) return;
        GpuTimer.end("gameRenderer.renderWorld");
        RenderPhaseProfiler.getInstance().endCpuPhase("gameRenderer.renderWorld");
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), require = 0)
    private void taskmanager$onRenderHandHead(CallbackInfo ci) {
        if (!ProfilerManager.getInstance().shouldCollectDetailedMetrics()) return;
        RenderPhaseProfiler.getInstance().beginCpuPhase("gameRenderer.renderHand", "shared/render");
        GpuTimer.begin("gameRenderer.renderHand");
    }

    @Inject(method = "renderItemInHand", at = @At("RETURN"), require = 0)
    private void taskmanager$onRenderHandTail(CallbackInfo ci) {
        if (!ProfilerManager.getInstance().shouldCollectDetailedMetrics()) return;
        GpuTimer.end("gameRenderer.renderHand");
        RenderPhaseProfiler.getInstance().endCpuPhase("gameRenderer.renderHand");
    }
}



