package wueffi.taskmanager.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import wueffi.taskmanager.client.util.ModTimingSnapshot;

public final class AttributionModelBuilder {

    public record EffectiveCpuAttribution(
            Map<String, CpuSamplingProfiler.Snapshot> displaySnapshots,
            Map<String, Long> redistributedSamplesByMod,
            Map<String, Long> redistributedRenderSamplesByMod,
            long totalSamples,
            long totalRenderSamples
    ) {}

    public record EffectiveMemoryAttribution(
            Map<String, Long> displayBytes,
            Map<String, Long> redistributedBytesByMod,
            long totalBytes
    ) {}

    public record EffectiveGpuAttribution(
            Map<String, Long> gpuNanosByMod,
            Map<String, Long> renderSamplesByMod,
            Map<String, Long> redistributedGpuNanosByMod,
            Map<String, Long> redistributedRenderSamplesByMod,
            long totalGpuNanos,
            long totalRenderSamples
    ) {}

    private AttributionModelBuilder() {
    }

    public static EffectiveCpuAttribution buildEffectiveCpuAttribution(
            Map<String, CpuSamplingProfiler.Snapshot> rawCpu,
            Map<String, CpuSamplingProfiler.DetailSnapshot> cpuDetails,
            Map<String, ModTimingSnapshot> invokes
    ) {
        LinkedHashMap<String, CpuSamplingProfiler.Snapshot> concrete = new LinkedHashMap<>();
        LinkedHashMap<String, CpuSamplingProfiler.Snapshot> carriedShared = new LinkedHashMap<>();
        long sharedTotalSamples = 0L;
        long sharedClientSamples = 0L;
        long sharedRenderSamples = 0L;
        long sharedTotalCpuNanos = 0L;
        long sharedClientCpuNanos = 0L;
        long sharedRenderCpuNanos = 0L;
        long rawTotalSamples = 0L;
        long rawTotalRenderSamples = 0L;
        for (Map.Entry<String, CpuSamplingProfiler.Snapshot> entry : rawCpu.entrySet()) {
            String modId = entry.getKey();
            CpuSamplingProfiler.Snapshot sample = entry.getValue();
            rawTotalSamples += sample.totalSamples();
            rawTotalRenderSamples += sample.renderSamples();
            if ("shared/gpu-stall".equals(modId)) {
                carriedShared.put(modId, sample);
                continue;
            }
            if (isSharedAttributionBucket(modId)) {
                sharedTotalSamples += sample.totalSamples();
                sharedClientSamples += sample.clientSamples();
                sharedRenderSamples += sample.renderSamples();
                sharedTotalCpuNanos += sample.totalCpuNanos();
                sharedClientCpuNanos += sample.clientCpuNanos();
                sharedRenderCpuNanos += sample.renderCpuNanos();
            } else {
                concrete.put(modId, sample);
            }
        }
        if (concrete.isEmpty()) {
            return new EffectiveCpuAttribution(new LinkedHashMap<>(rawCpu), Map.of(), Map.of(), rawTotalSamples, rawTotalRenderSamples);
        }

        Map<String, Double> totalWeights = buildCpuWeightMap(concrete, cpuDetails, invokes, CpuSamplingProfiler.Snapshot::totalSamples);
        Map<String, Double> clientWeights = buildCpuWeightMap(concrete, cpuDetails, invokes, CpuSamplingProfiler.Snapshot::clientSamples);
        Map<String, Double> renderWeights = buildCpuWeightMap(concrete, cpuDetails, invokes, CpuSamplingProfiler.Snapshot::renderSamples);
        Map<String, Double> totalCpuWeights = buildCpuWeightMap(concrete, cpuDetails, invokes, CpuSamplingProfiler.Snapshot::totalCpuNanos);
        Map<String, Double> clientCpuWeights = buildCpuWeightMap(concrete, cpuDetails, invokes, CpuSamplingProfiler.Snapshot::clientCpuNanos);
        Map<String, Double> renderCpuWeights = buildCpuWeightMap(concrete, cpuDetails, invokes, CpuSamplingProfiler.Snapshot::renderCpuNanos);
        Map<String, Long> redistributedTotals = distributeLongProportionally(sharedTotalSamples, totalWeights);
        Map<String, Long> redistributedClients = distributeLongProportionally(sharedClientSamples, clientWeights);
        Map<String, Long> redistributedRenders = distributeLongProportionally(sharedRenderSamples, renderWeights);
        Map<String, Long> redistributedTotalCpuNanos = distributeLongProportionally(sharedTotalCpuNanos, totalCpuWeights);
        Map<String, Long> redistributedClientCpuNanos = distributeLongProportionally(sharedClientCpuNanos, clientCpuWeights);
        Map<String, Long> redistributedRenderCpuNanos = distributeLongProportionally(sharedRenderCpuNanos, renderCpuWeights);

        LinkedHashMap<String, CpuSamplingProfiler.Snapshot> display = new LinkedHashMap<>();
        for (Map.Entry<String, CpuSamplingProfiler.Snapshot> entry : concrete.entrySet()) {
            String modId = entry.getKey();
            CpuSamplingProfiler.Snapshot sample = entry.getValue();
            display.put(modId, new CpuSamplingProfiler.Snapshot(
                    sample.totalSamples() + redistributedTotals.getOrDefault(modId, 0L),
                    sample.clientSamples() + redistributedClients.getOrDefault(modId, 0L),
                    sample.renderSamples() + redistributedRenders.getOrDefault(modId, 0L),
                    sample.totalCpuNanos() + redistributedTotalCpuNanos.getOrDefault(modId, 0L),
                    sample.clientCpuNanos() + redistributedClientCpuNanos.getOrDefault(modId, 0L),
                    sample.renderCpuNanos() + redistributedRenderCpuNanos.getOrDefault(modId, 0L)
            ));
        }
        display.putAll(carriedShared);

        return new EffectiveCpuAttribution(display, redistributedTotals, redistributedRenders, rawTotalSamples, rawTotalRenderSamples);
    }

