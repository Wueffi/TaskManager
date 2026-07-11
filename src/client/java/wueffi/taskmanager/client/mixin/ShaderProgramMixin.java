package wueffi.taskmanager.client.mixin;

import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlShaderModule;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import wueffi.taskmanager.client.ShaderCompilationProfiler;
import wueffi.taskmanager.client.TaskManagerScreen;

@Mixin(GlProgram.class)
public abstract class ShaderProgramMixin {

    @Inject(method = "link", at = @At("HEAD"))
    private static void taskmanager$beginShaderCompile(GlShaderModule vertexShader, GlShaderModule fragmentShader, VertexFormat vertexFormat, String debugLabel, CallbackInfoReturnable<GlProgram> cir) {
        if (!TaskManagerScreen.isLiveMetricsActive()) {
            return;
        }
        ShaderCompilationProfiler.getInstance().beginCompile(debugLabel);
    }

    @Inject(method = "link", at = @At("RETURN"))
    private static void taskmanager$endShaderCompile(GlShaderModule vertexShader, GlShaderModule fragmentShader, VertexFormat vertexFormat, String debugLabel, CallbackInfoReturnable<GlProgram> cir) {
        if (!TaskManagerScreen.isLiveMetricsActive()) {
            return;
        }
        ShaderCompilationProfiler.getInstance().endCompile();
    }
}
