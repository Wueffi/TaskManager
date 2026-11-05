package wueffi.taskmanager.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import wueffi.taskmanager.client.util.GpuTimer;
import java.lang.reflect.Method;
import java.util.*;
import static wueffi.taskmanager.client.RenderPhaseProfiler.ShaderPassSample;

/** Bounded raw capture, independent of CPU call counters and estimated GPU attribution. */
public final class ShaderPassProfiler {
    private static final int MAX_FRAMES = 600, MAX_SAMPLES = 32768;
    public static final String PASS_COVERAGE = "Iris composite, shadowcomp and final passes only; excludes geometry, shadow-map rendering, Voxy and other renderers";
    private static volatile boolean enabled;
    private static boolean appliedEnabled;
    private static final NavigableMap<Long, Frame> frames = new TreeMap<>();
    private static final Map<Long, Integer> retainedSessions = new HashMap<>();
    private static final Map<Long, FrameCoverage> coverage = new LinkedHashMap<>();
    private static final Map<Long, Map<String, Object>> metadata = new LinkedHashMap<>();
    private static final Set<String> hooks = new LinkedHashSet<>();
    private static final Map<Key, Long> knownPasses = new LinkedHashMap<>();
    private static int retainedSamples;
    private static long evictedFrames, nameLimitDrops;
    private static long displaySession, cachedSession = -1, rowsUpdatedAt;
    private static List<PassRow> cachedRows = List.of();
    private static String dimension = "unknown";
    private record Key(String dimension, String stage, String name) {}
    private static final class Sample {
        ShaderPassSample value;
        Sample(ShaderPassSample value) { this.value = value; }
    }
    private static final class Frame {
        final List<Sample> samples = new ArrayList<>();
        final Map<Key, Integer> invocations = new HashMap<>();
        long session = -1;
        int nextInvocation(long currentSession, Key key) {
            if (session != currentSession) { invocations.clear(); session = currentSession; }
            return invocations.merge(key, 1, Integer::sum);
        }
    }
    private static final class FrameTotals {
        long nanos, invocations, completed, pending, dropped;
        boolean complete() { return pending == 0 && dropped == 0; }
    }
    public record PassRow(String dimension, String stage, String name, long gpuNanos, long invocations,
                          long completed, long pending, long dropped, long sampledFrames,
                          double avgFrameMs, double avgInvocationMs, String status, long didNotExecuteFrames, long unavailableFrames) {}
    public record FrameCoverage(long session, long frame, String dimension, boolean closed, Set<String> stages) {}
    public record Capture(List<ShaderPassSample> samples, Map<Long, Map<String, Object>> sessions,
                          long evictedFrames, int frameLimit, int sampleLimit, List<FrameCoverage> frames, long omittedNameLimitSamples) {}
    private ShaderPassProfiler() {}
    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean value) { enabled = value; }

    /** Called each render frame, even when the TaskManager screen and ordinary collection are off. */
    public static synchronized void beginFrame() {
        FrameCoverage previous = coverage.get(GpuTimer.frameId());
        if (previous != null) coverage.put(previous.frame(), new FrameCoverage(previous.session(), previous.frame(),
                previous.dimension(), true, previous.stages()));
        if (appliedEnabled != enabled) {
            GpuTimer.reset();
            if (enabled) { knownPasses.clear(); displaySession = GpuTimer.sessionId(); }
            appliedEnabled = enabled;
            rowsUpdatedAt = 0;
        }
        GpuTimer.nextFrame();
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();
        dimension = mc.level == null ? "no_world" : mc.level.dimension().identifier().toString();
        Map<String, Object> captureMetadata = metadata.get(GpuTimer.sessionId());
        String resolution = mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight();
        int distance = mc.options.renderDistance().get();
        // A resized window/distance change must not silently mix incompatible benchmark inputs.
        if (captureMetadata != null && (!resolution.equals(captureMetadata.get("render_resolution"))
                || !Integer.valueOf(distance).equals(captureMetadata.get("render_distance")))) {
            pipelineChanged();
            captureMetadata = null;
        }
        if (captureMetadata == null) metadata.put(GpuTimer.sessionId(), captureMetadata(mc, resolution, distance));
        frames.computeIfAbsent(GpuTimer.frameId(), f -> new Frame());
        coverage.put(GpuTimer.frameId(), new FrameCoverage(GpuTimer.sessionId(), GpuTimer.frameId(), dimension, false, Set.of()));
        prune();
    }
    public static synchronized void hookExecuted(String stage) {
        hooks.add(stage);
        FrameCoverage current = coverage.get(GpuTimer.frameId());
        if (enabled && current != null && (current.session() != GpuTimer.sessionId() || !current.stages().contains(stage))) {
            Set<String> stages = current.session() == GpuTimer.sessionId() ? new LinkedHashSet<>(current.stages()) : new LinkedHashSet<>();
            stages.add(stage);
            coverage.put(current.frame(), new FrameCoverage(GpuTimer.sessionId(), current.frame(), dimension, false, Set.copyOf(stages)));
        }
    }
    public static synchronized String supportSummary() {
        List<String> missing = List.of("begin", "prepare", "deferred", "composite", "shadowcomp", "final")
                .stream().filter(stage -> !hooks.contains(stage)).toList();
        return "Observed: " + (hooks.isEmpty() ? "none" : String.join(", ", hooks))
                + "; unsupported/unconfirmed: " + (missing.isEmpty() ? "none" : String.join(", ", missing))
                + (!GpuTimer.isSupported() ? "; GPU timestamps unsupported" : "")
                + (nameLimitDrops == 0 ? "" : "; name-limit drops: " + nameLimitDrops);
    }
    public static synchronized GpuTimer.Scope beginPass(String name, String stage) {
        hookExecuted(stage);
        if (!enabled || !appliedEnabled) return () -> {};
        if (!metadata.containsKey(GpuTimer.sessionId())) {
            Minecraft mc = Minecraft.getInstance();
            metadata.put(GpuTimer.sessionId(), captureMetadata(mc,
                    mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight(), mc.options.renderDistance().get()));
        }
        Key key = new Key(dimension, stage, name);
        if (!knownPasses.containsKey(key) && knownPasses.size() >= 1024) { nameLimitDrops++; return () -> {}; }
        knownPasses.put(key, GpuTimer.frameId());
        Frame frame = frames.computeIfAbsent(GpuTimer.frameId(), f -> new Frame());
        int invocation = frame.nextInvocation(GpuTimer.sessionId(), key);
        Sample sample = new Sample(new ShaderPassSample(GpuTimer.sessionId(), GpuTimer.frameId(), dimension,
                stage, name, invocation, 0, 0, "pending"));
        frame.samples.add(sample); retainedSamples++;
        retainedSessions.merge(GpuTimer.sessionId(), 1, Integer::sum);
        prune();
        return GpuTimer.beginScope("shader/" + stage + "/" + name, result -> complete(sample, result));
    }
    private static synchronized void complete(Sample sample, GpuTimer.Result result) {
        ShaderPassSample v = sample.value;
        sample.value = new ShaderPassSample(result.session(), result.frame(), v.dimension(), v.stage(), v.pass(),
                v.invocation(), result.inclusiveNanos(), result.exclusiveNanos(), result.status());
    }
    /** Iris pipeline destruction/reload happens on the render thread while the GL context is alive. */
    public static synchronized void pipelineChanged() {
        GpuTimer.reset();
        if (enabled) { knownPasses.clear(); displaySession = GpuTimer.sessionId(); }
        hooks.clear(); rowsUpdatedAt = 0;
    }
    public static synchronized void shutdown() { GpuTimer.reset(); }
    private static void prune() {
        boolean removed = false;
        while (frames.size() > MAX_FRAMES || retainedSamples > MAX_SAMPLES) {
            removed = true;
            var removedFrame = frames.pollFirstEntry();
            coverage.remove(removedFrame.getKey());
            retainedSamples -= removedFrame.getValue().samples.size(); evictedFrames++;
            for (Sample sample : removedFrame.getValue().samples) {
                retainedSessions.computeIfPresent(sample.value.session(), (id, count) -> count == 1 ? null : count - 1);
            }
        }
        if (!removed) return;
        metadata.keySet().removeIf(id -> id != GpuTimer.sessionId() && !retainedSessions.containsKey(id));
    }
    public static synchronized List<ShaderPassSample> samples() {
        return frames.values().stream().flatMap(f -> f.samples.stream()).map(s -> s.value).toList();
    }
    public static synchronized Capture capture() {
        return new Capture(samples(), Map.copyOf(metadata), evictedFrames, MAX_FRAMES, MAX_SAMPLES, List.copyOf(coverage.values()), nameLimitDrops);
    }
    public static synchronized List<PassRow> rows() {
        long now = System.nanoTime();
        if (cachedSession != displaySession || now - rowsUpdatedAt >= 250_000_000L) {
            cachedRows = List.copyOf(aggregateSamples(() -> frames.values().stream().flatMap(f -> f.samples.stream())
                    .map(s -> s.value).iterator(), displaySession, knownPasses.keySet().stream()
                    .map(k -> List.of(k.dimension, k.stage, k.name)).toList(), List.copyOf(coverage.values())));
            rowsUpdatedAt = now; cachedSession = displaySession;
        }
        return cachedRows;
    }
    static List<PassRow> aggregate(List<ShaderPassSample> samples, long session, List<List<String>> known) {
        return aggregate(samples, session, known, List.of());
    }
    static List<PassRow> aggregate(List<ShaderPassSample> samples, long session, List<List<String>> known,
                                   List<FrameCoverage> coverage) {
        return aggregateSamples(samples, session, known, coverage);
    }
    private static List<PassRow> aggregateSamples(Iterable<ShaderPassSample> samples, long session, List<List<String>> known,
                                                 List<FrameCoverage> coverage) {
        Map<Key, Map<Long, FrameTotals>> groups = new LinkedHashMap<>();
        for (List<String> k : known) groups.put(new Key(k.get(0), k.get(1), k.get(2)), new LinkedHashMap<>());
        for (ShaderPassSample s : samples) {
            if (s.session() != session) continue;
            FrameTotals totals = groups.computeIfAbsent(new Key(s.dimension(), s.stage(), s.pass()), k -> new LinkedHashMap<>())
                    .computeIfAbsent(s.frame(), f -> new FrameTotals());
            totals.invocations++;
            switch (s.status()) {
                case "completed" -> { totals.completed++; totals.nanos += s.inclusiveGpuNanos(); }
                case "pending" -> totals.pending++;
                default -> totals.dropped++;
            }
        }
        List<PassRow> result = new ArrayList<>();
        groups.forEach((k, byFrame) -> {
            long nanos = 0, completed = 0, pending = 0, dropped = 0, invocations = 0, frameCount = 0, frameNanos = 0;
            for (FrameTotals frame : byFrame.values()) {
                invocations += frame.invocations; completed += frame.completed;
                nanos += frame.nanos; pending += frame.pending; dropped += frame.dropped;
                if (frame.complete()) { frameCount++; frameNanos += frame.nanos; }
            }
            long notExecuted = 0, unavailable = 0;
            for (FrameCoverage f : coverage) {
                if (f.session() != session || !f.dimension().equals(k.dimension) || !f.closed()) continue;
                FrameTotals measurements = byFrame.get(f.frame());
                if (measurements == null) {
                    if (f.stages().contains(k.stage)) notExecuted++; else unavailable++;
                } else if (!measurements.complete()) unavailable++;
            }
            String status = invocations == 0 ? (notExecuted > 0 ? "did_not_execute" : "unavailable")
                    : pending > 0 ? "pending" : dropped > 0 ? "unavailable" : "completed";
            result.add(new PassRow(k.dimension, k.stage, k.name, nanos, invocations, completed, pending, dropped,
                    frameCount, frameCount == 0 ? Double.NaN : frameNanos / 1e6 / frameCount,
                    completed == 0 ? Double.NaN : nanos / 1e6 / completed, status, notExecuted, unavailable));
        });
        return result;
    }
    private static Map<String, Object> captureMetadata(Minecraft mc, String resolution, int distance) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("minecraft", version("minecraft")); m.put("iris", version("iris"));
        m.put("captured_at", java.time.Instant.now().toString());
        m.put("render_resolution", resolution); m.put("render_distance", distance);
        m.put("gpu", Objects.toString(GL11.glGetString(GL11.GL_RENDERER), "unknown"));
        m.put("average_policy", "sum invocations within fully completed executing frames; excludes nonexecution and incomplete frames");
        m.put("pass_coverage", PASS_COVERAGE);
        m.put("retention_window", "rolling last " + MAX_FRAMES + " frames (max " + MAX_SAMPLES + " samples), not the full session");
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            m.put("shader_pack", Objects.toString(iris.getMethod("getCurrentPackName").invoke(null), "unavailable"));
            Optional<?> pack = (Optional<?>) iris.getMethod("getCurrentPack").invoke(null);
            Map<String, Object> settings = new TreeMap<>();
            if (pack.isPresent()) {
                Object options = pack.get().getClass().getMethod("getShaderPackOptions").invoke(pack.get());
                Object set = options.getClass().getMethod("getOptionSet").invoke(options);
                Object values = options.getClass().getMethod("getOptionValues").invoke(options);
                Class<?> valueApi = Class.forName("net.irisshaders.iris.shaderpack.option.values.OptionValues");
                for (String type : List.of("Boolean", "String")) {
                    Map<?, ?> entries = (Map<?, ?>) set.getClass().getMethod("get" + type + "Options").invoke(set);
                    Method getter = valueApi.getMethod("get" + type + "ValueOrDefault", String.class);
                    for (Object key : entries.keySet()) settings.put(key.toString(), getter.invoke(values, key.toString()));
                }
            }
            m.put("shader_settings", settings);
        } catch (ReflectiveOperationException | LinkageError e) {
            m.put("shader_metadata_status", "unavailable: " + e.getClass().getSimpleName());
        }
        return Map.copyOf(m);
    }
    private static String version(String id) {
        return FabricLoader.getInstance().getModContainer(id).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unavailable");
    }
    static String csv(Capture capture) {
        StringBuilder out = new StringBuilder("session,frame,dimension,stage,pass,invocation,gpu_ns,status\n");
        for (ShaderPassSample s : capture.samples()) {
            if (!s.status().equals("completed")) continue;
            out.append(s.session()).append(',').append(s.frame()).append(',').append(quote(s.dimension())).append(',')
                    .append(quote(s.stage())).append(',').append(quote(s.pass())).append(',').append(s.invocation())
                    .append(',').append(s.inclusiveGpuNanos()).append(',').append(s.status()).append('\n');
        }
        return out.toString();
    }
    private static String quote(String s) { return "\"" + s.replace("\"", "\"\"") + "\""; }
}