    public static EffectiveGpuAttribution buildEffectiveGpuAttribution(
            Map<String, RenderPhaseProfiler.PhaseSnapshot> renderPhases,
            Map<String, CpuSamplingProfiler.Snapshot> cpu,
            EffectiveCpuAttribution effectiveCpu,
            boolean effectiveView
    ) {
        Map<String, CpuSamplingProfiler.Snapshot> renderSource = effectiveView ? effectiveCpu.displaySnapshots() : cpu;
        LinkedHashMap<String, Long> renderSamplesByMod = new LinkedHashMap<>();
        long totalRenderSamples = 0L;
        renderSource.forEach((modId, sample) -> {
            if (sample.renderSamples() > 0L) {
                renderSamplesByMod.put(modId, sample.renderSamples());
            }
        });
        for (long renderSamples : renderSamplesByMod.values()) {
            totalRenderSamples += renderSamples;
        }

        LinkedHashMap<String, Long> directGpuByMod = new LinkedHashMap<>();
        long sharedGpuNanos = 0L;
        long directGpuTotal = 0L;
        for (RenderPhaseProfiler.PhaseSnapshot phase : renderPhases.values()) {
            if (phase.gpuNanos() <= 0L) {
                continue;
            }
            String ownerMod = effectiveView ? effectiveGpuPhaseOwner(phase) : phase.ownerMod();
            if (ownerMod == null || ownerMod.isBlank()) {
                ownerMod = "shared/render";
            }
            if (isSharedAttributionBucket(ownerMod)) {
                sharedGpuNanos += phase.gpuNanos();
            } else {
                directGpuByMod.merge(ownerMod, phase.gpuNanos(), Long::sum);
                directGpuTotal += phase.gpuNanos();
            }
        }

        if (!effectiveView) {
            long totalGpuNanos = directGpuTotal;
            if (sharedGpuNanos > 0L) {
                directGpuByMod.merge("shared/render", sharedGpuNanos, Long::sum);
                renderSamplesByMod.putIfAbsent("shared/render", 0L);
                totalGpuNanos += sharedGpuNanos;
            }
            return new EffectiveGpuAttribution(directGpuByMod, renderSamplesByMod, Map.of(), Map.of(), Math.max(1L, totalGpuNanos), Math.max(1L, totalRenderSamples));
        }

        LinkedHashMap<String, Long> effectiveGpuByMod = new LinkedHashMap<>();
        renderSamplesByMod.keySet().forEach(modId -> effectiveGpuByMod.put(modId, directGpuByMod.getOrDefault(modId, 0L)));
        directGpuByMod.forEach((modId, gpuNanos) -> effectiveGpuByMod.putIfAbsent(modId, gpuNanos));
        Map<String, Double> weights = buildGpuWeightMap(renderSamplesByMod, effectiveGpuByMod);
        if (weights.isEmpty()) {
            if (sharedGpuNanos > 0L) {
                effectiveGpuByMod.merge("shared/render", sharedGpuNanos, Long::sum);
                renderSamplesByMod.putIfAbsent("shared/render", 0L);
            }
            effectiveGpuByMod.entrySet().removeIf(entry -> entry.getValue() <= 0L && !"shared/render".equals(entry.getKey()));
            long totalGpuNanos = directGpuTotal + sharedGpuNanos;
            return new EffectiveGpuAttribution(effectiveGpuByMod, renderSamplesByMod, Map.of(), Map.of(), Math.max(1L, totalGpuNanos), Math.max(1L, totalRenderSamples));
        }
        Map<String, Long> redistributedGpu = distributeLongProportionally(sharedGpuNanos, weights);
        redistributedGpu.forEach((modId, gpuNanos) -> effectiveGpuByMod.merge(modId, gpuNanos, Long::sum));
        effectiveGpuByMod.entrySet().removeIf(entry -> entry.getValue() <= 0L && renderSamplesByMod.getOrDefault(entry.getKey(), 0L) <= 0L);
        long totalGpuNanos = directGpuTotal + sharedGpuNanos;
        return new EffectiveGpuAttribution(effectiveGpuByMod, renderSamplesByMod, redistributedGpu, effectiveCpu.redistributedRenderSamplesByMod(), Math.max(1L, totalGpuNanos), Math.max(1L, totalRenderSamples));
    }

