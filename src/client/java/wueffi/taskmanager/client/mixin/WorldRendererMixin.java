package wueffi.taskmanager.client.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import org.spongepowered.asm.mixin.Unique;
import wueffi.taskmanager.client.RenderPhaseProfiler;
import wueffi.taskmanager.client.TaskManagerScreen;
import wueffi.taskmanager.client.util.GpuTimer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.ObjectAllocator;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import wueffi.taskmanager.client.ProfilerManager;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
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
        RenderPhaseProfiler.getInstance().endCpuPhase(phase);
    }

    @Inject(
            method = "render",
            at = @At("HEAD")
    )
    private void taskmanager$onRenderHead(
            ObjectAllocator allocator,
            RenderTickCounter tickCounter,
            boolean renderBlockOutline,
            Camera camera,
            Matrix4f positionMatrix,
            Matrix4f matrix4f,          // NEW PARAM
            Matrix4f projectionMatrix,
            GpuBufferSlice fog,
            Vector4f fogColor,
            boolean shouldRenderSky,
            CallbackInfo ci) {
        taskmanager$beginPhase("worldRenderer.render");
    }

    @Inject(
            method = "render",
            at = @At("TAIL")
    )
    private void taskmanager$onRenderTail(
            ObjectAllocator allocator,
            RenderTickCounter tickCounter,
            boolean renderBlockOutline,
            Camera camera,
            Matrix4f positionMatrix,
            Matrix4f matrix4f,          // NEW PARAM
            Matrix4f projectionMatrix,
            GpuBufferSlice fog,
            Vector4f fogColor,
            boolean shouldRenderSky,
            CallbackInfo ci) {
        taskmanager$endPhase("worldRenderer.render");
    }

    @Inject(
        method = "drawEntityOutlinesFramebuffer",
        at = @At("HEAD")
    )
    private void taskmanager$onOutlinesHead(CallbackInfo ci) {
        taskmanager$beginPhase("worldRenderer.entityOutlines");
    }

    @Inject(
        method = "drawEntityOutlinesFramebuffer",
        at = @At("TAIL")
    )
    private void taskmanager$onOutlinesTail(CallbackInfo ci) {
        taskmanager$endPhase("worldRenderer.entityOutlines");
    }

    @Inject(method = "renderWeather", at = @At("HEAD"), require = 0)
    private void taskmanager$onRenderWeatherHead(CallbackInfo ci) {
        taskmanager$beginPhase("worldRenderer.renderWeather");
    }

    @Inject(method = "renderWeather", at = @At("TAIL"), require = 0)
    private void taskmanager$onRenderWeatherTail(CallbackInfo ci) {
        taskmanager$endPhase("worldRenderer.renderWeather");
    }

    @Inject(method = "renderSky", at = @At("HEAD"), require = 0)
    private void taskmanager$onRenderSkyHead(CallbackInfo ci) {
        taskmanager$beginPhase("worldRenderer.renderSky");
    }

    @Inject(method = "renderSky", at = @At("TAIL"), require = 0)
    private void taskmanager$onRenderSkyTail(CallbackInfo ci) {
        taskmanager$endPhase("worldRenderer.renderSky");
    }

    @Inject(method = "renderClouds", at = @At("HEAD"), require = 0)
    private void taskmanager$onRenderCloudsHead(CallbackInfo ci) {
        taskmanager$beginPhase("worldRenderer.renderClouds");
    }

    @Inject(method = "renderClouds", at = @At("TAIL"), require = 0)
    private void taskmanager$onRenderCloudsTail(CallbackInfo ci) {
        taskmanager$endPhase("worldRenderer.renderClouds");
    }

    @Inject(method = "renderParticles", at = @At("HEAD"), require = 0)
    private void taskmanager$onRenderParticlesHead(CallbackInfo ci) {
        taskmanager$beginPhase("worldRenderer.renderParticles");
    }

    @Inject(method = "renderParticles", at = @At("TAIL"), require = 0)
    private void taskmanager$onRenderParticlesTail(CallbackInfo ci) {
        taskmanager$endPhase("worldRenderer.renderParticles");
    }

    @Inject(method = "renderMain", at = @At("HEAD"), require = 0)
    private void taskmanager$onRenderMainHead(CallbackInfo ci) {
        taskmanager$beginPhase("worldRenderer.renderMain");
    }

    @Inject(method = "renderMain", at = @At("TAIL"), require = 0)
    private void taskmanager$onRenderMainTail(CallbackInfo ci) {
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

    @Inject(method = "renderBlockEntities", at = @At("HEAD"), require = 0)
    private void taskmanager$onRenderBlockEntitiesHead(CallbackInfo ci) {
        taskmanager$beginPhase("worldRenderer.renderBlockEntities");
    }

    @Inject(method = "renderBlockEntities", at = @At("TAIL"), require = 0)
    private void taskmanager$onRenderBlockEntitiesTail(CallbackInfo ci) {
        taskmanager$endPhase("worldRenderer.renderBlockEntities");
    }

    @Inject(method = "fillEntityRenderStates", at = @At("HEAD"), require = 0)
    private void taskmanager$onFillEntityRenderStatesHead(CallbackInfo ci) {
        taskmanager$beginCpuOnlyPhase("worldRenderer.fillEntityRenderStates");
    }

    @Inject(method = "fillEntityRenderStates", at = @At("TAIL"), require = 0)
    private void taskmanager$onFillEntityRenderStatesTail(CallbackInfo ci) {
        taskmanager$endCpuOnlyPhase("worldRenderer.fillEntityRenderStates");
    }

    @Inject(method = "pushEntityRenders", at = @At("HEAD"), require = 0)
    private void taskmanager$onPushEntityRendersHead(CallbackInfo ci) {
        taskmanager$beginCpuOnlyPhase("worldRenderer.pushEntityRenders");
    }

    @Inject(method = "pushEntityRenders", at = @At("TAIL"), require = 0)
    private void taskmanager$onPushEntityRendersTail(CallbackInfo ci) {
        taskmanager$endCpuOnlyPhase("worldRenderer.pushEntityRenders");
    }

    @Inject(method = "fillBlockEntityRenderStates", at = @At("HEAD"), require = 0)
    private void taskmanager$onFillBlockEntityRenderStatesHead(CallbackInfo ci) {
        taskmanager$beginCpuOnlyPhase("worldRenderer.fillBlockEntityRenderStates");
    }

    @Inject(method = "fillBlockEntityRenderStates", at = @At("TAIL"), require = 0)
    private void taskmanager$onFillBlockEntityRenderStatesTail(CallbackInfo ci) {
        taskmanager$endCpuOnlyPhase("worldRenderer.fillBlockEntityRenderStates");
    }
}

