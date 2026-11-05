package wueffi.taskmanager.client.util;

public final class GpuTimerTests {
    private GpuTimerTests() {}

    public static void run() {
        asynchronousScopes();
        GpuTimer.ActiveQuery parent = query("parent", 10_000_000L);
        GpuTimer.ActiveQuery child = query("child", 4_000_000L);
        GpuTimer.ActiveQuery grandchild = query("grandchild", 1_000_000L);
        parent.children.add(child);
        child.children.add(grandchild);
        if (GpuTimer.exclusiveNanos(parent) + GpuTimer.exclusiveNanos(child) + GpuTimer.exclusiveNanos(grandchild) != 10_000_000L) {
            throw new AssertionError("nested GPU phases must account for each interval once");
        }
        parent.inclusiveNanos = 3_000_000L;
        if (GpuTimer.exclusiveNanos(parent) != 0L || GpuTimer.exclusiveNanos(query("empty", 0L)) != 0L) {
            throw new AssertionError("inconsistent or empty GPU intervals must not produce negative time");
        }
    }

    private static void asynchronousScopes() {
        FakeBackend gl = new FakeBackend();
        GpuTimer.Backend original = GpuTimer.backendForTests(gl);
        try {
            java.util.List<GpuTimer.Result> results = new java.util.ArrayList<>();
            long submittedFrame = GpuTimer.frameId(), submittedSession = GpuTimer.sessionId();
            try (var parent = GpuTimer.beginScope("pass", results::add)) {
                try (var child = GpuTimer.beginScope("compute", results::add)) {}
                try (var repeated = GpuTimer.beginScope("compute", results::add)) {}
            }
            GpuTimer.nextFrame();
            check(results.isEmpty() && gl.reads == 0, "unavailable results must never be read or block");
            gl.ready = true;
            GpuTimer.collectResults();
            check(results.size() == 3, "repeated invocations must complete independently");
            var parentResult = results.getLast();
            check(parentResult.frame() == submittedFrame && parentResult.session() == submittedSession,
                    "delayed timestamps retain submitting frame and session");
            check(parentResult.inclusiveNanos() == 5_000_000L && parentResult.exclusiveNanos() == 3_000_000L,
                    "parent keeps inclusive time while children are subtracted exactly once");
            results.clear();
            GpuTimer.begin("fixture.legacy");
            try (var measured = GpuTimer.beginScope("fixture.shader", results::add)) {}
            GpuTimer.end("fixture.legacy");
            GpuTimer.collectResults();
            check(wueffi.taskmanager.client.RenderPhaseProfiler.getInstance().getSnapshot().get("fixture.legacy").gpuNanos() == 3_000_000L,
                    "adding measured shader spans must not remove GPU cost from legacy mod attribution");
            check(results.getFirst().inclusiveNanos() == 1_000_000L, "measured and attribution trees remain independent");
            results.clear();
            gl.ready = false;
            long dropsBefore = GpuTimer.droppedSamples();
            for (int i = 0; i < GpuTimer.MAX_INTERVALS + 3; i++) {
                try (var scope = GpuTimer.beginScope("bounded", results::add)) {}
            }
            check(results.size() == 3 && results.stream().allMatch(r -> r.status().equals("dropped")),
                    "query exhaustion must count and identify dropped samples");
            check(GpuTimer.droppedSamples() == dropsBefore + 3, "drop counter tracks exhausted intervals");
            check(gl.created <= GpuTimer.MAX_INTERVALS * 2, "query object pool must remain bounded");
            GpuTimer.reset();
            check(results.size() == GpuTimer.MAX_INTERVALS + 3, "reset resolves every pending sample as cancelled");
            check(gl.deleted == gl.created, "reset deletes every allocated GL query");
            results.clear();
            var scope = GpuTimer.beginScope("disable", results::add);
            wueffi.taskmanager.client.ShaderPassProfiler.setEnabled(false);
            scope.close(); scope.close();
            gl.ready = true;
            GpuTimer.collectResults();
            check(results.size() == 1, "scope closes once even when profiling is disabled mid-pass");
            results.clear();
            try (var throwing = GpuTimer.beginScope("exception", results::add)) {
                throw new IllegalStateException("fixture");
            } catch (IllegalStateException expected) {}
            GpuTimer.collectResults();
            check(results.size() == 1 && results.getFirst().status().equals("completed"), "finally closes throwing pass");
            results.clear();
            var failed = GpuTimer.beginScope("failed-pass", results::add);
            failed.abort(); failed.close();
            check(results.size() == 1 && results.getFirst().status().equals("aborted"), "failed pass cannot be exported as a completed whole interval");
            results.clear();
            var abandoned = GpuTimer.beginScope("abandoned", results::add);
            GpuTimer.nextFrame(); abandoned.close();
            check(results.size() == 1 && results.getFirst().status().equals("aborted"), "open scope cannot leak into next frame");
            results.clear();
            try (var reload = GpuTimer.beginScope("reload", results::add)) {}
            long oldSession = GpuTimer.sessionId();
            wueffi.taskmanager.client.ShaderPassProfiler.pipelineChanged();
            check(GpuTimer.sessionId() != oldSession && results.getFirst().status().equals("cancelled"),
                    "pipeline reload invalidates pending results and rotates session");
            results.clear();
            gl.support = false;
            int allocatedBefore = gl.created;
            try (var unsupported = GpuTimer.beginScope("unsupported", results::add)) {}
            check(results.getFirst().status().equals("unsupported") && gl.created == allocatedBefore,
                    "unsupported timestamp collection reports missing data without allocating queries");
            gl.support = true; results.clear();
            GpuTimer.begin("fixture.failure");
            gl.failNextStamp = true;
            GpuTimer.begin("fixture.failure");
            GpuTimer.end("fixture.failure");
            GpuTimer.collectResults();
            check(!wueffi.taskmanager.client.RenderPhaseProfiler.getInstance().getSnapshot().containsKey("fixture.failure"),
                    "failed same-name nested begin cannot close its parent");
            GpuTimer.end("fixture.failure"); GpuTimer.collectResults();
            check(wueffi.taskmanager.client.RenderPhaseProfiler.getInstance().getSnapshot().get("fixture.failure").gpuCalls() == 1,
                    "outer legacy interval survives nested query submission failure");
            try (var shutdown = GpuTimer.beginScope("shutdown", results::add)) {}
            wueffi.taskmanager.client.ShaderPassProfiler.shutdown();
            check(results.getFirst().status().equals("cancelled") && gl.deleted == gl.created,
                    "shutdown cancels pending results and deletes query pool");
        } finally { GpuTimer.backendForTests(original); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final class FakeBackend implements GpuTimer.Backend {
        int created, deleted, reads;
        long clock;
        boolean ready, failNextStamp;
        boolean support = true;
        final java.util.Map<Integer, Long> values = new java.util.HashMap<>();
        public boolean supported() { return support; }
        public int create() { return ++created; }
        public void stamp(int id) {
            if (failNextStamp) { failNextStamp = false; throw new IllegalStateException("fixture stamp failure"); }
            values.put(id, clock += 1_000_000L);
        }
        public boolean available(int id) { return ready; }
        public long read(int id) { check(ready, "blocking read attempted"); reads++; return values.get(id); }
        public void delete(int id) { deleted++; values.remove(id); }
    }

    private static GpuTimer.ActiveQuery query(String phase, long nanos) {
        GpuTimer.ActiveQuery query = new GpuTimer.ActiveQuery(phase, 0, 0);
        query.inclusiveNanos = nanos;
        return query;
    }
}
