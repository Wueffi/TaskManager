package wueffi.taskmanager.client.mixin;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Unique;
import wueffi.taskmanager.client.RenderPhaseProfiler;
import wueffi.taskmanager.client.util.GpuTimer;
import org.spongepowered.asm.mixin.Mixin;
import wueffi.taskmanager.client.ProfilerManager;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class WorldRendererMixin {

    @Unique
    private static void taskmanager$beginPhase(String phase) {
        if (!ProfilerManager.getInstance().shouldCollectDetailedMetrics()) {
            return;
        }
        RenderPhaseProfiler.getInstance().beginCpuPhase(phase, "shared/render");
        GpuTimer.begin(phase);
    }

    @Unique
    private static void taskmanager$endPhase(String phase) {
        if (!ProfilerManager.getInstance().shouldCollectDetailedMetrics()) {
            return;
        }
        GpuTimer.end(phase);
        RenderPhaseProfiler.getInstance().endCpuPhase(phase);
    }

    @Unique
    private static void taskmanager$beginCpuOnlyPhase(String phase) {
        if (!ProfilerManager.getInstance().shouldCollectDetailedMetrics()) {
            return;
        }
        RenderPhaseProfiler.getInstance().beginCpuPhase(phase, "shared/render");
    }

    @Unique
    private static void taskmanager$endCpuOnlyPhase(String phase) {
        if (!ProfilerManager.getInstance().shouldCollectDetailedMetrics()) {
            return;
        }
        RenderPhaseProfiler.getInstance().endCpuPhase(phase);
    }

    @Inject(
        method = "executeOutline",
        at = @At("HEAD")
    )
    private void taskmanager$onOutlinesHead(CallbackInfo ci) {
        taskmanager$beginPhase("worldRenderer.entityOutlines");
    }

    @Inject(
        method = "executeOutline",
        at = @At("RETURN")
    )
    private void taskmanager$onOutlinesTail(CallbackInfo ci) {
        taskmanager$endPhase("worldRenderer.entityOutlines");
    }

    @Inject(method = "addSkyPass", at = @At("HEAD"), require = 0)
    private void taskmanager$onRenderSkyHead(FrameGraphBuilder frameGraphBuilder, CameraRenderState cameraRenderState, GpuBufferSlice fog, CallbackInfo ci) {
        taskmanager$beginCpuOnlyPhase("worldRenderer.skyPassSetup");
    }

    @Inject(method = "addSkyPass", at = @At("RETURN"), require = 0)
    private void taskmanager$onRenderSkyTail(FrameGraphBuilder frameGraphBuilder, CameraRenderState cameraRenderState, GpuBufferSlice fog, CallbackInfo ci) {
        taskmanager$endCpuOnlyPhase("worldRenderer.skyPassSetup");
    }

    @Inject(method = "addMainPass", at = @At("HEAD"), require = 0)
    private void taskmanager$onRenderMainHead(FrameGraphBuilder frameGraphBuilder, FeatureRenderDispatcher.PreparedFrame preparedFrame, GpuBufferSlice fog, ChunkSectionsToRender chunkSectionsToRender, boolean renderBlockOutline, CallbackInfo ci) {
        taskmanager$beginCpuOnlyPhase("worldRenderer.mainPassSetup");
    }

    @Inject(method = "addMainPass", at = @At("RETURN"), require = 0)
    private void taskmanager$onRenderMainTail(FrameGraphBuilder frameGraphBuilder, FeatureRenderDispatcher.PreparedFrame preparedFrame, GpuBufferSlice fog, ChunkSectionsToRender chunkSectionsToRender, boolean renderBlockOutline, CallbackInfo ci) {
        taskmanager$endCpuOnlyPhase("worldRenderer.mainPassSetup");
    }

    @Inject(method = "submitBlockEntities", at = @At("HEAD"), require = 0)
    private void taskmanager$onRenderBlockEntitiesHead(PoseStack poseStack, LevelRenderState levelRenderState, SubmitNodeCollector submitNodeCollector, CallbackInfo ci) {
        taskmanager$beginCpuOnlyPhase("worldRenderer.submitBlockEntities");
    }

    @Inject(method = "submitBlockEntities", at = @At("RETURN"), require = 0)
    private void taskmanager$onRenderBlockEntitiesTail(PoseStack poseStack, LevelRenderState levelRenderState, SubmitNodeCollector submitNodeCollector, CallbackInfo ci) {
        taskmanager$endCpuOnlyPhase("worldRenderer.submitBlockEntities");
    }

    @Inject(method = "submitEntities", at = @At("HEAD"), require = 0)
    private void taskmanager$onPushEntityRendersHead(PoseStack poseStack, LevelRenderState levelRenderState, SubmitNodeCollector submitNodeCollector, CallbackInfo ci) {
        taskmanager$beginCpuOnlyPhase("worldRenderer.pushEntityRenders");
    }

    @Inject(method = "submitEntities", at = @At("RETURN"), require = 0)
    private void taskmanager$onPushEntityRendersTail(PoseStack poseStack, LevelRenderState levelRenderState, SubmitNodeCollector submitNodeCollector, CallbackInfo ci) {
        taskmanager$endCpuOnlyPhase("worldRenderer.pushEntityRenders");
    }

}

