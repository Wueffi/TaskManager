package wueffi.taskmanager.client;

import java.lang.reflect.Method;

public final class WindowsTelemetryBridgeTests {

    private WindowsTelemetryBridgeTests() {
    }

    public static void run() {
        mergeWithPreviousKeepsLastValidTemperatures();
        processScriptsAreParentBound();
        inactiveCollectionStopsHelpersAndRejectsLateStarts();
    }

    private static void inactiveCollectionStopsHelpersAndRejectsLateStarts() {
        WindowsTelemetryBridge bridge = new WindowsTelemetryBridge();
        try {
            var helperField = WindowsTelemetryBridge.class.getDeclaredField("helperProcess");
            var fallbackField = WindowsTelemetryBridge.class.getDeclaredField("fallbackProcess");
            helperField.setAccessible(true);
            fallbackField.setAccessible(true);
            StubProcess helper = new StubProcess();
            StubProcess fallback = new StubProcess();
            helperField.set(bridge, helper);
            fallbackField.set(bridge, fallback);
            bridge.setCollectionActive(false);
            bridge.setCollectionActive(false);
            if (!helper.destroyed || !fallback.destroyed || helperField.get(bridge) != null || fallbackField.get(bridge) != null) {
                throw new AssertionError("Inactive collection must stop both persistent and fallback telemetry");
            }
            Method restart = WindowsTelemetryBridge.class.getDeclaredMethod("restartHelper", int.class, String.class, String.class);
            Method refresh = WindowsTelemetryBridge.class.getDeclaredMethod("runFallbackRefresh", String.class, String.class);
            restart.setAccessible(true);
            refresh.setAccessible(true);
            restart.invoke(bridge, 1000, "", "");
            refresh.invoke(bridge, "", "");
            if (helperField.get(bridge) != null || fallbackField.get(bridge) != null) {
                throw new AssertionError("A late refresh must not restart inactive telemetry");
            }
            bridge.setCollectionActive(true);
            var activeField = WindowsTelemetryBridge.class.getDeclaredField("collectionActive");
            activeField.setAccessible(true);
            if (!activeField.getBoolean(bridge)) throw new AssertionError("Collection must be resumable");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("reflection failure", e);
        } finally {
            bridge.close();
        }
    }

    private static final class StubProcess extends Process {
        boolean destroyed;
        public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
        public java.io.InputStream getInputStream() { return java.io.InputStream.nullInputStream(); }
        public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
        public int waitFor() { return 0; }
        public int exitValue() { return 0; }
        public void destroy() { destroyed = true; }
    }

    private static void processScriptsAreParentBound() {
        String loop = WindowsTelemetryBridge.buildProcessScript("'sample'", 1000, 123);
        if (!loop.contains("GetProcessById(123)") || !loop.contains("$tmParent.Handle")
                || !loop.contains("while (!$tmParent.HasExited)") || !loop.contains("WaitForExit(1000)")) {
            throw new AssertionError("Persistent telemetry must stop when its original parent exits");
        }
        String once = WindowsTelemetryBridge.buildProcessScript("'sample'", 0, 123);
        if (!once.contains("; break") || once.contains("WaitForExit")) {
            throw new AssertionError("Fallback telemetry must remain a single sample");
        }
        WindowsTelemetryBridge bridge = new WindowsTelemetryBridge();
        bridge.close();
        bridge.close();
        try {
            Method restart = WindowsTelemetryBridge.class.getDeclaredMethod("restartHelper", int.class, String.class, String.class);
            restart.setAccessible(true);
            restart.invoke(bridge, 1000, "", "");
            Method fallback = WindowsTelemetryBridge.class.getDeclaredMethod("runFallbackRefresh", String.class, String.class);
            fallback.setAccessible(true);
            fallback.invoke(bridge, "", "");
            if (!"stopped".equals(bridge.getHealth().helperStatus()) && bridge.isSupported()) {
                throw new AssertionError("Closed bridge must not restart telemetry");
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("reflection failure", e);
        }
    }

    private static void mergeWithPreviousKeepsLastValidTemperatures() {
        try {
            WindowsTelemetryBridge bridge = new WindowsTelemetryBridge();
            Method merge = WindowsTelemetryBridge.class.getDeclaredMethod("mergeWithPrevious", WindowsTelemetryBridge.Sample.class, WindowsTelemetryBridge.Sample.class);
            merge.setAccessible(true);
            WindowsTelemetryBridge.Sample previous = new WindowsTelemetryBridge.Sample(
                    1000L, true, "Windows Performance Counters", "CPU: LibreHardwareMonitor DLL [Package] | GPU: LibreHardwareMonitor DLL [Core] | GPU Hot Spot: HWiNFO Shared Memory [Hot Spot]", "none",
                    "LibreHardwareMonitor DLL", "LibreHardwareMonitor DLL", "HWiNFO Shared Memory",
                    20.0, 60.0, 71.5, 84.0, 65.0, 100L, 200L, 300L, 400L, 25L
            );
            WindowsTelemetryBridge.Sample current = new WindowsTelemetryBridge.Sample(
                    2000L, true, "Windows Performance Counters", "Unavailable", "none",
                    "Unavailable", "Unavailable", "Unavailable",
                    25.0, 62.0, -1.0, -1.0, -1.0, 120L, 220L, 320L, 420L, 20L
            );

            WindowsTelemetryBridge.Sample merged = (WindowsTelemetryBridge.Sample) merge.invoke(bridge, current, previous);
            assertEquals(71.5, merged.gpuTemperatureC(), "GPU temperature should survive transient sensor gaps");
            assertEquals(84.0, merged.gpuHotSpotTemperatureC(), "GPU hot spot temperature should survive transient sensor gaps");
            assertEquals(65.0, merged.cpuTemperatureC(), "CPU temperature should survive transient sensor gaps");
            assertEquals("HWiNFO Shared Memory", merged.gpuHotSpotTemperatureProvider(), "GPU hot spot provider should survive transient sensor gaps");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("reflection failure", e);
        }
    }

    private static void assertEquals(double expected, double actual, String message) {
        if (Math.abs(expected - actual) > 0.0001) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
