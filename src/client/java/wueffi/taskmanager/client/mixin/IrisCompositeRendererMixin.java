package wueffi.taskmanager.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import wueffi.taskmanager.client.ShaderPassProfiler;
import wueffi.taskmanager.client.util.GpuTimer;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.CompositeRenderer", remap = false)
public class IrisCompositeRendererMixin {
    @WrapMethod(method = "renderAll()V", require = 0)
    private void taskmanager$closeOnFailure(Operation<Void> original,
            @Share("passScope") LocalRef<GpuTimer.Scope> scope) {
        try {
            original.call();
        } catch (RuntimeException | Error failure) {
            GpuTimer.Scope active = scope.get();
            scope.set(null);
            if (active != null) {
                active.abort();
            }
            throw failure;
        } finally {
            taskmanager$closePass(scope);
        }
    }

    @WrapOperation(method = "renderAll()V", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/gl/GLDebug;pushGroup(ILjava/lang/String;)V", ordinal = 0), require = 0)
    private void taskmanager$captureStage(int id, String name, Operation<Void> original,
            @Share("stage") LocalRef<String> stage) {
        stage.set(name);
        original.call(id, name);
    }

    // The inner debug group includes compute-only passes and their memory barrier, not just the draw.
    @WrapOperation(method = "renderAll()V", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/gl/GLDebug;pushGroup(ILjava/lang/String;)V", ordinal = 1), require = 0)
    private void taskmanager$beginPass(int id, String name, Operation<Void> original,
            @Share("stage") LocalRef<String> stage,
            @Share("passScope") LocalRef<GpuTimer.Scope> scope) {
        original.call(id, name);
        if (stage.get() != null) {
            scope.set(ShaderPassProfiler.beginPass(name, stage.get()));
        }
    }

    @WrapOperation(method = "renderAll()V", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/gl/GLDebug;popGroup()V"), require = 0)
    private void taskmanager$endPass(Operation<Void> original,
            @Share("passScope") LocalRef<GpuTimer.Scope> scope) {
        taskmanager$closePass(scope);
        original.call();
    }

    @Unique
    private static void taskmanager$closePass(LocalRef<GpuTimer.Scope> scope) {
        GpuTimer.Scope active = scope.get();
        scope.set(null);
        if (active != null) {
            active.close();
        }
    }
}
