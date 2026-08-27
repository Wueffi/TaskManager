package wueffi.taskmanager.client.mixin;

import net.minecraft.client.gl.CompiledShader;
import net.minecraft.client.gl.ShaderProgram;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import wueffi.taskmanager.client.ProfilerManager;
import wueffi.taskmanager.client.ShaderCompilationProfiler;

@Mixin(ShaderProgram.class)
public abstract class ShaderProgramMixin {

    @Inject(method = "create", at = @At("HEAD"))
    private static void taskmanager$beginShaderCompile(CompiledShader vertexShader, CompiledShader fragmentShader, VertexFormat vertexFormat, String debugLabel, CallbackInfoReturnable<ShaderProgram> cir) {
        if (!ProfilerManager.getInstance().shouldCollectDetailedMetrics()) {
            return;
        }
        ShaderCompilationProfiler.getInstance().beginCompile(debugLabel);
    }

    @Inject(method = "create", at = @At("RETURN"))
    private static void taskmanager$endShaderCompile(CompiledShader vertexShader, CompiledShader fragmentShader, VertexFormat vertexFormat, String debugLabel, CallbackInfoReturnable<ShaderProgram> cir) {
        ShaderCompilationProfiler.getInstance().endCompile();
    }
}
