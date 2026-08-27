package wueffi.taskmanager.client;

import java.util.Arrays;

public class TickProfiler {

    private static final TickProfiler INSTANCE = new TickProfiler();
    public static TickProfiler getInstance() { return INSTANCE; }

    private static final int WINDOW_SIZE = 200;
    private static final int MAX_TRIMMED_OUTLIERS = 4;

    private final long[] clientTickDurations = new long[WINDOW_SIZE];
    private final long[] serverTickDurations = new long[WINDOW_SIZE];
    private int clientIndex = 0;
    private int clientCount = 0;
    private int serverIndex = 0;
    private int serverCount = 0;

    private volatile long clientStart;
    private volatile long serverStart;

    public void beginTick() {
        clientStart = System.nanoTime();
    }

    public void endTick() {
        record(clientTickDurations, true, System.nanoTime() - clientStart);
    }

    public void beginServerTick() {
        serverStart = System.nanoTime();
    }

    public void endServerTick() {
        record(serverTickDurations, false, System.nanoTime() - serverStart);
    }

    public long getAverageTickNs() {
        return getAverageClientTickNs();
    }

    public long getAverageClientTickNs() {
        return robustAverage(clientTickDurations, clientCount);
    }

    public long getAverageServerTickNs() {
        return robustAverage(serverTickDurations, serverCount);
    }

    public long getMaxTickNs() {
        return getMaxClientTickNs();
    }

    public long getMaxClientTickNs() {
        return max(clientTickDurations, clientCount);
    }

    public long getMaxServerTickNs() {
        return max(serverTickDurations, serverCount);
    }

    public long getClientTickP95Ns() {
        return percentile(clientTickDurations, clientCount, 0.95);
    }

    public long getClientTickP99Ns() {
        return percentile(clientTickDurations, clientCount, 0.99);
    }

    public long getServerTickP95Ns() {
        return percentile(serverTickDurations, serverCount, 0.95);
    }

    public long getServerTickP99Ns() {
        return percentile(serverTickDurations, serverCount, 0.99);
    }

    public long getClientTicks() {
        return clientCount;
    }

    public long getServerTicks() {
        return serverCount;
    }

    public void reset() {
        clientIndex = 0;
        clientCount = 0;
        serverIndex = 0;
        serverCount = 0;
        Arrays.fill(clientTickDurations, 0L);
        Arrays.fill(serverTickDurations, 0L);
    }

    private void record(long[] buffer, boolean client, long duration) {
        if (client) {
            clientTickDurations[clientIndex] = duration;
            clientIndex = (clientIndex + 1) % WINDOW_SIZE;
            if (clientCount < WINDOW_SIZE) {
                clientCount++;
            }
            return;
        }

        serverTickDurations[serverIndex] = duration;
        serverIndex = (serverIndex + 1) % WINDOW_SIZE;
        if (serverCount < WINDOW_SIZE) {
            serverCount++;
        }
    }

    private long robustAverage(long[] buffer, int count) {
        if (count == 0) return 0;
        if (count < 12) return average(buffer, count);

        int trimCount = Math.min(MAX_TRIMMED_OUTLIERS, Math.max(1, count / 20));
        long total = 0L;
        long[] largest = new long[trimCount];

        for (int i = 0; i < count; i++) {
            long value = buffer[i];
            total += value;
            insertLargest(largest, value);
        }

        long trimmedTotal = total;
        for (long value : largest) {
            trimmedTotal -= value;
        }
        return trimmedTotal / Math.max(1, count - trimCount);
    }

    private void insertLargest(long[] largest, long value) {
        for (int i = 0; i < largest.length; i++) {
            if (value > largest[i]) {
                long previous = largest[i];
                largest[i] = value;
                value = previous;
            }
        }
    }

    private long average(long[] buffer, int count) {
        if (count == 0) return 0;
        long total = 0;
        for (int i = 0; i < count; i++) {
            total += buffer[i];
        }
        return total / count;
    }

    private long max(long[] buffer, int count) {
        long max = 0;
        for (int i = 0; i < count; i++) {
            if (buffer[i] > max) {
                max = buffer[i];
            }
        }
        return max;
    }

    private long percentile(long[] buffer, int count, double percentile) {
        if (count == 0) return 0L;
        long[] copy = Arrays.copyOf(buffer, count);
        Arrays.sort(copy);
        int idx = Math.min(copy.length - 1, Math.max(0, (int) Math.ceil(percentile * copy.length) - 1));
        return copy[idx];
    }
}
