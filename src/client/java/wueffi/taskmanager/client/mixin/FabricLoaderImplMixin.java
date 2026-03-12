package wueffi.taskmanager.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import wueffi.taskmanager.client.StartupTimingProfiler;

import java.util.Collection;
import java.util.function.Consumer;

@Mixin(targets = "net.fabricmc.loader.impl.FabricLoaderImpl", remap = false)
public abstract class FabricLoaderImplMixin {

    @Redirect(
            method = "invokeEntrypoints",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"
            ),
            remap = false
    )
    private <T> void taskmanager$measureEntrypoint(
            Consumer<T> instance,
            T t,
            @Local(argsOnly = true) String key,
            @Local EntrypointContainer<T> container
    ) {
        long start = System.nanoTime();
        try {
            instance.accept(t);
        } finally {
            long finish = System.nanoTime();
            String modId = container == null || container.getProvider() == null ? "unknown" : container.getProvider().getMetadata().getId();
            String definition = container == null ? "" : container.getDefinition();
            StartupTimingProfiler.getInstance().recordEntrypoint(modId, key, definition, start, finish);
        }
    }
}
