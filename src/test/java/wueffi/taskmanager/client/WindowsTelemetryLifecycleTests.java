package wueffi.taskmanager.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Windows-only process integration check; run separately from deterministic unit tests. */
public final class WindowsTelemetryLifecycleTests {
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            runOwner();
            return;
        }
        if (!new WindowsTelemetryBridge().isSupported()) {
            throw new IllegalStateException("This integration test requires Windows PowerShell");
        }
        checkInactiveCollection();
        checkExit(false);
        checkExit(true);
        System.out.println("Windows telemetry lifecycle tests passed.");
    }

    private static void checkInactiveCollection() throws Exception {
        WindowsTelemetryBridge bridge = new WindowsTelemetryBridge();
        Process helper = new ProcessBuilder("powershell", "-NoProfile", "-Command", "Start-Sleep -Seconds 60").start();
        Process fallback = new ProcessBuilder("powershell", "-NoProfile", "-Command", "Start-Sleep -Seconds 60").start();
        try {
            Field field = WindowsTelemetryBridge.class.getDeclaredField("helperProcess");
            Field fallbackField = WindowsTelemetryBridge.class.getDeclaredField("fallbackProcess");
            field.setAccessible(true);
            fallbackField.setAccessible(true);
            field.set(bridge, helper);
            fallbackField.set(bridge, fallback);
            bridge.setCollectionActive(false);
            if (!helper.waitFor(5, TimeUnit.SECONDS) || !fallback.waitFor(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Inactive collection left a telemetry process alive");
            }
            Method restart = WindowsTelemetryBridge.class.getDeclaredMethod("restartHelper", int.class, String.class, String.class);
            restart.setAccessible(true);
            restart.invoke(bridge, 1000, "", "");
            if (field.get(bridge) != null) throw new AssertionError("Inactive bridge restarted a helper");
            bridge.setCollectionActive(true);
            restart.invoke(bridge, 1000, "", "");
            Process resumed = (Process) field.get(bridge);
            if (resumed == null || !resumed.isAlive()) throw new AssertionError("Bridge failed to resume collection");
            bridge.setCollectionActive(false);
            if (!resumed.waitFor(5, TimeUnit.SECONDS)) throw new AssertionError("Resumed helper did not stop");
            System.out.println("Inactive collection: persistent/fallback stopped, late restart rejected, resume/stop passed");
        } finally {
            bridge.close();
            helper.destroyForcibly();
            fallback.destroyForcibly();
        }
    }

    private static void runOwner() throws Exception {
        WindowsTelemetryBridge bridge = new WindowsTelemetryBridge();
        Method restart = WindowsTelemetryBridge.class.getDeclaredMethod("restartHelper", int.class, String.class, String.class);
        restart.setAccessible(true);
        restart.invoke(bridge, 1000, "", "");
        Field field = WindowsTelemetryBridge.class.getDeclaredField("helperProcess");
        field.setAccessible(true);
        Process helper = (Process) field.get(bridge);
        if (helper == null) throw new AssertionError("Telemetry did not start");
        System.out.println("HELPER " + helper.pid());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (bridge.getLatest().capturedAtEpochMillis() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        if (bridge.getLatest().capturedAtEpochMillis() == 0) throw new AssertionError("No telemetry sample within 60 seconds");
        System.out.println("SAMPLE " + bridge.getLatest().counterSource());
        System.in.read();
        // Normal JVM exit must invoke bridge cleanup without an explicit close().
    }

    private static void checkExit(boolean force) throws Exception {
        Path arguments = Files.createTempFile("taskmanager-lifecycle-", ".args");
        Process owner = null;
        ProcessHandle helper = null;
        try {
            String classpath = System.getProperty("java.class.path").replace("\\", "\\\\").replace("\"", "\\\"");
            Files.writeString(arguments, "-cp\n\"" + classpath + "\"\n" + WindowsTelemetryLifecycleTests.class.getName() + "\nowner\n");
            owner = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                    "@" + arguments).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(owner.getInputStream()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(75);
            boolean sampled = false;
            while (System.nanoTime() < deadline && owner.isAlive()) {
                if (!reader.ready()) {
                    Thread.sleep(50);
                    continue;
                }
                String line = reader.readLine();
                if (line.startsWith("HELPER ")) helper = ProcessHandle.of(Long.parseLong(line.substring(7))).orElseThrow();
                if (line.startsWith("SAMPLE ")) {
                    sampled = true;
                    System.out.println((force ? "Forced" : "Normal") + " exit: received " + line);
                    break;
                }
            }
            if (!sampled || helper == null) throw new AssertionError("Owner did not deliver telemetry");
            if (force) owner.destroyForcibly();
            else owner.getOutputStream().close();
            if (!owner.waitFor(10, TimeUnit.SECONDS)) throw new AssertionError("Owner did not exit");
            if (!force && owner.exitValue() != 0) throw new AssertionError("Normal owner exit failed");
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (helper.isAlive() && System.nanoTime() < deadline) Thread.sleep(100);
            if (helper.isAlive()) throw new AssertionError("Telemetry helper survived owner exit");
            System.out.println((force ? "Forced" : "Normal") + " exit: helper " + helper.pid() + " terminated");
        } finally {
            if (owner != null && owner.isAlive()) owner.destroyForcibly();
            if (helper != null && helper.isAlive()) helper.destroyForcibly();
            Files.deleteIfExists(arguments);
        }
    }
}
