package wueffi.taskmanager.client;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.protocol.Packet;

public class NetworkPacketProfiler {

    public record Snapshot(
            long inboundPackets,
            long outboundPackets,
            Map<String, Long> inboundByCategory,
            Map<String, Long> outboundByCategory,
            Map<String, Long> inboundByType,
            Map<String, Long> outboundByType
    ) {}

    public record SpikeSnapshot(
            long capturedAtEpochMillis,
            Map<String, Long> inboundByCategory,
            Map<String, Long> outboundByCategory,
            Map<String, Long> inboundByType,
            Map<String, Long> outboundByType
    ) {}

    private record ClassifiedPacket(String category, String packetType) {}

    private static final NetworkPacketProfiler INSTANCE = new NetworkPacketProfiler();
    private static final int WINDOW_SIZE = 120;
    private static final int MAX_SPIKES = 12;

    public static NetworkPacketProfiler getInstance() {
        return INSTANCE;
    }

    private final Object lock = new Object();
    private final Deque<Snapshot> history = new ArrayDeque<>();
    private final Deque<SpikeSnapshot> spikeHistory = new ArrayDeque<>();
    private volatile Snapshot latestSnapshot = new Snapshot(0L, 0L, Map.of(), Map.of(), Map.of(), Map.of());
    private long inboundPackets;
    private long outboundPackets;
    private final Map<String, Long> inboundByCategory = new LinkedHashMap<>();
    private final Map<String, Long> outboundByCategory = new LinkedHashMap<>();
    private final Map<String, Long> inboundByType = new LinkedHashMap<>();
    private final Map<String, Long> outboundByType = new LinkedHashMap<>();

    public void recordInbound(Packet<?> packet) {
        synchronized (lock) {
            inboundPackets++;
            ClassifiedPacket classified = classify(packet);
            inboundByCategory.merge(classified.category(), 1L, Long::sum);
            inboundByType.merge(classified.packetType(), 1L, Long::sum);
        }
    }

    public void recordOutbound(Packet<?> packet) {
        synchronized (lock) {
            outboundPackets++;
            ClassifiedPacket classified = classify(packet);
            outboundByCategory.merge(classified.category(), 1L, Long::sum);
            outboundByType.merge(classified.packetType(), 1L, Long::sum);
        }
    }

    public Snapshot drainWindow() {
        synchronized (lock) {
            Snapshot snapshot = new Snapshot(
                    inboundPackets,
                    outboundPackets,
                    topEntries(inboundByCategory, 8),
                    topEntries(outboundByCategory, 8),
                    topEntries(inboundByType, 12),
                    topEntries(outboundByType, 12)
            );
            latestSnapshot = snapshot;
            history.addLast(snapshot);
            while (history.size() > WINDOW_SIZE) {
                history.removeFirst();
            }
            inboundPackets = 0L;
            outboundPackets = 0L;
            inboundByCategory.clear();
            outboundByCategory.clear();
            inboundByType.clear();
            outboundByType.clear();
            return snapshot;
        }
    }

    public Snapshot getLatestSnapshot() {
        return latestSnapshot;
    }

    public List<Snapshot> getHistory() {
        synchronized (lock) {
            return List.copyOf(history);
        }
    }

    public List<SpikeSnapshot> getSpikeHistory() {
        synchronized (lock) {
            return List.copyOf(spikeHistory);
        }
    }

    public void captureSpikeBookmark() {
        synchronized (lock) {
            if (history.isEmpty()) {
                return;
            }
            Snapshot latest = history.peekLast();
            spikeHistory.addFirst(new SpikeSnapshot(
                    System.currentTimeMillis(),
                    latest.inboundByCategory(),
                    latest.outboundByCategory(),
                    latest.inboundByType(),
                    latest.outboundByType()
            ));
            while (spikeHistory.size() > MAX_SPIKES) {
                spikeHistory.removeLast();
            }
        }
    }

    public void reset() {
        synchronized (lock) {
            history.clear();
            spikeHistory.clear();
            latestSnapshot = new Snapshot(0L, 0L, Map.of(), Map.of(), Map.of(), Map.of());
            inboundPackets = 0L;
            outboundPackets = 0L;
            inboundByCategory.clear();
            outboundByCategory.clear();
            inboundByType.clear();
            outboundByType.clear();
        }
    }

    private Map<String, Long> topEntries(Map<String, Long> source, int limit) {
        Map<String, Long> result = new LinkedHashMap<>();
        source.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private ClassifiedPacket classify(Packet<?> packet) {
        if (packet == null) {
            return new ClassifiedPacket("unknown", "unknown");
        }
        String className = packet.getClass().getName();
        String simple = packet.getClass().getSimpleName();
        String lower = className.toLowerCase();
        String category;
        if (lower.contains("chunk")) category = "chunks";
        else if (lower.contains("entity")) category = "entities";
        else if (lower.contains("blockentity") || lower.contains("block_entity")) category = "block-entities";
        else if (lower.contains("particle")) category = "particles";
        else if (lower.contains("sound")) category = "audio";
        else if (lower.contains("command")) category = "commands";
        else if (lower.contains("screen") || lower.contains("inventory") || lower.contains("slot")) category = "ui";
        else if (lower.contains("custompayload") || lower.contains("payload")) category = "custom";
        else {
            int idx = className.lastIndexOf('.');
            category = idx >= 0 ? className.substring(Math.max(0, className.lastIndexOf('.', idx - 1) + 1), idx) : "misc";
        }
        int idx = className.lastIndexOf('.');
        String namespace = idx >= 0 ? className.substring(Math.max(0, className.lastIndexOf('.', idx - 1) + 1), idx) : "misc";
        return new ClassifiedPacket(category, category + ":" + namespace + ":" + simple);
    }
}
