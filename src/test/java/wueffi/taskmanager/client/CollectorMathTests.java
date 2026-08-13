package wueffi.taskmanager.client;

public final class CollectorMathTests {

    private CollectorMathTests() {
    }

    public static void run() {
        splitBudgetDistributesRemainderPredictably();
        adaptiveWorldScanCadenceStaysFastWhenCheap();
        adaptiveWorldScanCadenceBacksOffAfterExpensiveScans();
    }

    private static void splitBudgetDistributesRemainderPredictably() {
        long[] shares = CollectorMath.splitBudget(10L, 3);
        assertEquals(3, shares.length, "share count");
        assertEquals(4L, shares[0], "first share");
        assertEquals(3L, shares[1], "second share");
        assertEquals(3L, shares[2], "third share");
    }

    private static void adaptiveWorldScanCadenceStaysFastWhenCheap() {
        assertEquals(500L, CollectorMath.computeAdaptiveWorldScanCadenceMillis(true, false, false, 0L), "detailed cadence baseline");
        assertEquals(1_000L, CollectorMath.computeAdaptiveWorldScanCadenceMillis(false, false, false, 0L), "light cadence baseline");
    }

    private static void adaptiveWorldScanCadenceBacksOffAfterExpensiveScans() {
        assertEquals(1_000L, CollectorMath.computeAdaptiveWorldScanCadenceMillis(true, false, false, 5L), "detailed cadence should widen after a 5ms scan");
        assertEquals(5_000L, CollectorMath.computeAdaptiveWorldScanCadenceMillis(false, false, false, 100L), "light cadence should cap the backoff");
        assertEquals(2_000L, CollectorMath.computeAdaptiveWorldScanCadenceMillis(false, false, true, 0L), "self-protection should widen the baseline cadence");
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