    public static EffectiveMemoryAttribution buildEffectiveMemoryAttribution(Map<String, Long> rawMemoryMods) {
        LinkedHashMap<String, Long> concrete = new LinkedHashMap<>();
        long sharedBytes = 0L;
        long totalBytes = 0L;
        for (Map.Entry<String, Long> entry : rawMemoryMods.entrySet()) {
            totalBytes += entry.getValue();
            if (isSharedAttributionBucket(entry.getKey())) {
                sharedBytes += entry.getValue();
            } else {
                concrete.put(entry.getKey(), entry.getValue());
            }
        }
        if (concrete.isEmpty()) {
            return new EffectiveMemoryAttribution(new LinkedHashMap<>(rawMemoryMods), Map.of(), totalBytes);
        }
        Map<String, Double> weights = buildMemoryWeightMap(concrete);
        Map<String, Long> redistributed = distributeLongProportionally(sharedBytes, weights);
        LinkedHashMap<String, Long> display = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : concrete.entrySet()) {
            display.put(entry.getKey(), entry.getValue() + redistributed.getOrDefault(entry.getKey(), 0L));
        }
        return new EffectiveMemoryAttribution(display, redistributed, totalBytes);
    }

    public static String effectiveGpuPhaseOwner(RenderPhaseProfiler.PhaseSnapshot phaseSnapshot) {
        if (phaseSnapshot == null) {
            return "shared/render";
        }
        String owner = phaseSnapshot.ownerMod() == null || phaseSnapshot.ownerMod().isBlank() ? "shared/render" : phaseSnapshot.ownerMod();
        Map<String, Long> owners = phaseSnapshot.likelyOwners();
        if (!isSharedAttributionBucket(owner) || owners == null || owners.isEmpty()) {
            return owner;
        }
        Map.Entry<String, Long> topOwner = owners.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !isSharedAttributionBucket(entry.getKey()))
                .max(Map.Entry.comparingByValue())
                .orElse(null);
        if (topOwner == null) {
            return owner;
        }
        long totalHints = owners.values().stream().mapToLong(Long::longValue).sum();
        if (topOwner.getValue() < 2L || totalHints <= 0L || topOwner.getValue() * 100L < totalHints * 65L) {
            return owner;
        }
        return topOwner.getKey();
    }

    public static boolean hasPromotedLikelyOwner(RenderPhaseProfiler.PhaseSnapshot phaseSnapshot) {
        if (phaseSnapshot == null) {
            return false;
        }
        String rawOwner = phaseSnapshot.ownerMod() == null || phaseSnapshot.ownerMod().isBlank() ? "shared/render" : phaseSnapshot.ownerMod();
        return isSharedAttributionBucket(rawOwner) && !effectiveGpuPhaseOwner(phaseSnapshot).equals(rawOwner);
    }

    public static List<String> buildGpuPhaseBreakdownLines(
            Map<String, RenderPhaseProfiler.PhaseSnapshot> renderPhases,
            String modId,
            Function<String, String> displayNameFn
    ) {
        return renderPhases.entrySet().stream()
                .filter(entry -> {
                    String owner = effectiveGpuPhaseOwner(entry.getValue());
                    return modId.equals(owner) || ("shared/render".equals(modId) && isSharedAttributionBucket(owner));
                })
                .sorted((a, b) -> Long.compare(b.getValue().gpuNanos(), a.getValue().gpuNanos()))
                .limit(5)
                .map(entry -> String.format(Locale.ROOT, "%s | %.2f ms%s",
                        entry.getKey(),
                        entry.getValue().gpuNanos() / 1_000_000.0,
                        isSharedAttributionBucket(modId) ? formatLikelyOwnerSuffix(entry.getValue(), displayNameFn) : ""))
                .toList();
    }

    public static Map<String, Long> buildSharedRenderLikelyOwners(
            Map<String, RenderPhaseProfiler.PhaseSnapshot> renderPhases,
            Function<String, String> displayNameFn
    ) {
        Map<String, Long> totals = new LinkedHashMap<>();
        renderPhases.forEach((phase, phaseSnapshot) -> {
            String owner = effectiveGpuPhaseOwner(phaseSnapshot);
            if (!isSharedAttributionBucket(owner) || phaseSnapshot.gpuNanos() <= 0L || phaseSnapshot.likelyOwners() == null) {
                return;
            }
            phaseSnapshot.likelyOwners().forEach((key, value) -> totals.merge(displayNameFn.apply(key), value, Long::sum));
        });
        return topLongEntries(totals, 5);
    }

    public static Map<String, Long> buildSharedRenderLikelyFrames(Map<String, RenderPhaseProfiler.PhaseSnapshot> renderPhases) {
        Map<String, Long> totals = new LinkedHashMap<>();
        renderPhases.forEach((phase, phaseSnapshot) -> {
            String owner = effectiveGpuPhaseOwner(phaseSnapshot);
            if (!isSharedAttributionBucket(owner) || phaseSnapshot.gpuNanos() <= 0L) {
                return;
            }
            mergeLongTotals(totals, phaseSnapshot.likelyFrames());
        });
        return topLongEntries(totals, 5);
    }

    public static String describeGpuOwnerSource(Map<String, RenderPhaseProfiler.PhaseSnapshot> renderPhases, String modId) {
        if (modId == null || modId.isBlank()) {
            return "unknown";
        }
        if ("shared/render".equals(modId)) {
            return "shared/render fallback bucket from phases without a concrete owner";
        }
        if (isSharedAttributionBucket(modId)) {
            return "shared bucket carried through raw ownership view";
        }
        long promotedPhases = renderPhases.values().stream()
                .filter(AttributionModelBuilder::hasPromotedLikelyOwner)
                .filter(phase -> modId.equals(effectiveGpuPhaseOwner(phase)))
                .count();
        if (promotedPhases > 0L) {
            return "claimed from shared/render by a strong likely-owner signal in " + promotedPhases + " phase" + (promotedPhases == 1L ? "" : "s");
        }
        long taggedPhases = renderPhases.values().stream()
                .filter(phase -> modId.equals(effectiveGpuPhaseOwner(phase)))
                .count();
        return taggedPhases > 0 ? "directly tagged by " + taggedPhases + " render phase" + (taggedPhases == 1 ? "" : "s") : "redistributed from shared render work";
    }

    private static String formatLikelyOwnerSuffix(RenderPhaseProfiler.PhaseSnapshot snapshot, Function<String, String> displayNameFn) {
        if (snapshot == null || snapshot.likelyOwners() == null || snapshot.likelyOwners().isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner(", ");
        snapshot.likelyOwners().entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(2)
                .forEach(entry -> joiner.add(displayNameFn.apply(entry.getKey()) + " " + entry.getValue()));
        String summary = joiner.toString();
        return summary.isBlank() ? "" : " | likely " + summary;
    }

    public static Map<String, Double> buildCpuWeightMap(
            Map<String, CpuSamplingProfiler.Snapshot> concrete,
            Map<String, CpuSamplingProfiler.DetailSnapshot> cpuDetails,
            Map<String, ModTimingSnapshot> invokes,
            ToLongFunction<CpuSamplingProfiler.Snapshot> extractor
    ) {
        LinkedHashMap<String, Double> weights = new LinkedHashMap<>();
        double total = 0.0;
        for (Map.Entry<String, CpuSamplingProfiler.Snapshot> entry : concrete.entrySet()) {
            double weight = Math.max(0L, extractor.applyAsLong(entry.getValue()));
            CpuSamplingProfiler.DetailSnapshot detail = cpuDetails == null ? null : cpuDetails.get(entry.getKey());
            if (AttributionInsights.isRenderSubmissionHeavy(detail)) {
                weight *= 0.25;
            }
            weights.put(entry.getKey(), weight);
            total += weight;
        }
        if (total > 0.0) {
            return weights;
        }
        double invokeTotal = 0.0;
        for (String modId : concrete.keySet()) {
            double weight = Math.max(0L, invokes.getOrDefault(modId, new ModTimingSnapshot(0, 0)).calls());
            CpuSamplingProfiler.DetailSnapshot detail = cpuDetails == null ? null : cpuDetails.get(modId);
            if (AttributionInsights.isRenderSubmissionHeavy(detail)) {
                weight *= 0.25;
            }
            weights.put(modId, weight);
            invokeTotal += weight;
        }
        if (invokeTotal > 0.0) {
            return weights;
        }
        for (String modId : concrete.keySet()) {
            weights.put(modId, 1.0);
        }
        return weights;
    }

    public static Map<String, Double> buildMemoryWeightMap(Map<String, Long> concrete) {
        LinkedHashMap<String, Double> weights = new LinkedHashMap<>();
        double total = 0.0;
        for (Map.Entry<String, Long> entry : concrete.entrySet()) {
            double weight = Math.max(0L, entry.getValue());
            weights.put(entry.getKey(), weight);
            total += weight;
        }
        if (total > 0.0) {
            return weights;
        }
        for (String modId : concrete.keySet()) {
            weights.put(modId, 1.0);
        }
        return weights;
    }

    public static Map<String, Double> buildGpuWeightMap(Map<String, Long> renderSamplesByMod, Map<String, Long> directGpuByMod) {
        LinkedHashMap<String, Double> weights = new LinkedHashMap<>();
        double total = 0.0;
        double nonMinecraftTotal = 0.0;
        for (Map.Entry<String, Long> entry : directGpuByMod.entrySet()) {
            if (isSharedAttributionBucket(entry.getKey())) {
                continue;
            }
            double weight = Math.max(0L, entry.getValue());
            weights.put(entry.getKey(), weight);
            total += weight;
            if (!"minecraft".equals(entry.getKey())) {
                nonMinecraftTotal += weight;
            }
        }
        if (total > 0.0) {
            return weights;
        }
        return Map.of();
    }

    public static Map<String, Long> distributeLongProportionally(long total, Map<String, Double> weights) {
        if (total <= 0L || weights.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        ArrayList<Map.Entry<String, Double>> entries = new ArrayList<>(weights.entrySet());
        double weightSum = entries.stream().mapToDouble(entry -> Math.max(0.0, entry.getValue())).sum();
        if (weightSum <= 0.0) {
            for (Map.Entry<String, Double> entry : entries) {
                result.put(entry.getKey(), 0L);
            }
            return result;
        }
        LinkedHashMap<String, Double> remainders = new LinkedHashMap<>();
        long assigned = 0L;
        for (Map.Entry<String, Double> entry : entries) {
            double exact = total * Math.max(0.0, entry.getValue()) / weightSum;
            long whole = (long) Math.floor(exact);
            result.put(entry.getKey(), whole);
            remainders.put(entry.getKey(), exact - whole);
            assigned += whole;
        }
        long remainder = total - assigned;
        if (remainder > 0L) {
            entries.sort((a, b) -> Double.compare(remainders.getOrDefault(b.getKey(), 0.0), remainders.getOrDefault(a.getKey(), 0.0)));
            for (int i = 0; i < remainder; i++) {
                String modId = entries.get(i % entries.size()).getKey();
                result.put(modId, result.getOrDefault(modId, 0L) + 1L);
            }
        }
        return result;
    }

    private static void mergeLongTotals(Map<String, Long> target, Map<String, Long> source) {
        if (source == null) {
            return;
        }
        source.forEach((key, value) -> target.merge(key, value, Long::sum));
    }

    private static Map<String, Long> topLongEntries(Map<String, Long> source, int limit) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> result = new LinkedHashMap<>();
        source.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    public static boolean isSharedAttributionBucket(String modId) {
        return modId != null && (modId.startsWith("shared/") || modId.startsWith("runtime/"));
    }
}
