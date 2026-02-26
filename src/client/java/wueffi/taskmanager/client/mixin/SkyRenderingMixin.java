package wueffi.taskmanager.client.mixin;

import wueffi.taskmanager.client.RenderPhaseProfiler;
import wueffi.taskmanager.client.util.GpuTimer;
import net.minecraft.client.render.SkyRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.VertexConsumerProvider;
import org.spongepowered.asm.mixin.Mixin;
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
//            MatrixStack matrices, VertexConsumerProvider.Immediate vertexConsumers, float rot, int phase, float alpha, float starBrightness, CallbackInfo ci) {
        MatrixStack matrices, float f, int i, float g, float h, CallbackInfo ci) {
        RenderPhaseProfiler.getInstance().beginCpuPhase("sky.renderCelestialBodies");
        GpuTimer.begin("sky.renderCelestialBodies");
    }

    @Inject(
            method = "renderCelestialBodies",
            at = @At("TAIL")
    )
    private void taskmanager$onSkyTail(
//            MatrixStack matrices, VertexConsumerProvider.Immediate vertexConsumers, float rot, int phase, float alpha, float starBrightness, CallbackInfo ci) {
        MatrixStack matrices, float f, int i, float g, float h, CallbackInfo ci) {
        GpuTimer.end("sky.renderCelestialBodies");
        RenderPhaseProfiler.getInstance().endCpuPhase("sky.renderCelestialBodies");
    }
}