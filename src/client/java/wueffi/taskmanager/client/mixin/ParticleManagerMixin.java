package wueffi.taskmanager.client.mixin;

import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.SubmittableBatch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wueffi.taskmanager.client.ProfilerManager;
import wueffi.taskmanager.client.RenderPhaseProfiler;
import wueffi.taskmanager.client.util.GpuTimer;

@Mixin(ParticleManager.class)
public class ParticleManagerMixin {

    @Inject(method = "addToBatch", at = @At("HEAD"))
    private void taskmanager$onParticlesHead(SubmittableBatch batch, Frustum frustum, Camera camera, float tickDelta, CallbackInfo ci) {
        if (!ProfilerManager.getInstance().shouldCollectDetailedMetrics()) return;
        RenderPhaseProfiler.getInstance().beginCpuPhase("particles.render", "shared/render");
        GpuTimer.begin("particles.render");
    }

    @Inject(method = "addToBatch", at = @At("TAIL"))
    private void taskmanager$onParticlesTail(SubmittableBatch batch, Frustum frustum, Camera camera, float tickDelta, CallbackInfo ci) {
        GpuTimer.end("particles.render");
        RenderPhaseProfiler.getInstance().endCpuPhase("particles.render");
    }
}
