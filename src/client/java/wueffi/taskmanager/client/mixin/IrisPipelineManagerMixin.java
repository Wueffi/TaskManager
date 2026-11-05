package wueffi.taskmanager.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wueffi.taskmanager.client.ShaderPassProfiler;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.PipelineManager", remap = false)
public class IrisPipelineManagerMixin {
    @Unique
    private Object taskmanager$pipeline;

    @WrapOperation(method = "preparePipeline", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lnet/irisshaders/iris/pipeline/PipelineManager;pipeline:Lnet/irisshaders/iris/pipeline/WorldRenderingPipeline;"), require = 0)
    private void taskmanager$onPipelineChanged(@Coerce Object manager, @Coerce Object pipeline,
            Operation<Void> original) {
        original.call(manager, pipeline);
        if (taskmanager$pipeline != pipeline) {
            taskmanager$pipeline = pipeline;
            ShaderPassProfiler.pipelineChanged();
        }
    }

    @Inject(method = "destroyPipeline()V", at = @At("HEAD"), require = 0)
    private void taskmanager$onDestroy(CallbackInfo ci) {
        taskmanager$pipeline = null;
        ShaderPassProfiler.pipelineChanged();
    }
}
