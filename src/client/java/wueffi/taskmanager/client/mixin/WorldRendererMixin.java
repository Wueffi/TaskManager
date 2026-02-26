package wueffi.taskmanager.client.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import wueffi.taskmanager.client.RenderPhaseProfiler;
import wueffi.taskmanager.client.util.GpuTimer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.ObjectAllocator;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public class WorldRendererMixin {

    @Inject(
        method = "render",
        at = @At("HEAD")
    )
    private void taskmanager$onRenderHead(
//            ObjectAllocator allocator, RenderTickCounter tickCounter, boolean renderBlockOutline, Camera camera, Matrix4f positionMatrix, Matrix4f projectionMatrix, GpuBufferSlice fog, Vector4f fogColor, boolean shouldRenderSky, CallbackInfo ci) {
        ObjectAllocator allocator, RenderTickCounter tickCounter, boolean renderBlockOutline, Camera camera, Matrix4f positionMatrix, Matrix4f matrix4f, Matrix4f projectionMatrix, GpuBufferSlice fogBuffer, Vector4f fogColor, boolean renderSky, CallbackInfo ci) {
        RenderPhaseProfiler.getInstance().beginCpuPhase("worldRenderer.render");
        GpuTimer.begin("worldRenderer.render");
    }

    @Inject(
        method = "render",
        at = @At("TAIL")
    )
    private void taskmanager$onRenderTail(
//            ObjectAllocator allocator, RenderTickCounter tickCounter, boolean renderBlockOutline, Camera camera, Matrix4f positionMatrix, Matrix4f projectionMatrix, GpuBufferSlice fog, Vector4f fogColor, boolean shouldRenderSky, CallbackInfo ci) {
        ObjectAllocator allocator, RenderTickCounter tickCounter, boolean renderBlockOutline, Camera camera, Matrix4f positionMatrix, Matrix4f matrix4f, Matrix4f projectionMatrix, GpuBufferSlice fogBuffer, Vector4f fogColor, boolean renderSky, CallbackInfo ci) {
        GpuTimer.end("worldRenderer.render");
        RenderPhaseProfiler.getInstance().endCpuPhase("worldRenderer.render");
    }

    @Inject(
        method = "drawEntityOutlinesFramebuffer",
        at = @At("HEAD")
    )
    private void taskmanager$onOutlinesHead(CallbackInfo ci) {
        RenderPhaseProfiler.getInstance().beginCpuPhase("worldRenderer.entityOutlines");
        GpuTimer.begin("worldRenderer.entityOutlines");
    }

    @Inject(
        method = "drawEntityOutlinesFramebuffer",
        at = @At("TAIL")
    )
    private void taskmanager$onOutlinesTail(CallbackInfo ci) {
        GpuTimer.end("worldRenderer.entityOutlines");
        RenderPhaseProfiler.getInstance().endCpuPhase("worldRenderer.entityOutlines");
    }
}
