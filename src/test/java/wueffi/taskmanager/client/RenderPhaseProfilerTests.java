package wueffi.taskmanager.client;

import java.util.concurrent.atomic.AtomicReference;

public final class RenderPhaseProfilerTests {

    private RenderPhaseProfilerTests() {
    }

    public static void run() {
        nestedCpuScopesTrackEachPhaseSeparately();
        renderContextOwnerBecomesLikelyOwnerHint();
        drainPreservesActiveScopeAndContext();
        ownerSamplingCanRaceScopeUpdatesAndDrains();
    }

    private static void ownerSamplingCanRaceScopeUpdatesAndDrains() {
        RenderPhaseProfiler profiler = RenderPhaseProfiler.getInstance();
        profiler.reset();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        long renderThread = Thread.currentThread().threadId();
        Thread sampler = new Thread(() -> {
            try {
                for (int i = 0; i < 2_000; i++) {
                    profiler.recordLikelyOwnerSample(renderThread, "chunky", "worker#draw");
                    profiler.drainSnapshot();
                }
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        sampler.setDaemon(true);
        sampler.start();
        for (int i = 0; i < 2_000; i++) {
            profiler.beginCpuPhase("outer", "shared/render");
            profiler.beginCpuPhase("inner", "shared/render");
            profiler.endCpuPhase("inner");
            profiler.endCpuPhase("outer");
        }
        try {
            sampler.join(5_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("concurrent scope test interrupted", e);
        }
        assertTrue(!sampler.isAlive(), "concurrent sampling/draining must terminate");
        if (failure.get() != null) {
            throw new AssertionError("concurrent scope access failed", failure.get());
        }
        profiler.reset();
    }

    private static void drainPreservesActiveScopeAndContext() {
        RenderPhaseProfiler profiler = RenderPhaseProfiler.getInstance();
        profiler.reset();
        profiler.pushContextOwner("chunky");
        profiler.beginCpuPhase("outer", "shared/render");
        profiler.drainSnapshot();
        profiler.beginCpuPhase("inner", "shared/render");
        profiler.endCpuPhase("inner");
        profiler.endCpuPhase("outer");
        profiler.popContextOwner();
        var snapshot = profiler.drainSnapshot();
        assertNotNull(snapshot.get("outer"), "drain must not discard an in-flight phase");
        assertEquals(1L, snapshot.get("outer").cpuCalls(), "in-flight phase completes once");
        assertEquals(1L, snapshot.get("inner").likelyOwners().getOrDefault("chunky", 0L), "context survives a drain");
        assertTrue(profiler.drainSnapshot().isEmpty(), "drained counters must not reappear");
        profiler.reset();
    }

    private static void nestedCpuScopesTrackEachPhaseSeparately() {
        RenderPhaseProfiler profiler = RenderPhaseProfiler.getInstance();
        profiler.reset();

        profiler.beginCpuPhase("outer");
        profiler.beginCpuPhase("inner");
        profiler.endCpuPhase("inner");
        profiler.endCpuPhase("outer");

        RenderPhaseProfiler.PhaseSnapshot outer = profiler.getSnapshot().get("outer");
        RenderPhaseProfiler.PhaseSnapshot inner = profiler.getSnapshot().get("inner");

        assertNotNull(outer, "outer phase snapshot");
        assertNotNull(inner, "inner phase snapshot");
        assertEquals(1L, outer.cpuCalls(), "outer call count");
        assertEquals(1L, inner.cpuCalls(), "inner call count");
        assertTrue(outer.cpuNanos() > 0L, "outer cpu nanos");
        assertTrue(inner.cpuNanos() > 0L, "inner cpu nanos");
    }

    private static void renderContextOwnerBecomesLikelyOwnerHint() {
        RenderPhaseProfiler profiler = RenderPhaseProfiler.getInstance();
        profiler.reset();

        profiler.pushContextOwner("chunky");
        profiler.beginCpuPhase("worldRenderer.renderEntities", "shared/render");
        profiler.endCpuPhase("worldRenderer.renderEntities");
        profiler.popContextOwner();

        RenderPhaseProfiler.PhaseSnapshot snapshot = profiler.getSnapshot().get("worldRenderer.renderEntities");
        assertNotNull(snapshot, "render phase snapshot");
        assertEquals("shared/render", snapshot.ownerMod(), "shared phase owner should stay honest");
        assertEquals(1L, snapshot.likelyOwners().getOrDefault("chunky", 0L), "context owner should be captured as a likely owner hint");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertNotNull(Object value, String message) {
        if (value == null) {
            throw new AssertionError(message);
        }
    }
}
