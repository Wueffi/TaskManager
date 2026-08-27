package wueffi.taskmanager.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

final class SessionExporter {

    private static final long EXPORT_TIMEOUT_MILLIS = 30_000L;
    private static final Gson GSON = new GsonBuilder().serializeSpecialFloatingPointValues().setPrettyPrinting().create();

    private final AtomicBoolean exportInFlight = new AtomicBoolean(false);
    private volatile ExecutorService exportExecutor = createExecutor();
    private volatile Future<?> exportFuture;
    private volatile long exportStartedAtMillis;

    String exportSession(ProfilerManager manager) {
        long now = System.currentTimeMillis();
        if (!exportInFlight.compareAndSet(false, true)) {
            if (isTimedOut(now)) {
                cancelHungExport();
            } else {
                return manager.lastExportStatus();
            }
        }

        manager.beginExport();
        MinecraftClient client = MinecraftClient.getInstance();
        exportStartedAtMillis = now;
        exportFuture = exportExecutor.submit(() -> {
            ProfilerManager.ExportResult result = manager.runSessionExport();
            if (client != null) {
                client.execute(() -> manager.notifyExportFinished(client, result));
            }
            manager.finishExport(result);
            exportInFlight.set(false);
            exportStartedAtMillis = 0L;
        });
        manager.requestSnapshotPublish();
        return manager.lastExportStatus();
    }

    ProfilerManager.ExportResult runSessionExport(ProfilerManager manager) {
        Map<String, Object> export = manager.buildSessionExportPayload();
        Path dir = sessionDirectory();
        try {
            Files.createDirectories(dir);
            long exportTimestamp = System.currentTimeMillis();
            Path file = dir.resolve("taskmanager-session-" + exportTimestamp + ".json");
            Files.writeString(file, manager.exportJson(export));
            Path htmlFile = dir.resolve("taskmanager-session-" + exportTimestamp + ".html");
            Files.writeString(htmlFile, manager.buildSessionHtmlReport(export));
            Path traceFile = dir.resolve("taskmanager-session-" + exportTimestamp + ".trace.json");
            Files.writeString(traceFile, manager.buildChromeTraceJson());
            manager.logSessionExport(file);
            return new ProfilerManager.ExportResult("Exported " + file.getFileName() + " + " + htmlFile.getFileName() + " + " + traceFile.getFileName(), dir, htmlFile, traceFile);
        } catch (Exception e) {
            return new ProfilerManager.ExportResult("Export failed: " + e.getMessage(), null, null, null);
        }
    }

