package wueffi.taskmanager.client.mixin;

// import net.minecraft.world.MoonPhase;
import org.spongepowered.asm.mixin.Unique;
import wueffi.taskmanager.client.RenderPhaseProfiler;
import wueffi.taskmanager.client.TaskManagerScreen;
import wueffi.taskmanager.client.util.GpuTimer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import wueffi.taskmanager.client.ProfilerManager;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRenderer.class)
public class SkyRenderingMixin {

    @Inject(
            method = "renderSunMoonAndStars",
            at = @At("HEAD")
    )
    private void taskmanager$onSkyHead(
            PoseStack matrices,
            float sunAngle,
            float moonAngle,
            float starAngle,
            net.minecraft.world.level.MoonPhase moonPhase,
            float alpha,
            float starBrightness,
            CallbackInfo ci) {

        if (!ProfilerManager.getInstance().shouldCollectDetailedMetrics()) return;
        RenderPhaseProfiler.getInstance().beginCpuPhase("sky.renderCelestialBodies", "shared/render");
        GpuTimer.begin("sky.renderCelestialBodies");
    }

    @Inject(
            method = "renderSunMoonAndStars",
            at = @At("TAIL")
    )
    private void taskmanager$onSkyTail(
            PoseStack matrices,
            float sunAngle,
            float moonAngle,
            float starAngle,
            net.minecraft.world.level.MoonPhase moonPhase,
            float alpha,
            float starBrightness,
            CallbackInfo ci) {

        if (!ProfilerManager.getInstance().shouldCollectDetailedMetrics()) return;
        GpuTimer.end("sky.renderCelestialBodies");
        RenderPhaseProfiler.getInstance().endCpuPhase("sky.renderCelestialBodies");
    }
}
