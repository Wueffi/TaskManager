package wueffi.taskmanager.client;

public final class ProfilerManagerTests {

    private ProfilerManagerTests() {
    }

    public static void run() {
        snapshotPublishingHonorsForceAndDelay();
        missedSampleCountDetectsDroppedIntervals();
        observedSampleIntervalUsesAverageGap();
        sessionLoggingKeepsCaptureActiveInManualDeep();
        passiveModeSkipsDetailedInstrumentation();
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
