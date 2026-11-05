package wueffi.taskmanager.client;

public final class ProfilerManagerTests {

    private ProfilerManagerTests() {
    }

    public static void run() {
        snapshotPublishingHonorsForceAndDelay();
        summaryCadenceDoesNotTreatThrottlingAsDroppedSamples();
        sessionHistoryLimitFollowsSummaryCadence();
        jvmSamplingSkipsWorkInsideCadence();
        missedSampleCountDetectsDroppedIntervals();
        observedSampleIntervalUsesAverageGap();
        sessionLoggingKeepsCaptureActiveInManualDeep();
        passiveModeSkipsDetailedInstrumentation();
        gpuSummaryUsesFrameMeasurementNotPhaseTotals();
    }

    private static void sessionHistoryLimitFollowsSummaryCadence() {
        assertEquals(120, ProfilerManager.computeSessionPointLimit(60, 500), "60 seconds retains 120 half-second summaries");
        assertEquals(1200, ProfilerManager.computeSessionPointLimit(60, 50), "fast cadence preserves previous capacity");
        assertEquals(30, ProfilerManager.computeSessionPointLimit(60, 2000), "slow cadence must not expand the time window");
        assertEquals(17, ProfilerManager.computeSessionPointLimit(5, 300), "fractional point counts round up");
    }

    private static void summaryCadenceDoesNotTreatThrottlingAsDroppedSamples() {
        for (int delay : new int[] {50, 500, 2000}) {
            assertFalse(ProfilerManager.shouldPublishSnapshot(false, 10_000L + delay - 1L, 10_000L, delay), "summary must wait for configured cadence");
            assertTrue(ProfilerManager.shouldPublishSnapshot(false, 10_000L + delay, 10_000L, delay), "summary is due at the boundary");
            assertEquals(0, ProfilerManager.computeMissedSamples(10_000L, 10_000L + delay, delay), "intentional throttling is not sample loss");
            assertEquals(1, ProfilerManager.computeMissedSamples(10_000L, 10_000L + delay * 2L, delay), "real missed summary interval is recorded");
        }
    }

    private static void jvmSamplingSkipsWorkInsideCadence() {
        MemoryProfiler memory = MemoryProfiler.getInstance();
        try {
            var field = MemoryProfiler.class.getDeclaredField("lastJvmSampleAtMillis");
            field.setAccessible(true);
            var timestamp = (java.util.concurrent.atomic.AtomicLong) field.get(memory);
            long original = timestamp.getAndSet(Long.MAX_VALUE);
            try {
                var snapshot = memory.getDetailedSnapshot();
                memory.sampleJvm();
                assertTrue(snapshot == memory.getDetailedSnapshot(), "skipped sample must not rebuild JVM metrics");
                assertEquals(Long.MAX_VALUE, timestamp.get(), "skipped sample must not update its timestamp");
            } finally {
                timestamp.set(original);
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("reflection failure", e);
        }
    }

    private static void snapshotPublishingHonorsForceAndDelay() {
        assertTrue(ProfilerManager.shouldPublishSnapshot(true, 120L, 100L, 50), "force should always publish");
        assertTrue(ProfilerManager.shouldPublishSnapshot(false, 0L, 0L, 50), "first publish should be allowed");
        assertFalse(ProfilerManager.shouldPublishSnapshot(false, 120L, 100L, 50), "publish should be throttled inside the delay window");
        assertTrue(ProfilerManager.shouldPublishSnapshot(false, 151L, 100L, 50), "publish should resume once the delay elapses");
    }

    private static void missedSampleCountDetectsDroppedIntervals() {
        assertEquals(0, ProfilerManager.computeMissedSamples(1000L, 1050L, 50), "exact cadence should not report missed samples");
        assertEquals(1, ProfilerManager.computeMissedSamples(1000L, 1100L, 50), "one skipped interval should report one missed sample");
        assertEquals(3, ProfilerManager.computeMissedSamples(1000L, 1200L, 50), "larger gaps should report multiple missed samples");
    }

    private static void observedSampleIntervalUsesAverageGap() {
        long observed = ProfilerManager.computeObservedSampleIntervalMs(new long[] {1000L, 1050L, 1100L, 1200L}, 50);
        assertEquals(66L, observed, "observed interval should average the captured sample gaps");
        assertEquals(50L, ProfilerManager.computeObservedSampleIntervalMs(new long[] {1000L}, 50), "fallback interval should be used for tiny samples");
    }

    private static void sessionLoggingKeepsCaptureActiveInManualDeep() {
        assertTrue(ProfilerManager.computeCaptureActive(ProfilerManager.CaptureMode.MANUAL_DEEP, false, true),
                "manual deep recording should stay active while a session is being recorded");
        assertFalse(ProfilerManager.computeCaptureActive(ProfilerManager.CaptureMode.MANUAL_DEEP, false, false),
                "manual deep without screen or session should stay inactive");
    }

    private static void passiveModeSkipsDetailedInstrumentation() {
        assertFalse(ProfilerManager.computeDetailedMetrics(ProfilerManager.CaptureMode.PASSIVE_LIGHTWEIGHT, false, false, false),
                "passive lightweight mode should avoid detailed instrumentation");
        assertTrue(ProfilerManager.computeDetailedMetrics(ProfilerManager.CaptureMode.SPIKE_CAPTURE, false, false, false),
                "spike capture should retain detailed instrumentation");
        assertTrue(ProfilerManager.computeDetailedMetrics(ProfilerManager.CaptureMode.OPEN_ONLY, true, false, false),
                "the open profiler screen should retain detailed instrumentation");
    }

    private static void gpuSummaryUsesFrameMeasurementNotPhaseTotals() {
        RenderPhaseProfiler render = RenderPhaseProfiler.getInstance();
        render.reset();
        try {
            render.recordGpuResult("world", 332_190_848L);
            render.recordGpuResult("layer", 199_899_104L);
            java.util.Map<String, Object> summary = new java.util.HashMap<>();
            render.recordGpuFrameResult(11_182_560L);
            ProfilerManager.getInstance().addGpuFrameSummary(summary);
            assertDoubleEquals(11.18256, (double) summary.get("gpuFrameTimeMs"), "summary uses a single GPU frame, not accumulated phases");
            assertDoubleEquals(11.18256, (double) summary.get("gpuFrameTimeP95Ms"), "single-sample p95 uses the same frame metric");
            assertTrue(String.valueOf(summary.get("gpuFrameTimeKind")).contains("not GPU busy time"), "GPU frame metric is labelled as a timestamp span");
            java.util.Map<String, Object> windows = ProfilerManager.getInstance().buildCaptureWindows();
            assertTrue(windows.containsKey("frameTimelineSpanSeconds") && windows.containsKey("profilerSelfCostAvgMs"), "exports state capture window and profiler overhead");
            assertTrue(String.valueOf(windows.get("shaderPassCoverage")).contains("excludes geometry"), "exports state shader-pass coverage");
            render.recordGpuFrameResult(532_089_952L);
            ProfilerManager.getInstance().addGpuFrameSummary(summary);
            assertDoubleEquals(532.089952, (double) summary.get("gpuFrameTimeMs"), "genuine slow frames are preserved");
            assertDoubleEquals(532.089952, (double) summary.get("gpuFrameTimeP95Ms"), "single-sample p95 preserves a slow frame");
        } finally {
            render.reset();
        }
    }

    private static void assertDoubleEquals(double expected, double actual, String message) {
        if (Math.abs(expected - actual) > 0.0000001) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean value, String message) {
        if (value) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
