package wueffi.taskmanager.client.mixin;

import wueffi.taskmanager.client.ModTimingProfiler;
import wueffi.taskmanager.client.StartupTimingProfiler;
import wueffi.taskmanager.client.util.ModClassIndex;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Proxy;

@Mixin(targets = "net.fabricmc.fabric.impl.base.event.ArrayBackedEvent", remap = false)
public class ArrayBackedEventMixin<T> {

    @Unique
    private boolean taskmanager$bypassing = false;

    @SuppressWarnings("unchecked")
    @Inject(method = "register(Ljava/lang/Object;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void taskmanager$onRegister(T listener, CallbackInfo ci) {
        if (taskmanager$bypassing) return;

        String modId = ModClassIndex.getModForClassName(listener.getClass());

        StartupTimingProfiler.getInstance().recordRegistration(modId);

        Class<?>[] interfaces = listener.getClass().getInterfaces();
        final String capturedModId = modId;

        Object proxy;
        try {
            proxy = Proxy.newProxyInstance(
                    listener.getClass().getClassLoader(),
                    interfaces,
                    (proxyObj, method, args) -> {
                        long start = System.nanoTime();
                        try {
                            return method.invoke(listener, args);
                        } finally {
                            ModTimingProfiler.getInstance().record(
                                    capturedModId,
                                    method.getName(),
                                    System.nanoTime() - start
                            );
                        }
                    }
            );
        } catch (Exception e) {
            return;
        }

        taskmanager$bypassing = true;
        try {
            this.getClass().getMethod("register", Object.class).invoke(this, proxy);
        } catch (Exception ignored) {
        } finally {
            taskmanager$bypassing = false;
        }

        ci.cancel();
    }
}