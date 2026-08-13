package wueffi.taskmanager.client;

final class CollectorMath {

    private CollectorMath() {
    }

    static long[] splitBudget(long totalBudget, int parts) {
        if (totalBudget <= 0L || parts <= 0) {
            return new long[0];
        }
        long[] shares = new long[parts];
        long baseShare = totalBudget / parts;
        long remainder = totalBudget % parts;
        for (int i = 0; i < parts; i++) {
            shares[i] = baseShare + (i < remainder ? 1L : 0L);
        }
        return shares;
    }

    static long computeAdaptiveWorldScanCadenceMillis(boolean detailedMetrics, boolean sessionLogging, boolean selfProtecting, long lastScanDurationMillis) {
        long baseCadenceMillis = selfProtecting ? 2_000L : (detailedMetrics || sessionLogging ? 500L : 1_000L);
        return baseCadenceMillis + Math.min(4_000L, Math.max(0L, lastScanDurationMillis) * 100L);
    }

    static long computeAdaptiveMemoryCadenceMillis(String governorMode, boolean screenOpen, boolean sessionLogging) {
        return switch (governorMode == null ? "normal" : governorMode) {
            case "self-protect" -> screenOpen ? 6_000L : 15_000L;
            case "burst" -> screenOpen || sessionLogging ? 1_500L : 3_000L;
            case "tight" -> screenOpen || sessionLogging ? 2_500L : 4_500L;
            case "light" -> screenOpen ? 6_000L : 12_000L;
            default -> screenOpen ? 2_000L : (sessionLogging ? 5_000L : 8_000L);
        };
    }
}
