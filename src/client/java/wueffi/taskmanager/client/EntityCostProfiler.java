package wueffi.taskmanager.client;

import net.minecraft.entity.Entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class EntityCostProfiler {

    private static final EntityCostProfiler INSTANCE = new EntityCostProfiler();
    private static final int MAX_ROWS = 8;

    private final Map<String, LongAdder> tickNanosByType = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> tickCallsByType = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> renderPrepNanosByType = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> renderPrepCallsByType = new ConcurrentHashMap<>();
    private final ThreadLocal<Long> tickStartNanos = new ThreadLocal<>();
    private final ThreadLocal<String> tickType = new ThreadLocal<>();
    private final ThreadLocal<Long> renderPrepStartNanos = new ThreadLocal<>();
    private final ThreadLocal<String> renderPrepType = new ThreadLocal<>();
    private final AtomicLong lastUpdatedAtMillis = new AtomicLong(0L);

    public static EntityCostProfiler getInstance() {
        return INSTANCE;
    }

    private EntityCostProfiler() {
    }

    public void beginEntityTick(Entity entity) {
        tickStartNanos.set(System.nanoTime());
        tickType.set(describeEntity(entity));
    }

    public void endEntityTick() {
        Long startedAt = tickStartNanos.get();
        String entityType = tickType.get();
        tickStartNanos.remove();
        tickType.remove();
        if (startedAt == null || entityType == null || entityType.isBlank()) {
            return;
        }
        long durationNs = Math.max(0L, System.nanoTime() - startedAt);
        tickNanosByType.computeIfAbsent(entityType, ignored -> new LongAdder()).add(durationNs);
        tickCallsByType.computeIfAbsent(entityType, ignored -> new LongAdder()).increment();
        lastUpdatedAtMillis.set(System.currentTimeMillis());
    }

    public void beginEntityRenderPrep(Entity entity) {
        renderPrepStartNanos.set(System.nanoTime());
        renderPrepType.set(describeEntity(entity));
    }

    public void endEntityRenderPrep() {
        Long startedAt = renderPrepStartNanos.get();
        String entityType = renderPrepType.get();
        renderPrepStartNanos.remove();
        renderPrepType.remove();
        if (startedAt == null || entityType == null || entityType.isBlank()) {
            return;
        }
        long durationNs = Math.max(0L, System.nanoTime() - startedAt);
        renderPrepNanosByType.computeIfAbsent(entityType, ignored -> new LongAdder()).add(durationNs);
        renderPrepCallsByType.computeIfAbsent(entityType, ignored -> new LongAdder()).increment();
        lastUpdatedAtMillis.set(System.currentTimeMillis());
    }

    public Snapshot getSnapshot() {
        return new Snapshot(
                topEntries(tickNanosByType),
                topEntries(tickCallsByType),
                topEntries(renderPrepNanosByType),
                topEntries(renderPrepCallsByType),
                getLastSampleAgeMillis()
        );
    }

    public void reset() {
        tickNanosByType.clear();
        tickCallsByType.clear();
        renderPrepNanosByType.clear();
        renderPrepCallsByType.clear();
        lastUpdatedAtMillis.set(0L);
        tickStartNanos.remove();
        tickType.remove();
        renderPrepStartNanos.remove();
        renderPrepType.remove();
    }

    public List<String> buildTopTickLines() {
        Snapshot snapshot = getSnapshot();
        return buildLines(snapshot.tickNanosByType(), snapshot.tickCallsByType(), "tick");
    }

    public List<String> buildTopRenderPrepLines() {
        Snapshot snapshot = getSnapshot();
        return buildLines(snapshot.renderPrepNanosByType(), snapshot.renderPrepCallsByType(), "render prep");
    }

    private List<String> buildLines(Map<String, Long> nanosByType, Map<String, Long> callsByType, String label) {
        List<String> lines = new ArrayList<>();
        nanosByType.forEach((type, nanos) -> lines.add(String.format(
                java.util.Locale.ROOT,
                "%s | %s %.2f ms | %d calls",
                type,
                label,
                nanos / 1_000_000.0,
                callsByType.getOrDefault(type, 0L)
        )));
        return lines;
    }

    private long getLastSampleAgeMillis() {
        long updatedAt = lastUpdatedAtMillis.get();
        if (updatedAt == 0L) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, System.currentTimeMillis() - updatedAt);
    }

    private static Map<String, Long> topEntries(Map<String, LongAdder> source) {
        LinkedHashMap<String, Long> snapshot = new LinkedHashMap<>();
        source.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), entry.getValue().sum()))
                .filter(entry -> entry.getValue() > 0L)
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(MAX_ROWS)
                .forEach(entry -> snapshot.put(entry.getKey(), entry.getValue()));
        return snapshot;
    }

    private static String describeEntity(Entity entity) {
        if (entity == null) {
            return "unknown";
        }
        String simpleName = entity.getClass().getSimpleName();
        if (simpleName != null && !simpleName.isBlank()) {
            return simpleName;
        }
        return entity.getType().toString();
    }

    public record Snapshot(
            Map<String, Long> tickNanosByType,
            Map<String, Long> tickCallsByType,
            Map<String, Long> renderPrepNanosByType,
            Map<String, Long> renderPrepCallsByType,
            long sampleAgeMillis
    ) {
    }
}
