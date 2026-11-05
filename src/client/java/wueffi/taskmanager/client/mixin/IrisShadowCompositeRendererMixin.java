package wueffi.taskmanager.client.mixin;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import java.util.Iterator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import wueffi.taskmanager.client.ShaderPassProfiler;
import wueffi.taskmanager.client.util.GpuTimer;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowCompositeRenderer", remap = false)
public class IrisShadowCompositeRendererMixin {
    @Unique
    private String taskmanager$stage;

    @Unique
    private Set<String> taskmanager$computeNames;

    @Unique
    private String taskmanager$passName;

    @Unique
    private Map<Object, String> taskmanager$passNames;

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/shaderpack/programs/ProgramSource;getName()Ljava/lang/String;"), require = 0)
    private String taskmanager$capturePassName(@Coerce Object source, Operation<String> original) {
        taskmanager$passName = original.call(source);
        return taskmanager$passName;
    }

    // Iris 1.11.7 never assigns ComputeOnlyPass.name in this renderer.
    @Inject(method = "createComputes", at = @At("HEAD"), require = 0)
    private void taskmanager$resetComputeNames(CallbackInfoReturnable<?> cir) {
        taskmanager$computeNames = new LinkedHashSet<>();
    }

    @WrapOperation(method = "createComputes", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/shaderpack/programs/ComputeSource;getName()Ljava/lang/String;"), require = 0)
    private String taskmanager$captureComputeName(@Coerce Object source, Operation<String> original) {
        String name = original.call(source);
        if (taskmanager$computeNames != null && name != null) {
            taskmanager$computeNames.add(name);
        }
        return name;
    }

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
            target = "Lcom/google/common/collect/ImmutableList$Builder;add(Ljava/lang/Object;)Lcom/google/common/collect/ImmutableList$Builder;"), require = 0)
    private ImmutableList.Builder<?> taskmanager$rememberPassName(ImmutableList.Builder<?> builder, Object pass,
            Operation<ImmutableList.Builder<?>> original) {
        ImmutableList.Builder<?> result = original.call(builder, pass);
        String name = taskmanager$passName;
        if (name == null && taskmanager$computeNames != null && !taskmanager$computeNames.isEmpty()) {
            name = String.join(", ", taskmanager$computeNames);
        }
        if (name != null) {
            if (taskmanager$passNames == null) {
                taskmanager$passNames = new IdentityHashMap<>();
            }
            taskmanager$passNames.put(pass, name);
        }
        taskmanager$passName = null;
        taskmanager$computeNames = null;
        return result;
    }

    @WrapOperation(method = {"createProgram", "createComputes"}, at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/pipeline/WorldRenderingPipeline;getTextureOverrides(Lnet/irisshaders/iris/shaderpack/texture/TextureStage;)Ljava/util/Set;"), require = 0)
    private Set<?> taskmanager$captureStage(@Coerce Object pipeline, @Coerce Object stage,
            Operation<Set<?>> original) {
        if (stage instanceof Enum<?> actualStage) {
            taskmanager$stage = actualStage.name().toLowerCase(Locale.ROOT);
        }
        return original.call(pipeline, stage);
    }

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
            target = "Ljava/util/Iterator;next()Ljava/lang/Object;", ordinal = 0), require = 0)
    private Object taskmanager$beginPass(Iterator<?> iterator, Operation<Object> original,
            @Share("passScope") LocalRef<GpuTimer.Scope> scope) {
        Object pass = original.call(iterator);
        String name = taskmanager$passNames == null ? null : taskmanager$passNames.get(pass);
        if (taskmanager$stage != null && name != null) {
            scope.set(ShaderPassProfiler.beginPass(name, taskmanager$stage));
        }
        return pass;
    }

    // Both the compute-only continue and the graphics path return to this outer loop condition.
    @WrapOperation(method = "renderAll()V", at = @At(value = "INVOKE",
            target = "Ljava/util/Iterator;hasNext()Z", ordinal = 0), require = 0)
    private boolean taskmanager$endPass(Iterator<?> iterator, Operation<Boolean> original,
            @Share("passScope") LocalRef<GpuTimer.Scope> scope) {
        taskmanager$closePass(scope);
        return original.call(iterator);
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
