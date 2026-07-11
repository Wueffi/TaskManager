package wueffi.taskmanager.client.mixin;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wueffi.taskmanager.client.InputLatencyProfiler;

@Mixin(MouseHandler.class)
public class MouseMixin {

    @Inject(method = "onMove", at = @At("HEAD"))
    private void taskmanager$onCursorPos(long window, double x, double y, CallbackInfo ci) {
        InputLatencyProfiler.getInstance().recordMouseMove(x, y);
    }

    @Inject(method = "onButton", at = @At("HEAD"))
    private void taskmanager$onMouseButton(long window, MouseButtonInfo input, int modifiers, CallbackInfo ci) {
        InputLatencyProfiler.getInstance().recordInputEvent();
    }

    @Inject(method = "onScroll", at = @At("HEAD"))
    private void taskmanager$onMouseScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        InputLatencyProfiler.getInstance().recordInputEvent();
    }
}
