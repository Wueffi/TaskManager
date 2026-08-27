package wueffi.taskmanager.client;

import net.fabricmc.loader.api.FabricLoader;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public class RenderPhaseProfiler {

    private static final RenderPhaseProfiler INSTANCE = new RenderPhaseProfiler();
    public static RenderPhaseProfiler getInstance() { return INSTANCE; }

    private static class Counter {
        final LongAdder cpuNanos = new LongAdder();
        final LongAdder cpuCalls = new LongAdder();
        final LongAdder gpuNanos = new LongAdder();
        final LongAdder gpuCalls = new LongAdder();
        final Map<String, LongAdder> likelyOwners = new ConcurrentHashMap<>();
        final Map<String, LongAdder> likelyFrames = new ConcurrentHashMap<>();
        volatile String ownerMod;
    }

    public record PhaseSnapshot(long cpuNanos, long cpuCalls, long gpuNanos, long gpuCalls, String ownerMod, Map<String, Long> likelyOwners, Map<String, Long> likelyFrames) {
        public PhaseSnapshot(long cpuNanos, long cpuCalls, long gpuNanos, long gpuCalls, String ownerMod) {
            this(cpuNanos, cpuCalls, gpuNanos, gpuCalls, ownerMod, Map.of(), Map.of());
        }
    }

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<Long, Deque<PhaseScope>> cpuScopesByThread = new ConcurrentHashMap<>();
    private final Map<Long, Deque<String>> contextOwnersByThread = new ConcurrentHashMap<>();
    private volatile Set<String> knownModIds;

    private record PhaseScope(String phase, long startedAtNanos) {}

    public void beginCpuPhase(String phase) {
        Counter counter = ensureCounter(phase);
        recordContextOwnerHint(counter);
        cpuScopesByThread.computeIfAbsent(Thread.currentThread().threadId(), ignored -> new ArrayDeque<>())
                .addLast(new PhaseScope(phase, System.nanoTime()));
    }

    public void beginCpuPhase(String phase, String ownerMod) {
        Counter counter = ensureCounter(phase, ownerMod);
        recordContextOwnerHint(counter);
        cpuScopesByThread.computeIfAbsent(Thread.currentThread().threadId(), ignored -> new ArrayDeque<>())
                .addLast(new PhaseScope(phase, System.nanoTime()));
    }

    public void endCpuPhase(String phase) {
        Deque<PhaseScope> scopes = cpuScopesByThread.get(Thread.currentThread().threadId());
        if (scopes == null || scopes.isEmpty()) {
            return;
        }

        PhaseScope scope = null;
        if (phase.equals(scopes.peekLast().phase())) {
            scope = scopes.removeLast();
        } else {
            Deque<PhaseScope> skipped = new ArrayDeque<>();
            while (!scopes.isEmpty()) {
                PhaseScope candidate = scopes.removeLast();
                if (phase.equals(candidate.phase())) {
                    scope = candidate;
                    break;
                }
                skipped.addFirst(candidate);
            }
            scopes.addAll(skipped);
        }
        if (scope == null) {
            return;
        }
        if (scopes.isEmpty()) {
            cpuScopesByThread.remove(Thread.currentThread().threadId());
        }

        long duration = System.nanoTime() - scope.startedAtNanos();
        Counter counter = ensureCounter(phase);
        counter.cpuNanos.add(duration);
        counter.cpuCalls.increment();
    }

    public void recordGpuResult(String phase, long nanoseconds) {
        Counter counter = ensureCounter(phase);
        counter.gpuNanos.add(nanoseconds);
        counter.gpuCalls.increment();
    }

    public void recordLikelyOwnerSample(long threadId, String ownerMod, String frameReason) {
        if (ownerMod == null || ownerMod.isBlank()) {
            return;
        }
        Deque<PhaseScope> scopes = cpuScopesByThread.get(threadId);
        if (scopes == null || scopes.isEmpty()) {
            return;
        }
        LinkedHashSet<String> activePhases = new LinkedHashSet<>();
        for (PhaseScope scope : scopes) {
            activePhases.add(scope.phase());
        }
        for (String phase : activePhases) {
            Counter counter = ensureCounter(phase);
            counter.likelyOwners.computeIfAbsent(ownerMod, ignored -> new LongAdder()).increment();
            if (frameReason != null && !frameReason.isBlank()) {
                counter.likelyFrames.computeIfAbsent(frameReason, ignored -> new LongAdder()).increment();
            }
        }
    }

    public void registerPhaseOwner(String phase, String ownerMod) {
        ensureCounter(phase, ownerMod);
    }

    public void pushContextOwner(String ownerMod) {
        String normalizedOwner = normalizeContextOwner(ownerMod);
        if (normalizedOwner == null) {
            return;
        }
        contextOwnersByThread.computeIfAbsent(Thread.currentThread().threadId(), ignored -> new ArrayDeque<>())
                .addLast(normalizedOwner);
    }

    public void popContextOwner() {
        long threadId = Thread.currentThread().threadId();
        Deque<String> owners = contextOwnersByThread.get(threadId);
        if (owners == null || owners.isEmpty()) {
            return;
        }
        owners.removeLast();
        if (owners.isEmpty()) {
            contextOwnersByThread.remove(threadId);
        }
    }

    public Map<String, PhaseSnapshot> getSnapshot() {
        Map<String, PhaseSnapshot> result = new LinkedHashMap<>();
        counters.forEach((phase, counter) -> result.put(phase, new PhaseSnapshot(
                counter.cpuNanos.sum(),
                counter.cpuCalls.sum(),
                counter.gpuNanos.sum(),
                counter.gpuCalls.sum(),
                counter.ownerMod,
                snapshotReasonMap(counter.likelyOwners),
                snapshotReasonMap(counter.likelyFrames)
        )));
        return result;
    }

    public Map<String, PhaseSnapshot> drainSnapshot() {
        Map<String, PhaseSnapshot> result = getSnapshot();
        reset();
        return result;
    }

    public Map<String, Long> getCpuNanos() {
        Map<String, Long> result = new LinkedHashMap<>();
        getSnapshot().forEach((phase, snapshot) -> result.put(phase, snapshot.cpuNanos()));
        return result;
    }

    public Map<String, Long> getCpuCalls() {
        Map<String, Long> result = new LinkedHashMap<>();
        getSnapshot().forEach((phase, snapshot) -> result.put(phase, snapshot.cpuCalls()));
        return result;
    }

    public Map<String, Long> getGpuNanos() {
        Map<String, Long> result = new LinkedHashMap<>();
        getSnapshot().forEach((phase, snapshot) -> result.put(phase, snapshot.gpuNanos()));
        return result;
    }

    public Map<String, Long> getGpuCalls() {
        Map<String, Long> result = new LinkedHashMap<>();
        getSnapshot().forEach((phase, snapshot) -> result.put(phase, snapshot.gpuCalls()));
        return result;
    }

    public void reset() {
        counters.clear();
        cpuScopesByThread.clear();
        contextOwnersByThread.clear();
    }

    private Counter ensureCounter(String phase) {
        return ensureCounter(phase, resolveOwnerMod(phase));
    }

    private Counter ensureCounter(String phase, String ownerMod) {
        Counter counter = counters.computeIfAbsent(phase, ignored -> new Counter());
        if (counter.ownerMod == null || counter.ownerMod.isBlank() || counter.ownerMod.startsWith("shared/")) {
            counter.ownerMod = normalizeOwnerMod(ownerMod);
        }
        return counter;
    }

    private void recordContextOwnerHint(Counter counter) {
        String contextOwner = getCurrentContextOwner();
        if (contextOwner == null) {
            return;
        }
        counter.likelyOwners.computeIfAbsent(contextOwner, ignored -> new LongAdder()).increment();
    }

    private String getCurrentContextOwner() {
        Deque<String> owners = contextOwnersByThread.get(Thread.currentThread().threadId());
        if (owners == null || owners.isEmpty()) {
            return null;
        }
        return normalizeContextOwner(owners.peekLast());
    }

    private String normalizeContextOwner(String ownerMod) {
        if (ownerMod == null || ownerMod.isBlank() || ownerMod.startsWith("shared/") || ownerMod.startsWith("runtime/")) {
            return null;
        }
        return ownerMod;
    }

    private String normalizeOwnerMod(String ownerMod) {
        if (ownerMod == null || ownerMod.isBlank()) {
            return "shared/render";
        }
        return ownerMod;
    }

    private String resolveOwnerMod(String phase) {
        if (phase == null || phase.isBlank()) {
            return "shared/render";
        }
        String normalized = phase.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("minecraft.") || normalized.startsWith("minecraft:")) {
            return "minecraft";
        }
        if (normalized.startsWith("frame.")) {
            return "minecraft";
        }
        int separator = normalized.indexOf(':');
        if (separator > 0) {
            String namespace = normalized.substring(0, separator);
            if (getKnownModIds().contains(namespace)) {
                return namespace;
            }
        }
        String tokenized = normalized.replace('/', '.').replace('\\', '.').replace('$', '.').replace(':', '.').replace('-', '_');
        for (String token : tokenized.split("[^a-z0-9_]+")) {
            if (!token.isBlank() && getKnownModIds().contains(token)) {
                return token;
            }
        }
        for (String modId : getKnownModIds()) {
            String candidate = modId.replace('-', '_');
            if (tokenized.contains(candidate + ".") || tokenized.endsWith(candidate)) {
                return modId;
            }
        }
        return "shared/render";
    }

    private Set<String> getKnownModIds() {
        Set<String> cached = knownModIds;
        if (cached != null) {
            return cached;
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.add("minecraft");
        FabricLoader.getInstance().getAllMods().forEach(mod -> ids.add(mod.getMetadata().getId().toLowerCase(Locale.ROOT)));
        knownModIds = ids;
        return ids;
    }

    private Map<String, Long> snapshotReasonMap(Map<String, LongAdder> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> result = new LinkedHashMap<>();
        source.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
                .limit(4)
                .forEach(entry -> result.put(entry.getKey(), entry.getValue().sum()));
        return result;
    }
}
