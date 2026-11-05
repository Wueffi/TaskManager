package wueffi.taskmanager.client.mixin;

import com.mojang.renderpearl.backend.opengl.GlProgram;
import com.mojang.renderpearl.backend.opengl.GlShaderModule;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import wueffi.taskmanager.client.ShaderCompilationProfiler;
import wueffi.taskmanager.client.ProfilerManager;

@Mixin(GlProgram.class)
public abstract class ShaderProgramMixin {

    @Inject(method = "link", at = @At("HEAD"))
    private static void taskmanager$beginShaderCompile(List<GlShaderModule> shaders, String debugLabel, CallbackInfoReturnable<GlProgram> cir) {
        if (!ProfilerManager.getInstance().shouldCollectDetailedMetrics()) {
            return;
        }
        ShaderCompilationProfiler.getInstance().beginCompile(debugLabel);
    }

    @Inject(method = "link", at = @At("RETURN"))
    private static void taskmanager$endShaderCompile(List<GlShaderModule> shaders, String debugLabel, CallbackInfoReturnable<GlProgram> cir) {
        if (!ProfilerManager.getInstance().shouldCollectDetailedMetrics()) {
            return;
        }
        ShaderCompilationProfiler.getInstance().endCompile();
    }
}
