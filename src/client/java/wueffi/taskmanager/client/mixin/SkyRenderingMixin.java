package wueffi.taskmanager.client.mixin;

// import net.minecraft.world.MoonPhase;
import org.spongepowered.asm.mixin.Unique;
import wueffi.taskmanager.client.RenderPhaseProfiler;
import wueffi.taskmanager.client.TaskManagerScreen;
import wueffi.taskmanager.client.util.GpuTimer;
import net.minecraft.client.render.SkyRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.VertexConsumerProvider;
import org.spongepowered.asm.mixin.Mixin;
import wueffi.taskmanager.client.ProfilerManager;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRendering.class)
public class SkyRenderingMixin {

    @Inject(
            method = "renderCelestialBodies",
            at = @At("HEAD")
    )
    private void taskmanager$onSkyHead(
            MatrixStack matrices, float f, int i, float g, float h, CallbackInfo ci) {

        if (!ProfilerManager.getInstance().shouldCollectDetailedMetrics()) return;
        RenderPhaseProfiler.getInstance().beginCpuPhase("sky.renderCelestialBodies", "minecraft");
        GpuTimer.begin("sky.renderCelestialBodies");
    }

    @Inject(
            method = "renderCelestialBodies",
            at = @At("TAIL")
    )
    private void taskmanager$onSkyTail(
            MatrixStack matrices, float f, int i, float g, float h, CallbackInfo ci) {

        if (!ProfilerManager.getInstance().shouldCollectDetailedMetrics()) return;
        GpuTimer.end("sky.renderCelestialBodies");
        RenderPhaseProfiler.getInstance().endCpuPhase("sky.renderCelestialBodies");
    }
}
