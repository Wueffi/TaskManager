package wueffi.taskmanager.client.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wueffi.taskmanager.client.ProfilerManager;
import wueffi.taskmanager.client.RenderPhaseProfiler;
import wueffi.taskmanager.client.util.GpuTimer;

@Mixin(ParticleEngine.class)
public class ParticleManagerMixin {

    @Inject(method = "extract", at = @At("HEAD"))
    private void taskmanager$onParticlesHead(ParticlesRenderState batch, Frustum frustum, Camera camera, float tickDelta, CallbackInfo ci) {
        if (!ProfilerManager.getInstance().shouldCollectDetailedMetrics()) return;
        RenderPhaseProfiler.getInstance().beginCpuPhase("particles.render", "shared/render");
        GpuTimer.begin("particles.render");
    }

    @Inject(method = "extract", at = @At("TAIL"))
    private void taskmanager$onParticlesTail(ParticlesRenderState batch, Frustum frustum, Camera camera, float tickDelta, CallbackInfo ci) {
        if (!ProfilerManager.getInstance().shouldCollectDetailedMetrics()) return;
        GpuTimer.end("particles.render");
        RenderPhaseProfiler.getInstance().endCpuPhase("particles.render");
    }
}
