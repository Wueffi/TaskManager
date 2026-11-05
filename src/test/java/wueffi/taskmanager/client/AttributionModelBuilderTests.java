package wueffi.taskmanager.client;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AttributionModelBuilderTests {

    private AttributionModelBuilderTests() {
    }

    public static void run() {
        gpuPhaseOwnerPromotesWhenLikelyOwnerIsStrong();
        gpuPhaseOwnerStaysSharedWhenLikelyOwnerIsWeak();
        cpuRedistributionDownweightsRenderSubmissionHeavyMods();
        rawGpuOwnershipDoesNotPromoteCpuHints();
    }

    private static void rawGpuOwnershipDoesNotPromoteCpuHints() {
        Map<String, RenderPhaseProfiler.PhaseSnapshot> phases = Map.of("world", new RenderPhaseProfiler.PhaseSnapshot(
                0L, 0L, 10_000_000L, 1L, "shared/render", Map.of("iris", 10L), Map.of()));
        AttributionModelBuilder.EffectiveCpuAttribution cpu = AttributionModelBuilder.buildEffectiveCpuAttribution(Map.of(), Map.of(), Map.of());
        AttributionModelBuilder.EffectiveGpuAttribution raw = AttributionModelBuilder.buildEffectiveGpuAttribution(phases, Map.of(), cpu, false);
        if (raw.gpuNanosByMod().getOrDefault("shared/render", 0L) != 10_000_000L || raw.gpuNanosByMod().containsKey("iris")) {
            throw new AssertionError("raw GPU timings must retain tagged ownership, not CPU hints");
        }
        AttributionModelBuilder.EffectiveGpuAttribution effective = AttributionModelBuilder.buildEffectiveGpuAttribution(phases, Map.of(), cpu, true);
        if (effective.gpuNanosByMod().getOrDefault("iris", 0L) != 10_000_000L) {
            throw new AssertionError("effective view should retain its explicitly heuristic owner estimate");
        }
    }

    private static void gpuPhaseOwnerPromotesWhenLikelyOwnerIsStrong() {
        RenderPhaseProfiler.PhaseSnapshot phase = new RenderPhaseProfiler.PhaseSnapshot(
                0L, 0L, 10_000_000L, 1L, "shared/render",
                Map.of("iris", 7L, "minecraft", 2L),
                Map.of()
        );
        assertEquals("iris", AttributionModelBuilder.effectiveGpuPhaseOwner(phase), "strong likely-owner majority should claim the phase");
    }

    private static void gpuPhaseOwnerStaysSharedWhenLikelyOwnerIsWeak() {
        RenderPhaseProfiler.PhaseSnapshot phase = new RenderPhaseProfiler.PhaseSnapshot(
                0L, 0L, 10_000_000L, 1L, "shared/render",
                Map.of("iris", 1L, "minecraft", 1L),
                Map.of()
        );
        assertEquals("shared/render", AttributionModelBuilder.effectiveGpuPhaseOwner(phase), "weak likely-owner hints should leave the phase shared");
    }

    private static void cpuRedistributionDownweightsRenderSubmissionHeavyMods() {
        Map<String, CpuSamplingProfiler.Snapshot> rawCpu = new LinkedHashMap<>();
        rawCpu.put("chunky", new CpuSamplingProfiler.Snapshot(100L, 0L, 100L, 100L, 0L, 100L));
        rawCpu.put("sodium", new CpuSamplingProfiler.Snapshot(100L, 0L, 100L, 100L, 0L, 100L));
        rawCpu.put("shared/framework", new CpuSamplingProfiler.Snapshot(100L, 0L, 100L, 100L, 0L, 100L));

        Map<String, CpuSamplingProfiler.DetailSnapshot> cpuDetails = new LinkedHashMap<>();
        cpuDetails.put("chunky", new CpuSamplingProfiler.DetailSnapshot(
                linkedLongs("Render thread", 10L),
                linkedLongs("GL30C#glBindFramebuffer", 10L, "JNI#invokePV", 5L),
                1
        ));
        cpuDetails.put("sodium", new CpuSamplingProfiler.DetailSnapshot(
                linkedLongs("Render thread", 10L),
                linkedLongs("ChunkBuilder#buildMeshes", 10L, "SectionCompiler#compile", 5L),
                1
        ));

        AttributionModelBuilder.EffectiveCpuAttribution effective = AttributionModelBuilder.buildEffectiveCpuAttribution(rawCpu, cpuDetails, Map.of());
        long chunkySamples = effective.displaySnapshots().get("chunky").totalSamples();
        long sodiumSamples = effective.displaySnapshots().get("sodium").totalSamples();
        if (chunkySamples >= sodiumSamples) {
            throw new AssertionError("render-submission-heavy rows should receive less redistributed CPU: chunky=" + chunkySamples + ", sodium=" + sodiumSamples);
        }
    }

    private static Map<String, Long> linkedLongs(String firstKey, long firstValue) {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put(firstKey, firstValue);
        return result;
    }

    private static Map<String, Long> linkedLongs(String firstKey, long firstValue, String secondKey, long secondValue) {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put(firstKey, firstValue);
        result.put(secondKey, secondValue);
        return result;
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