    void saveBaseline(ProfilerManager.SessionBaseline baseline) {
        Path file = baselineFile();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(baseline));
        } catch (IOException ignored) {
        }
    }

    ProfilerManager.SessionBaseline loadBaseline() {
        Path file = baselineFile();
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return GSON.fromJson(Files.readString(file), ProfilerManager.SessionBaseline.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    void clearBaseline() {
        try {
            Files.deleteIfExists(baselineFile());
        } catch (IOException ignored) {
        }
    }

    ProfilerManager.SessionBaseline importLatestSessionBaseline() {
        try {
            Path dir = sessionDirectory();
            if (!Files.isDirectory(dir)) {
                return null;
            }
            return Files.list(dir)
                    .filter(path -> path.getFileName().toString().startsWith("taskmanager-session-"))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().endsWith(".trace.json"))
                    .max(Comparator.comparing(Path::getFileName))
                    .map(this::importSession)
                    .orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }

    ProfilerManager.SessionBaseline importSession(Path path) {
        if (path == null || !Files.exists(path)) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            JsonElement baselineElement = root.get("baseline");
            if (baselineElement != null && baselineElement.isJsonObject()) {
                ProfilerManager.SessionBaseline baseline = GSON.fromJson(baselineElement, ProfilerManager.SessionBaseline.class);
                if (baseline != null) {
                    return baseline;
                }
            }
            JsonElement sessionPointsElement = root.get("sessionPoints");
            if (sessionPointsElement == null || !sessionPointsElement.isJsonArray() || sessionPointsElement.getAsJsonArray().isEmpty()) {
                return null;
            }
            double avgFps = 0.0;
            double avgOnePercentLow = 0.0;
            double avgMspt = 0.0;
            double avgMsptP95 = 0.0;
            double avgHeapBytes = 0.0;
            int count = 0;
            Map<String, Double> cpuTotals = new LinkedHashMap<>();
            Map<String, Double> gpuTotals = new LinkedHashMap<>();
            Map<String, Double> memoryTotals = new LinkedHashMap<>();
            Map<String, Integer> cpuCounts = new LinkedHashMap<>();
            Map<String, Integer> gpuCounts = new LinkedHashMap<>();
            Map<String, Integer> memoryCounts = new LinkedHashMap<>();
            for (JsonElement element : sessionPointsElement.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject point = element.getAsJsonObject();
                avgFps += getDouble(point, "averageFps");
                avgOnePercentLow += getDouble(point, "onePercentLowFps");
                avgMspt += getDouble(point, "msptAvg");
                avgMsptP95 += getDouble(point, "msptP95");
                avgHeapBytes += getDouble(point, "heapUsedBytes");
                mergeAverageMap(cpuTotals, cpuCounts, point.getAsJsonObject("cpuEffectivePercentByMod"));
                mergeAverageMap(gpuTotals, gpuCounts, point.getAsJsonObject("gpuEffectivePercentByMod"));
                mergeAverageMap(memoryTotals, memoryCounts, point.getAsJsonObject("memoryEffectiveMbByMod"));
                count++;
            }
            if (count == 0) {
                return null;
            }
            String label = path.getFileName().toString().replace(".json", "");
            return new ProfilerManager.SessionBaseline(
                    avgFps / count,
                    avgOnePercentLow / count,
                    avgMspt / count,
                    avgMsptP95 / count,
                    Math.round(avgHeapBytes / count),
                    finalizeAverageMap(cpuTotals, cpuCounts),
                    finalizeAverageMap(gpuTotals, gpuCounts),
                    finalizeAverageMap(memoryTotals, memoryCounts),
                    Files.getLastModifiedTime(path).toMillis(),
                    label
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isTimedOut(long now) {
        return exportStartedAtMillis > 0L && now - exportStartedAtMillis > EXPORT_TIMEOUT_MILLIS;
    }

    private void cancelHungExport() {
        Future<?> future = exportFuture;
        if (future != null) {
            future.cancel(true);
        }
        exportExecutor.shutdownNow();
        exportExecutor = createExecutor();
        exportFuture = null;
        exportStartedAtMillis = 0L;
        exportInFlight.set(false);
    }

    private static ExecutorService createExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "taskmanager-session-export");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static Path sessionDirectory() {
        return FabricLoader.getInstance().getGameDir().resolve("taskmanager-sessions");
    }

    private static Path baselineFile() {
        return sessionDirectory().resolve("baseline.json");
    }

    private static double getDouble(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || !element.isJsonPrimitive() ? 0.0 : element.getAsDouble();
    }

    private static void mergeAverageMap(Map<String, Double> totals, Map<String, Integer> counts, JsonObject values) {
        if (values == null) {
            return;
        }
        values.entrySet().forEach(entry -> {
            double value = entry.getValue() == null || !entry.getValue().isJsonPrimitive() ? 0.0 : entry.getValue().getAsDouble();
            totals.merge(entry.getKey(), value, Double::sum);
            counts.merge(entry.getKey(), 1, Integer::sum);
        });
    }

    private static Map<String, Double> finalizeAverageMap(Map<String, Double> totals, Map<String, Integer> counts) {
        Map<String, Double> averages = new LinkedHashMap<>();
        totals.forEach((key, total) -> averages.put(key, total / Math.max(1, counts.getOrDefault(key, 1))));
        return averages;
    }
}
