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
@Mixin(targets = "net.irisshaders.iris.pipeline.FinalPassRenderer", remap = false)
public class IrisFinalPassRendererMixin {
    @WrapMethod(method = "renderFinalPass()V", require = 0)
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

    // Only the shader branch has this debug group; fallback copying and swap passes are outside it.
    @WrapOperation(method = "renderFinalPass()V", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/gl/GLDebug;pushGroup(ILjava/lang/String;)V"), require = 0)
    private void taskmanager$beginPass(int id, String name, Operation<Void> original,
            @Share("passScope") LocalRef<GpuTimer.Scope> scope) {
        original.call(id, name);
        scope.set(ShaderPassProfiler.beginPass(name, name));
    }

    @WrapOperation(method = "renderFinalPass()V", at = @At(value = "INVOKE",
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
