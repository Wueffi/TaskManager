package wueffi.taskmanager.client;

import java.util.*;
import java.lang.reflect.*;
import wueffi.taskmanager.client.util.GpuTimer;
import static wueffi.taskmanager.client.RenderPhaseProfiler.ShaderPassSample;

final class ShaderPassProfilerTests {
    static void run() {
        stoppedCaptureRemainsSelected();
        invocationCountersFollowCaptureBoundaries();
        evictionOnlyVisitsRemovedSamples();
        sampleLimitEvictsWholeFrames();
        rowsObserveInclusiveCompletion();
        List<ShaderPassSample> samples = List.of(
            sample(1, "overworld", 1, 2_000_000, "completed"),
            sample(1, "overworld", 2, 4_000_000, "completed"),
            sample(2, "overworld", 1, 8_000_000, "completed"),
            sample(2, "overworld", 2, 0, "pending"),
            sample(3, "overworld", 1, 0, "dropped"),
            sample(4, "nether", 1, 10_000_000, "completed"),
            new ShaderPassSample(9, 5, "overworld", "shadowcomp", "floodfill", 1, 99_000_000, 0, "completed"));
        var rows = ShaderPassProfiler.aggregate(samples, 1, List.of(List.of("overworld", "shadowcomp", "absent")),
                List.of(new ShaderPassProfiler.FrameCoverage(1, 1, "overworld", true, Set.of("shadowcomp")),
                        new ShaderPassProfiler.FrameCoverage(1, 6, "overworld", true, Set.of())));
        var pass = rows.stream().filter(r -> r.name().equals("floodfill") && r.dimension().equals("overworld")).findFirst().orElseThrow();
        check(pass.avgFrameMs() == 6.0 && pass.sampledFrames() == 1, "sum repeated invocations before frame averaging; omit incomplete frames");
        check(Math.abs(pass.avgInvocationMs() - 14.0 / 3) < 1e-9, "invocation average uses completed GPU samples, not CPU calls");
        check(pass.completed() == 3 && pass.pending() == 1 && pass.dropped() == 1 && pass.invocations() == 5,
                "pending, dropped and completed are distinct; old sessions excluded");
        var absent = rows.stream().filter(r -> r.name().equals("absent")).findFirst().orElseThrow();
        check(absent.didNotExecuteFrames() == 1 && absent.unavailableFrames() == 1,
                "only observed stage absence proves nonexecution; missing hook evidence stays unavailable");
        check(rows.stream().anyMatch(r -> r.status().equals("did_not_execute") && Double.isNaN(r.avgFrameMs())),
                "non-execution is not a measured zero or unavailable sample");
        check(rows.stream().filter(r -> r.dimension().equals("nether")).findFirst().orElseThrow().avgFrameMs() == 10,
                "dimension samples are never mixed");
        var unavailable = ShaderPassProfiler.aggregate(List.of(sample(1, "overworld", 1, 0, "unsupported")), 1, List.of()).getFirst();
        check(Double.isNaN(unavailable.avgFrameMs()) && Double.isNaN(unavailable.avgInvocationMs()), "missing measurements cannot display zero milliseconds");
        String csv = ShaderPassProfiler.csv(new ShaderPassProfiler.Capture(samples, Map.of(1L, Map.of("gpu", "fixture")), 0, 600, 32768, List.of(), 0));
        check(csv.startsWith("session,frame,dimension,stage,pass,invocation,gpu_ns,status\n"), "CSV has exact requested columns");
        check(csv.lines().count() == 6 && !csv.contains("pending") && !csv.contains("dropped"), "CSV contains only raw completed intervals");
        var quoted = new ShaderPassSample(1, 1, "a,b", "stage", "pass\"name", 1, 0, 0, "completed");
        csv = ShaderPassProfiler.csv(new ShaderPassProfiler.Capture(List.of(quoted), Map.of(), 0, 600, 32768, List.of(), 0));
        check(csv.contains("\"a,b\"") && csv.contains("\"pass\"\"name\""), "CSV escapes names and dimensions");
    }
    private static void invocationCountersFollowCaptureBoundaries() {
        try (CaptureFixture f = new CaptureFixture()) {
            ShaderPassProfiler.beginPass("pass", "composite").close();
            ShaderPassProfiler.beginPass("pass", "composite").close();
            ShaderPassProfiler.beginPass("pass", "final").close();
            field(ShaderPassProfiler.class, "dimension").set(null, "nether");
            ShaderPassProfiler.beginPass("pass", "composite").close();
            field(ShaderPassProfiler.class, "dimension").set(null, "overworld");
            ShaderPassProfiler.beginPass("pass", "composite").close();
            ShaderPassProfiler.beginPass("other", "composite").close();
            check(ShaderPassProfiler.samples().stream().map(ShaderPassSample::invocation).toList().equals(List.of(1, 2, 1, 1, 3, 1)),
                    "invocation counters distinguish names, stages and dimensions");
            Object frame = f.frames.values().iterator().next();
            Field samplesField = field(frame.getClass(), "samples");
            @SuppressWarnings("unchecked") List<Object> samples = (List<Object>) samplesField.get(frame);
            int[] reads = {0};
            samplesField.set(frame, new CountingSamples(samples, reads));
            ShaderPassProfiler.beginPass("pass", "composite").close();
            check(reads[0] == 0, "assigning the next invocation never scans existing frame samples");
            ShaderPassProfiler.pipelineChanged();
            f.seedSession();
            ShaderPassProfiler.beginPass("pass", "composite").close();
            check(ShaderPassProfiler.samples().getLast().invocation() == 1, "same-frame session reset starts invocation numbering again");
            GpuTimer.nextFrame();
            ShaderPassProfiler.beginPass("pass", "composite").close();
            check(ShaderPassProfiler.samples().getLast().invocation() == 1, "new frame starts invocation numbering again");
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void evictionOnlyVisitsRemovedSamples() {
        try (CaptureFixture f = new CaptureFixture()) {
            long oldSession = GpuTimer.sessionId();
            for (int i = 0; i < 600; i++) {
                if (i == 300) { ShaderPassProfiler.pipelineChanged(); f.seedSession(); }
                f.addFrame(1);
            }
            long firstFrame = ShaderPassProfiler.samples().getFirst().frame();
            Object first = f.frames.values().iterator().next();
            Field samplesField = first.getClass().getDeclaredField("samples"); samplesField.setAccessible(true);
            Object detached = ((List<?>) samplesField.get(first)).getFirst();
            int[] reads = {0};
            for (Object frame : f.frames.values()) {
                @SuppressWarnings("unchecked") List<Object> samples = (List<Object>) samplesField.get(frame);
                samplesField.set(frame, new CountingSamples(samples, reads));
            }
            f.addFrame(1);
            check(reads[0] == 1, "steady-state eviction visits only the removed sample, never retained samples");
            var capture = ShaderPassProfiler.capture();
            check(capture.samples().size() == 600 && capture.frames().size() == 600 && capture.evictedFrames() == 1,
                    "frame limit removes samples and coverage together");
            check(capture.samples().getFirst().frame() == firstFrame + 1 && capture.sessions().containsKey(oldSession),
                    "old session metadata remains while any of its samples survive");
            var complete = ShaderPassProfiler.class.getDeclaredMethod("complete", detached.getClass(), GpuTimer.Result.class);
            complete.setAccessible(true);
            complete.invoke(null, detached, new GpuTimer.Result(oldSession, firstFrame, 900, 100, "completed"));
            check(ShaderPassProfiler.samples().stream().noneMatch(s -> s.frame() == firstFrame),
                    "late GPU completion cannot resurrect an evicted sample");
            for (int i = 0; i < 299; i++) f.addFrame(1);
            check(!ShaderPassProfiler.capture().sessions().containsKey(oldSession), "metadata is removed after the last retained session sample");
            check(f.retainedSessions.equals(Map.of(GpuTimer.sessionId(), 600)), "session counts track retained samples exactly");
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void sampleLimitEvictsWholeFrames() {
        try (CaptureFixture f = new CaptureFixture()) {
            for (int i = 0; i < 33; i++) f.addFrame(1024);
            var capture = ShaderPassProfiler.capture();
            check(capture.samples().size() == 32768 && capture.frames().size() == 32 && capture.evictedFrames() == 1,
                    "sample cap evicts an entire oldest frame without lowering either configured limit");
            check(capture.samples().getFirst().invocation() == 1 && capture.samples().getLast().invocation() == 1024,
                    "retention does not corrupt per-frame invocation numbering");
            check(f.retainedSessions.equals(Map.of(GpuTimer.sessionId(), 32768)), "sample-limit eviction updates session counts");
            f.addFrame(32769);
            check(ShaderPassProfiler.samples().isEmpty() && f.retainedSessions.isEmpty(),
                    "a single overflowing frame is wholly evicted and releases session counts");
            ShaderPassProfiler.beginPass("pass", "composite").close();
            check(ShaderPassProfiler.samples().getFirst().invocation() == 1,
                    "recreated current frame restarts numbering just like the retained-sample scan");
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void rowsObserveInclusiveCompletion() {
        try (CaptureFixture f = new CaptureFixture()) {
            ShaderPassProfiler.beginPass("pass", "composite").close();
            Object frame = f.frames.values().iterator().next();
            Object sample = ((List<?>) field(frame.getClass(), "samples").get(frame)).getFirst();
            field(ShaderPassProfiler.class, "displaySession").set(null, GpuTimer.sessionId());
            field(ShaderPassProfiler.class, "cachedSession").set(null, -1L);
            check(ShaderPassProfiler.rows().getFirst().status().equals("unavailable"), "unsupported intervals remain unavailable in live rows");
            var complete = ShaderPassProfiler.class.getDeclaredMethod("complete", sample.getClass(), GpuTimer.Result.class);
            complete.setAccessible(true);
            complete.invoke(null, sample, new GpuTimer.Result(GpuTimer.sessionId(), GpuTimer.frameId(), 2_000_000, 500_000, "completed"));
            field(ShaderPassProfiler.class, "rowsUpdatedAt").set(null, 0L);
            var row = ShaderPassProfiler.rows().getFirst();
            check(row.gpuNanos() == 2_000_000 && row.avgFrameMs() == 2 && row.status().equals("completed"),
                    "row refresh observes callback updates and continues using inclusive GPU time");
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static final class CountingSamples extends AbstractList<Object> {
        final List<Object> samples;
        final int[] reads;
        CountingSamples(List<Object> samples, int[] reads) { this.samples = samples; this.reads = reads; }
        public Object get(int index) { reads[0]++; return samples.get(index); }
        public int size() { return samples.size(); }
        public boolean add(Object sample) { return samples.add(sample); }
    }
    private static final class CaptureFixture implements AutoCloseable {
        final Map<Field, Object> saved = new LinkedHashMap<>();
        final Map<Long, Object> frames;
        final Map<Long, Object> coverage;
        final Map<Long, Object> metadata;
        final Map<Long, Integer> retainedSessions;
        @SuppressWarnings("unchecked") CaptureFixture() throws ReflectiveOperationException {
            for (String name : List.of("frames", "coverage", "metadata", "knownPasses", "retainedSessions", "hooks",
                    "enabled", "appliedEnabled", "retainedSamples", "evictedFrames", "nameLimitDrops", "dimension",
                    "displaySession", "cachedSession", "rowsUpdatedAt", "cachedRows")) {
                Field field = field(ShaderPassProfiler.class, name); Object value = field.get(null);
                if (value instanceof Map<?, ?> map) { saved.put(field, new LinkedHashMap<>(map)); map.clear(); }
                else if (value instanceof Set<?> set) { saved.put(field, new LinkedHashSet<>(set)); set.clear(); }
                else saved.put(field, value);
            }
            for (String name : List.of("backend", "session", "frame")) {
                Field field = field(GpuTimer.class, name); saved.put(field, field.get(null));
            }
            Field backend = field(GpuTimer.class, "backend");
            backend.set(null, Proxy.newProxyInstance(backend.getType().getClassLoader(), new Class<?>[]{backend.getType()},
                    (proxy, method, args) -> {
                        if (method.getName().equals("supported")) return false;
                        throw new AssertionError("unsupported fixture must not access GPU queries");
                    }));
            frames = (Map<Long, Object>) field(ShaderPassProfiler.class, "frames").get(null);
            coverage = (Map<Long, Object>) field(ShaderPassProfiler.class, "coverage").get(null);
            metadata = (Map<Long, Object>) field(ShaderPassProfiler.class, "metadata").get(null);
            retainedSessions = (Map<Long, Integer>) field(ShaderPassProfiler.class, "retainedSessions").get(null);
            field(ShaderPassProfiler.class, "enabled").set(null, true);
            field(ShaderPassProfiler.class, "appliedEnabled").set(null, true);
            field(ShaderPassProfiler.class, "retainedSamples").set(null, 0);
            field(ShaderPassProfiler.class, "evictedFrames").set(null, 0L);
            field(ShaderPassProfiler.class, "dimension").set(null, "overworld");
            seedSession();
        }
        void seedSession() { metadata.put(GpuTimer.sessionId(), Map.of("fixture", true)); }
        void addFrame(int samples) {
            GpuTimer.nextFrame();
            coverage.put(GpuTimer.frameId(), new ShaderPassProfiler.FrameCoverage(GpuTimer.sessionId(), GpuTimer.frameId(), "overworld", true, Set.of("composite")));
            for (int i = 0; i < samples; i++) ShaderPassProfiler.beginPass("pass", "composite").close();
        }
        @SuppressWarnings("unchecked") public void close() throws ReflectiveOperationException {
            for (var entry : saved.entrySet()) {
                Object value = entry.getKey().get(null);
                if (value instanceof Map<?, ?>) { Map<Object, Object> map = (Map<Object, Object>) value; map.clear(); map.putAll((Map<?, ?>) entry.getValue()); }
                else if (value instanceof Set<?>) { Set<Object> set = (Set<Object>) value; set.clear(); set.addAll((Set<?>) entry.getValue()); }
                else entry.getKey().set(null, entry.getValue());
            }
        }
    }
    private static void stoppedCaptureRemainsSelected() {
        try {
            var applied = ShaderPassProfiler.class.getDeclaredField("appliedEnabled"); applied.setAccessible(true);
            var selected = ShaderPassProfiler.class.getDeclaredField("displaySession"); selected.setAccessible(true);
            boolean oldApplied = applied.getBoolean(null);
            long oldSelected = selected.getLong(null);
            try {
                applied.setBoolean(null, true); selected.setLong(null, 12345);
                ShaderPassProfiler.setEnabled(false);
                ShaderPassProfiler.beginFrame();
                check(selected.getLong(null) == 12345 && !applied.getBoolean(null),
                        "turning collection off must preserve the captured session selected for display");
            } finally { applied.setBoolean(null, oldApplied); selected.setLong(null, oldSelected); }
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static ShaderPassSample sample(long frame, String dimension, int invocation, long ns, String status) {
        return new ShaderPassSample(1, frame, dimension, "shadowcomp", "floodfill", invocation, ns, ns, status);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
