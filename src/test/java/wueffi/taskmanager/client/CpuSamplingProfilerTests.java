package wueffi.taskmanager.client;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class CpuSamplingProfilerTests {

    private CpuSamplingProfilerTests() {
    }

    public static void run() {
        fallbackFramePrefersNonRuntimeFrameOverDriverFrame();
        gpuDriverWaitClassifiesIntoSharedStallBucket();
        drainPreservesSamplingBaselineButResetClearsIt();
    }

    @SuppressWarnings("unchecked")
    private static void drainPreservesSamplingBaselineButResetClearsIt() {
        CpuSamplingProfiler profiler = CpuSamplingProfiler.getInstance();
        try {
            profiler.reset();
            Field baselinesField = CpuSamplingProfiler.class.getDeclaredField("lastThreadCpuTimes");
            baselinesField.setAccessible(true);
            Map<Long, Long> baselines = (Map<Long, Long>) baselinesField.get(profiler);
            Field sampleTimeField = CpuSamplingProfiler.class.getDeclaredField("lastSampleAtMillis");
            sampleTimeField.setAccessible(true);
            AtomicLong sampleTime = (AtomicLong) sampleTimeField.get(profiler);
            baselines.put(42L, 123_456L);
            long capturedAt = System.currentTimeMillis() - 100L;
            sampleTime.set(capturedAt);

            Method sampleThread = CpuSamplingProfiler.class.getDeclaredMethod("sampleThread", long.class, List.class, long.class, long.class);
            sampleThread.setAccessible(true);
            List<ThreadSnapshotCollector.ThreadStackSnapshot> snapshots = List.of(
                    new ThreadSnapshotCollector.ThreadStackSnapshot(42L, "Render thread", new StackTraceElement[]{
                            new StackTraceElement("net.minecraft.client.Minecraft", "runTick", "Minecraft.java", 1)
                    }));
            sampleThread.invoke(profiler, 42L, snapshots, 17L, 1_000_000L);
            long firstBudget = profiler.drainWindow().samples().values().stream().mapToLong(CpuSamplingProfiler.Snapshot::totalCpuNanos).sum();
            if (firstBudget != 17L || !profiler.drainWindow().samples().isEmpty()) {
                throw new AssertionError("drain must emit each CPU budget exactly once");
            }
            if (!Long.valueOf(123_456L).equals(baselines.get(42L)) || sampleTime.get() != capturedAt) {
                throw new AssertionError("tick drains must preserve CPU and wall-time baselines, including empty windows");
            }
            sampleThread.invoke(profiler, 42L, snapshots, 23L, 1_000_000L);
            long secondBudget = profiler.drainWindow().samples().values().stream().mapToLong(CpuSamplingProfiler.Snapshot::totalCpuNanos).sum();
            if (secondBudget != 23L) {
                throw new AssertionError("next window must contain only its own CPU budget");
            }
            baselines.put(Long.MAX_VALUE, 1L);
            Method refreshThreads = CpuSamplingProfiler.class.getDeclaredMethod("getCachedThreadIds", long.class);
            refreshThreads.setAccessible(true);
            refreshThreads.invoke(profiler, System.currentTimeMillis());
            if (baselines.containsKey(Long.MAX_VALUE)) {
                throw new AssertionError("thread refresh must release baselines for terminated threads");
            }
            profiler.reset();
            if (!baselines.isEmpty() || sampleTime.get() != 0L) {
                throw new AssertionError("explicit capture reset must discard the old baseline");
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("reflection failure", e);
        } finally {
            profiler.reset();
        }
    }

    private static void fallbackFramePrefersNonRuntimeFrameOverDriverFrame() {
        try {
            CpuSamplingProfiler profiler = CpuSamplingProfiler.getInstance();
            Method findFallbackFrame = CpuSamplingProfiler.class.getDeclaredMethod("findFallbackFrame", StackTraceElement[].class);
            findFallbackFrame.setAccessible(true);
            StackTraceElement[] stack = new StackTraceElement[] {
                    new StackTraceElement("org.lwjgl.opengl.GL30C", "glBindFramebuffer", "GL30C.java", 0),
                    new StackTraceElement("org.lwjgl.system.JNI", "invokePV", "JNI.java", 0),
                    new StackTraceElement("net.minecraft.client.render.WorldRenderer", "render", "WorldRenderer.java", 1200)
            };

            String reason = (String) findFallbackFrame.invoke(profiler, (Object) stack);
            assertEquals("WorldRenderer#render", reason, "attribution should explain the closest non-runtime CPU frame instead of the OpenGL submission frame");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("reflection failure", e);
        }
    }

    private static void gpuDriverWaitClassifiesIntoSharedStallBucket() {
        try {
            CpuSamplingProfiler profiler = CpuSamplingProfiler.getInstance();
            Method attributeStack = CpuSamplingProfiler.class.getDeclaredMethod("attributeStack", StackTraceElement[].class, String.class, long.class, long.class);
            attributeStack.setAccessible(true);
            StackTraceElement[] stack = new StackTraceElement[] {
                    new StackTraceElement("org.lwjgl.opengl.GL32C", "glFenceSync", "GL32C.java", 0),
                    new StackTraceElement("org.lwjgl.system.JNI", "invokePV", "JNI.java", 0),
                    new StackTraceElement("net.fabricmc.fabric.impl.client.rendering.WorldRenderContextImpl", "render", "WorldRenderContextImpl.java", 120),
                    new StackTraceElement("org.embeddedt.chunky.SomeRenderHook", "draw", "SomeRenderHook.java", 64)
            };

            Object attribution = attributeStack.invoke(profiler, (Object) stack, "Render thread", 200_000L, 2_000_000L);
            Method modId = attribution.getClass().getDeclaredMethod("modId");
            String attributedMod = (String) modId.invoke(attribution);
            assertEquals("shared/gpu-stall", attributedMod, "GPU driver waits should be carried as a shared stall bucket instead of inflating a mod row");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("reflection failure", e);
        }
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
