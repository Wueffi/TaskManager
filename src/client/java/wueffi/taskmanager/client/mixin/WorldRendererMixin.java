package wueffi.taskmanager.client.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Unique;
import wueffi.taskmanager.client.RenderPhaseProfiler;
import wueffi.taskmanager.client.util.GpuTimer;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
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
            method = "renderLevel",
            at = @At("HEAD")
    )
    private void taskmanager$onRenderHead(
            GraphicsResourceAllocator allocator,
            DeltaTracker tickCounter,
            boolean renderBlockOutline,
            CameraRenderState cameraRenderState,
            Matrix4fc projectionMatrix,
            GpuBufferSlice fog,
            Vector4f fogColor,
            boolean shouldRenderSky,
            ChunkSectionsToRender chunkSectionsToRender,
            CallbackInfo ci) {
        taskmanager$beginPhase("worldRenderer.render");
    }

    @Inject(
            method = "renderLevel",
            at = @At("TAIL")
    )
    private void taskmanager$onRenderTail(
            GraphicsResourceAllocator allocator,
            DeltaTracker tickCounter,
            boolean renderBlockOutline,
            CameraRenderState cameraRenderState,
            Matrix4fc projectionMatrix,
            GpuBufferSlice fog,
            Vector4f fogColor,
            boolean shouldRenderSky,
            ChunkSectionsToRender chunkSectionsToRender,
            CallbackInfo ci) {
        taskmanager$endPhase("worldRenderer.render");
    }

    @Inject(
        method = "doEntityOutline",
        at = @At("HEAD")
    )
    private void taskmanager$onOutlinesHead(CallbackInfo ci) {
        taskmanager$beginPhase("worldRenderer.entityOutlines");
    }

    @Inject(
        method = "doEntityOutline",
        at = @At("TAIL")
    )
    private void taskmanager$onOutlinesTail(CallbackInfo ci) {
        taskmanager$endPhase("worldRenderer.entityOutlines");
    }

    @Inject(method = "addWeatherPass", at = @At("HEAD"), require = 0)
    private void taskmanager$onRenderWeatherHead(FrameGraphBuilder frameGraphBuilder, GpuBufferSlice fog, CallbackInfo ci) {
        taskmanager$beginPhase("worldRenderer.renderWeather");
    }

    @Inject(method = "addWeatherPass", at = @At("TAIL"), require = 0)
    private void taskmanager$onRenderWeatherTail(FrameGraphBuilder frameGraphBuilder, GpuBufferSlice fog, CallbackInfo ci) {
        taskmanager$endPhase("worldRenderer.renderWeather");
    }

    @Inject(method = "addSkyPass", at = @At("HEAD"), require = 0)
    private void taskmanager$onRenderSkyHead(FrameGraphBuilder frameGraphBuilder, CameraRenderState cameraRenderState, GpuBufferSlice fog, CallbackInfo ci) {
        taskmanager$beginPhase("worldRenderer.renderSky");
    }

    @Inject(method = "addSkyPass", at = @At("TAIL"), require = 0)
    private void taskmanager$onRenderSkyTail(FrameGraphBuilder frameGraphBuilder, CameraRenderState cameraRenderState, GpuBufferSlice fog, CallbackInfo ci) {
        taskmanager$endPhase("worldRenderer.renderSky");
    }

    @Inject(method = "addCloudsPass", at = @At("HEAD"), require = 0)
    private void taskmanager$onRenderCloudsHead(FrameGraphBuilder frameGraphBuilder, CloudStatus cloudStatus, Vec3 cameraPos, long ticks, float tickProgress, int color, float cloudHeight, int cloudRange, CallbackInfo ci) {
        taskmanager$beginPhase("worldRenderer.renderClouds");
    }

    @Inject(method = "addCloudsPass", at = @At("TAIL"), require = 0)
    private void taskmanager$onRenderCloudsTail(FrameGraphBuilder frameGraphBuilder, CloudStatus cloudStatus, Vec3 cameraPos, long ticks, float tickProgress, int color, float cloudHeight, int cloudRange, CallbackInfo ci) {
        taskmanager$endPhase("worldRenderer.renderClouds");
    }

    @Inject(method = "addParticlesPass", at = @At("HEAD"), require = 0)
    private void taskmanager$onRenderParticlesHead(CallbackInfo ci) {
        taskmanager$beginPhase("worldRenderer.renderParticles");
    }

    @Inject(method = "addParticlesPass", at = @At("TAIL"), require = 0)
    private void taskmanager$onRenderParticlesTail(CallbackInfo ci) {
        taskmanager$endPhase("worldRenderer.renderParticles");
    }

    @Inject(method = "addMainPass", at = @At("HEAD"), require = 0)
    private void taskmanager$onRenderMainHead(FrameGraphBuilder frameGraphBuilder, Frustum frustum, Matrix4fc projectionMatrix, GpuBufferSlice fog, boolean renderBlockOutline, LevelRenderState levelRenderState, DeltaTracker deltaTracker, ProfilerFiller profiler, ChunkSectionsToRender chunkSectionsToRender, CallbackInfo ci) {
        taskmanager$beginPhase("worldRenderer.renderMain");
    }

    @Inject(method = "addMainPass", at = @At("TAIL"), require = 0)
    private void taskmanager$onRenderMainTail(FrameGraphBuilder frameGraphBuilder, Frustum frustum, Matrix4fc projectionMatrix, GpuBufferSlice fog, boolean renderBlockOutline, LevelRenderState levelRenderState, DeltaTracker deltaTracker, ProfilerFiller profiler, ChunkSectionsToRender chunkSectionsToRender, CallbackInfo ci) {
        taskmanager$endPhase("worldRenderer.renderMain");
    }

    @Inject(method = "renderEntities", at = @At("HEAD"), require = 0)
    private void taskmanager$onRenderEntitiesHead(CallbackInfo ci) {
        taskmanager$beginPhase("worldRenderer.renderEntities");
    }

    @Inject(method = "renderEntities", at = @At("TAIL"), require = 0)
    private void taskmanager$onRenderEntitiesTail(CallbackInfo ci) {
        taskmanager$endPhase("worldRenderer.renderEntities");
    }

    @Inject(method = "submitBlockEntities", at = @At("HEAD"), require = 0)
    private void taskmanager$onRenderBlockEntitiesHead(PoseStack poseStack, LevelRenderState levelRenderState, SubmitNodeStorage submitNodeStorage, CallbackInfo ci) {
        taskmanager$beginPhase("worldRenderer.renderBlockEntities");
    }

    @Inject(method = "submitBlockEntities", at = @At("TAIL"), require = 0)
    private void taskmanager$onRenderBlockEntitiesTail(PoseStack poseStack, LevelRenderState levelRenderState, SubmitNodeStorage submitNodeStorage, CallbackInfo ci) {
        taskmanager$endPhase("worldRenderer.renderBlockEntities");
    }

    @Inject(method = "extractVisibleEntities", at = @At("HEAD"), require = 0)
    private void taskmanager$onFillEntityRenderStatesHead(Camera camera, Frustum frustum, DeltaTracker deltaTracker, LevelRenderState levelRenderState, CallbackInfo ci) {
        taskmanager$beginCpuOnlyPhase("worldRenderer.fillEntityRenderStates");
    }

    @Inject(method = "extractVisibleEntities", at = @At("TAIL"), require = 0)
    private void taskmanager$onFillEntityRenderStatesTail(Camera camera, Frustum frustum, DeltaTracker deltaTracker, LevelRenderState levelRenderState, CallbackInfo ci) {
        taskmanager$endCpuOnlyPhase("worldRenderer.fillEntityRenderStates");
    }

    @Inject(method = "submitEntities", at = @At("HEAD"), require = 0)
    private void taskmanager$onPushEntityRendersHead(PoseStack poseStack, LevelRenderState levelRenderState, SubmitNodeCollector submitNodeCollector, CallbackInfo ci) {
        taskmanager$beginCpuOnlyPhase("worldRenderer.pushEntityRenders");
    }

    @Inject(method = "submitEntities", at = @At("TAIL"), require = 0)
    private void taskmanager$onPushEntityRendersTail(PoseStack poseStack, LevelRenderState levelRenderState, SubmitNodeCollector submitNodeCollector, CallbackInfo ci) {
        taskmanager$endCpuOnlyPhase("worldRenderer.pushEntityRenders");
    }

    @Inject(method = "extractVisibleBlockEntities", at = @At("HEAD"), require = 0)
    private void taskmanager$onFillBlockEntityRenderStatesHead(Camera camera, float tickProgress, LevelRenderState levelRenderState, CallbackInfo ci) {
        taskmanager$beginCpuOnlyPhase("worldRenderer.fillBlockEntityRenderStates");
    }

    @Inject(method = "extractVisibleBlockEntities", at = @At("TAIL"), require = 0)
    private void taskmanager$onFillBlockEntityRenderStatesTail(Camera camera, float tickProgress, LevelRenderState levelRenderState, CallbackInfo ci) {
        taskmanager$endCpuOnlyPhase("worldRenderer.fillBlockEntityRenderStates");
    }
}

