package wueffi.taskmanager.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import wueffi.taskmanager.client.ProfilerManager;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import wueffi.taskmanager.client.ModTimingProfiler;
import wueffi.taskmanager.client.ModExecutionContext;
import wueffi.taskmanager.client.StartupTimingProfiler;
import wueffi.taskmanager.client.RenderPhaseProfiler;
import wueffi.taskmanager.client.TaskManagerScreen;
import wueffi.taskmanager.client.util.ModClassIndex;

import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

@Mixin(targets = "net.fabricmc.fabric.impl.base.event.ArrayBackedEvent", remap = false)
public abstract class ArrayBackedEventMixin {

    private static final Map<Object, Map<Class<?>, Object>> LISTENER_PROXY_CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    @Redirect(
            method = "update",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/function/Function;apply(Ljava/lang/Object;)Ljava/lang/Object;"
            )
    )
    private Object taskmanager$wrapInvoker(java.util.function.Function<Object, Object> factory, Object listeners) {
        recordStartupListeners(listeners);
        Object profilerAwareListeners = maybeWrapListeners(listeners);
        Object original = factory.apply(profilerAwareListeners);

        if (!ProfilerManager.getInstance().shouldCollectDetailedMetrics()) {
            return original;
        }

        Class<?>[] interfaces = original.getClass().getInterfaces();
        if (interfaces.length == 0) {
            return original;
        }

        return Proxy.newProxyInstance(
                original.getClass().getClassLoader(),
                interfaces,
                (proxy, method, args) -> {
                    long start = System.nanoTime();

                    try {
                        return method.invoke(original, args);
                    } finally {
                        long duration = System.nanoTime() - start;
                        String mod = ModClassIndex.getModForClassName(original.getClass());
                        if (mod == null) mod = "unknown";

                        ModTimingProfiler.getInstance().record(mod, method.getName(), duration);
                    }
                }
        );
    }

    private Object maybeWrapListeners(Object listeners) {
        if (!ProfilerManager.getInstance().shouldCollectDetailedMetrics() || listeners == null || !listeners.getClass().isArray()) {
            return listeners;
        }

        int length = Array.getLength(listeners);
        Class<?> componentType = listeners.getClass().getComponentType();
        if (componentType == null || !componentType.isInterface()) {
            return listeners;
        }

        Object wrapped = Array.newInstance(componentType, length);
        for (int i = 0; i < length; i++) {
            Object listener = Array.get(listeners, i);
            if (listener == null) {
                Array.set(wrapped, i, null);
                continue;
            }
            Array.set(wrapped, i, wrapListener(listener, componentType));
        }
        return wrapped;
    }

    private Object wrapListener(Object listener, Class<?> listenerInterface) {
        String mod = ModClassIndex.getModForClassName(listener.getClass());
        if (mod == null) {
            mod = "minecraft";
        }
        String resolvedMod = mod;
        synchronized (LISTENER_PROXY_CACHE) {
            Map<Class<?>, Object> proxiesByInterface = LISTENER_PROXY_CACHE.computeIfAbsent(listener, ignored -> new WeakHashMap<>());
            return proxiesByInterface.computeIfAbsent(listenerInterface, ignored -> Proxy.newProxyInstance(
                    listener.getClass().getClassLoader(),
                    new Class<?>[] {listenerInterface},
                    (proxy, method, args) -> {
                        long start = System.nanoTime();
                        boolean renderThread = isRenderThread();
                        String reason = "fabric event " + listenerInterface.getSimpleName() + "#" + method.getName();
                        ModExecutionContext.push(resolvedMod, reason);
                        if (renderThread) {
                            RenderPhaseProfiler.getInstance().pushContextOwner(resolvedMod);
                        }
                        try {
                            return method.invoke(listener, args);
                        } finally {
                            if (renderThread) {
                                RenderPhaseProfiler.getInstance().popContextOwner();
                            }
                            ModExecutionContext.pop();
                            long duration = System.nanoTime() - start;
                            ModTimingProfiler.getInstance().record(resolvedMod, method.getName(), duration);
                        }
                    }
            ));
        }
    }

    private boolean isRenderThread() {
        String threadName = Thread.currentThread().getName();
        return threadName != null && threadName.toLowerCase(Locale.ROOT).contains("render");
    }

    private void recordStartupListeners(Object listeners) {
        if (StartupTimingProfiler.closed || listeners == null || !listeners.getClass().isArray()) {
            return;
        }

        int length = Array.getLength(listeners);
        for (int i = 0; i < length; i++) {
            Object listener = Array.get(listeners, i);
            if (listener == null) continue;

            String mod = ModClassIndex.getModForClassName(listener.getClass());
            if (mod == null) {
                mod = "minecraft";
            }
            StartupTimingProfiler.getInstance().recordRegistration(mod);
        }
    }
}
