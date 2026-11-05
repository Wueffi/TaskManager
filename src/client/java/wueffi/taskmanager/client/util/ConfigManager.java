package wueffi.taskmanager.client.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import wueffi.taskmanager.client.ProfilerManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigManager {

    public enum HudPosition {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT;

        public HudPosition next() {
            HudPosition[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public enum HudLayoutMode {
        SINGLE_COLUMN,
        TWO_COLUMN,
        THREE_COLUMN;

        public HudLayoutMode next() {
            HudLayoutMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        public int columns() {
            return switch (this) {
                case SINGLE_COLUMN -> 1;
                case TWO_COLUMN -> 2;
                case THREE_COLUMN -> 3;
            };
        }
    }

    public enum HudTriggerMode {
        ALWAYS,
        SPIKES_ONLY,
        WARNINGS_ONLY;

        public HudTriggerMode next() {
            HudTriggerMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public enum HudPreset {
        COMPACT,
        FULL;

        public HudPreset next() {
            HudPreset[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public enum HudConfigMode {
        PRESET,
        CUSTOM;

        public HudConfigMode next() {
            HudConfigMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int[] FRAME_BUDGET_TARGET_FPS_OPTIONS = {30, 45, 60, 72, 90, 120, 144, 165, 240};
    private static final int[] PERFORMANCE_ALERT_FRAME_THRESHOLD_OPTIONS = {16, 20, 25, 30, 35, 40, 50};
    private static final int[] PERFORMANCE_ALERT_SERVER_THRESHOLD_OPTIONS = {15, 20, 25, 30, 40, 50, 75};
    private static final int[] PERFORMANCE_ALERT_CONSECUTIVE_OPTIONS = {1, 2, 3, 4, 5, 8};
    private static final Path CONFIG_PATH = resolveConfigPath();
    private static volatile ConfigData config = new ConfigData();

    private static Path resolveConfigPath() {
        try {
            Path configDir = FabricLoader.getInstance().getConfigDir();
            if (configDir != null) {
                return configDir.resolve("taskmanager.json");
            }
        } catch (Throwable ignored) {
        }
        return Path.of("taskmanager-test-config.json");
    }

    public static ProfilerManager.CaptureMode getCaptureMode() {
        try {
            return ProfilerManager.CaptureMode.valueOf(config.captureMode);
        } catch (Exception ignored) {
            return ProfilerManager.CaptureMode.OPEN_ONLY;
        }
    }

    public static void setCaptureMode(ProfilerManager.CaptureMode mode) {
        config.captureMode = mode.name();
        saveConfig();
    }

    public static boolean isHudEnabled() {
        return config.hudEnabled;
    }

    public static void setHudEnabled(boolean value) {
        config.hudEnabled = value;
        saveConfig();
    }

    public static HudPosition getHudPosition() {
        try {
            return HudPosition.valueOf(config.hudPosition);
        } catch (Exception ignored) {
            return HudPosition.TOP_LEFT;
        }
    }

    public static void cycleHudPosition() {
        config.hudPosition = getHudPosition().next().name();
        saveConfig();
    }

    public static HudLayoutMode getHudLayoutMode() {
        try {
            return HudLayoutMode.valueOf(config.hudLayoutMode);
        } catch (Exception ignored) {
            return HudLayoutMode.SINGLE_COLUMN;
        }
    }

    public static void cycleHudLayoutMode() {
        config.hudLayoutMode = getHudLayoutMode().next().name();
        saveConfig();
    }

    public static int getSessionDurationSeconds() {
        return Math.max(15, config.sessionDurationSeconds);
    }

    public static void cycleSessionDurationSeconds() {
        int current = getSessionDurationSeconds();
        int next = switch (current) {
            case 15 -> 30;
            case 30 -> 60;
            case 60 -> 120;
            case 120 -> 300;
            default -> 15;
        };
        config.sessionDurationSeconds = next;
        saveConfig();
    }

    public static int getMetricsUpdateIntervalMs() {
        return Math.clamp(config.metricsUpdateIntervalMs, 50, 2000);
    }

    public static void cycleMetricsUpdateIntervalMs() {
        int current = getMetricsUpdateIntervalMs();
        int next = switch (current) {
            case 50 -> 100;
            case 100 -> 250;
            case 250 -> 500;
            case 500 -> 1000;
            case 1000 -> 2000;
            default -> 50;
        };
        config.metricsUpdateIntervalMs = next;
        saveConfig();
    }

    public static int getProfilerUpdateDelayMs() {
        return Math.clamp(config.profilerUpdateDelayMs, 50, 2000);
    }

    public static int getFrameBudgetTargetFps() {
        return config.frameBudgetTargetFps <= 0 ? 60 : config.frameBudgetTargetFps;
    }

    public static double getFrameBudgetTargetFrameMs() {
        return 1000.0 / Math.max(1, getFrameBudgetTargetFps());
    }

    public static void cycleFrameBudgetTargetFps() {
        config.frameBudgetTargetFps = cycleIntOption(getFrameBudgetTargetFps(), FRAME_BUDGET_TARGET_FPS_OPTIONS);
        saveConfig();
    }

    public static boolean isPerformanceAlertsEnabled() { return config.performanceAlertsEnabled; }

    public static void togglePerformanceAlertsEnabled() {
        config.performanceAlertsEnabled = !isPerformanceAlertsEnabled();
        saveConfig();
    }

    public static boolean isPerformanceAlertChatEnabled() { return config.performanceAlertChatEnabled; }

    public static void togglePerformanceAlertChatEnabled() {
        config.performanceAlertChatEnabled = !isPerformanceAlertChatEnabled();
        saveConfig();
    }

    public static int getPerformanceAlertFrameThresholdMs() {
        return Math.clamp(config.performanceAlertFrameThresholdMs, 10, 120);
    }

    public static void cyclePerformanceAlertFrameThresholdMs() {
        config.performanceAlertFrameThresholdMs = cycleIntOption(getPerformanceAlertFrameThresholdMs(), PERFORMANCE_ALERT_FRAME_THRESHOLD_OPTIONS);
        saveConfig();
    }

    public static int getPerformanceAlertServerThresholdMs() {
        return Math.clamp(config.performanceAlertServerThresholdMs, 10, 200);
    }

    public static void cyclePerformanceAlertServerThresholdMs() {
        config.performanceAlertServerThresholdMs = cycleIntOption(getPerformanceAlertServerThresholdMs(), PERFORMANCE_ALERT_SERVER_THRESHOLD_OPTIONS);
        saveConfig();
    }

    public static int getPerformanceAlertConsecutiveTicks() {
        return Math.clamp(config.performanceAlertConsecutiveTicks, 1, 10);
    }

    public static void cyclePerformanceAlertConsecutiveTicks() {
        config.performanceAlertConsecutiveTicks = cycleIntOption(getPerformanceAlertConsecutiveTicks(), PERFORMANCE_ALERT_CONSECUTIVE_OPTIONS);
        saveConfig();
    }

    public static void cycleProfilerUpdateDelayMs() {
        int current = getProfilerUpdateDelayMs();
        int next = switch (current) {
            case 50 -> 100;
            case 100 -> 250;
            case 250 -> 500;
            case 500 -> 1000;
            case 1000 -> 2000;
            default -> 50;
        };
        config.profilerUpdateDelayMs = next;
        saveConfig();
    }

    public static int getHudFpsDisplayDelayMs() {
        return Math.clamp(config.hudFpsDisplayDelayMs, 50, 1000);
    }

    public static void cycleHudFpsDisplayDelayMs() {
        int current = getHudFpsDisplayDelayMs();
        int next = switch (current) {
            case 50 -> 100;
            case 100 -> 250;
            case 250 -> 500;
            case 500 -> 1000;
            default -> 50;
        };
        config.hudFpsDisplayDelayMs = next;
        saveConfig();
    }

    public static int getHudMemoryDisplayDelayMs() {
        return Math.clamp(config.hudMemoryDisplayDelayMs, 50, 1000);
    }

    public static void cycleHudMemoryDisplayDelayMs() {
        int current = getHudMemoryDisplayDelayMs();
        int next = switch (current) {
            case 50 -> 100;
            case 100 -> 250;
            case 250 -> 500;
            case 500 -> 1000;
            default -> 50;
        };
        config.hudMemoryDisplayDelayMs = next;
        saveConfig();
    }

    public static int getHudTransparencyPercent() {
        return Math.clamp(config.hudTransparencyPercent, 10, 100);
    }

    public static void setHudTransparencyPercent(int value) {
        config.hudTransparencyPercent = Math.clamp(value, 10, 100);
        saveConfig();
    }


    public static int getCpuGraphColor() { return parseColor(config.cpuGraphColor, 0xFF5EA9FF); }
    public static int getGpuGraphColor() { return parseColor(config.gpuGraphColor, 0xFF77DD77); }
    public static int getWorldEntityGraphColor() { return parseColor(config.worldEntityGraphColor, 0xFFFFC857); }
    public static int getWorldLoadedChunkGraphColor() { return parseColor(config.worldLoadedChunkGraphColor, 0xFF70C7A7); }
    public static int getWorldRenderedChunkGraphColor() { return parseColor(config.worldRenderedChunkGraphColor, 0xFF5EA9FF); }

    public static String getCpuGraphColorHex() { return normalizeColorHex(config.cpuGraphColor, 0xFF5EA9FF); }
    public static String getGpuGraphColorHex() { return normalizeColorHex(config.gpuGraphColor, 0xFF77DD77); }
    public static String getWorldEntityGraphColorHex() { return normalizeColorHex(config.worldEntityGraphColor, 0xFFFFC857); }
    public static String getWorldLoadedChunkGraphColorHex() { return normalizeColorHex(config.worldLoadedChunkGraphColor, 0xFF70C7A7); }
    public static String getWorldRenderedChunkGraphColorHex() { return normalizeColorHex(config.worldRenderedChunkGraphColor, 0xFF5EA9FF); }

    public static void setCpuGraphColorHex(String value) { config.cpuGraphColor = normalizeColorHex(value, 0xFF5EA9FF); saveConfig(); }
    public static void setGpuGraphColorHex(String value) { config.gpuGraphColor = normalizeColorHex(value, 0xFF77DD77); saveConfig(); }
    public static void setWorldEntityGraphColorHex(String value) { config.worldEntityGraphColor = normalizeColorHex(value, 0xFFFFC857); saveConfig(); }
    public static void setWorldLoadedChunkGraphColorHex(String value) { config.worldLoadedChunkGraphColor = normalizeColorHex(value, 0xFF70C7A7); saveConfig(); }
    public static void setWorldRenderedChunkGraphColorHex(String value) { config.worldRenderedChunkGraphColor = normalizeColorHex(value, 0xFF5EA9FF); saveConfig(); }

    public static void resetGraphColors() {
        config.cpuGraphColor = "#5EA9FF";
        config.gpuGraphColor = "#77DD77";
        config.worldEntityGraphColor = "#FFC857";
        config.worldLoadedChunkGraphColor = "#70C7A7";
        config.worldRenderedChunkGraphColor = "#5EA9FF";
        saveConfig();
    }

    public static boolean isHudShowFps() { return config.hudShowFps; }
    public static boolean isHudShowFrame() { return config.hudShowFrame; }
    public static boolean isHudShowTicks() { return config.hudShowTicks; }
    public static boolean isHudShowUtilization() { return config.hudShowUtilization; }
    public static boolean isHudShowTemperatures() { return config.hudShowTemperatures; }
    public static boolean isHudShowParallelism() { return config.hudShowParallelism; }
    public static boolean isHudShowLogic() { return config.hudShowLogic; }
    public static boolean isHudShowBackground() { return config.hudShowBackground; }
    public static boolean isHudShowFrameBudget() { return config.hudShowFrameBudget; }
    public static boolean isHudShowMemory() { return config.hudShowMemory; }
    public static boolean isHudShowMemoryAllocationRate() { return config.hudShowMemoryAllocationRate; }
    public static boolean isHudShowVram() { return config.hudShowVram; }
    public static boolean isHudShowNetwork() { return config.hudShowNetwork; }
    public static boolean isHudShowChunkActivity() { return config.hudShowChunkActivity; }
    public static boolean isHudShowWorld() { return config.hudShowWorld; }
    public static boolean isHudShowDiskIo() { return config.hudShowDiskIo; }
    public static boolean isHudShowInputLatency() { return config.hudShowInputLatency; }
    public static boolean isHudShowSession() { return config.hudShowSession; }
    public static boolean isHudShowFpsRateOfChange() { return config.hudShowFpsRateOfChange; }
    public static boolean isHudShowFrameRateOfChange() { return config.hudShowFrameRateOfChange; }
    public static boolean isHudShowTickRateOfChange() { return config.hudShowTickRateOfChange; }
    public static boolean isHudShowUtilizationRateOfChange() { return config.hudShowUtilizationRateOfChange; }
    public static boolean isHudShowWorldRateOfChange() { return config.hudShowWorldRateOfChange; }
    public static boolean isHudShowVramRateOfChange() { return config.hudShowVramRateOfChange; }
    public static boolean isHudShowNetworkRateOfChange() { return config.hudShowNetworkRateOfChange; }
    public static boolean isHudShowChunkActivityRateOfChange() { return config.hudShowChunkActivityRateOfChange; }
    public static boolean isHudShowDiskIoRateOfChange() { return config.hudShowDiskIoRateOfChange; }
    public static boolean isHudShowInputLatencyRateOfChange() { return config.hudShowInputLatencyRateOfChange; }
    public static boolean isHudShowZeroRateOfChange() { return config.hudShowZeroRateOfChange; }
    public static boolean isHudAutoFocusAlertRow() { return config.hudAutoFocusAlertRow; }
    public static boolean isHudBudgetColorMode() { return config.hudBudgetColorMode; }

    public static HudTriggerMode getHudTriggerMode() {
        try {
            return HudTriggerMode.valueOf(config.hudTriggerMode);
        } catch (Exception ignored) {
            return config.hudSpikeOnly ? HudTriggerMode.SPIKES_ONLY : HudTriggerMode.ALWAYS;
        }
    }

    public static HudPreset getHudPreset() {
        try {
            return HudPreset.valueOf(config.hudPreset);
        } catch (Exception ignored) {
            return HudPreset.COMPACT;
        }
    }

    public static void cycleHudPreset() {
        config.hudPreset = getHudPreset().next().name();
        saveConfig();
    }

    public static HudConfigMode getHudConfigMode() {
        try {
            return HudConfigMode.valueOf(config.hudConfigMode);
        } catch (Exception ignored) {
            return HudConfigMode.PRESET;
        }
    }

    public static void cycleHudConfigMode() {
        config.hudConfigMode = getHudConfigMode().next().name();
        saveConfig();
    }

    public static boolean isHudExpandedOnWarning() {
        return config.hudExpandedOnWarning;
    }

    public static void setHudExpandedOnWarning(boolean value) {
        config.hudExpandedOnWarning = value;
        saveConfig();
    }

    public static void toggleHudShowFps() { config.hudShowFps = !config.hudShowFps; saveConfig(); }
    public static void toggleHudShowFrame() { config.hudShowFrame = !config.hudShowFrame; saveConfig(); }
    public static void toggleHudShowTicks() { config.hudShowTicks = !config.hudShowTicks; saveConfig(); }
    public static void toggleHudShowUtilization() { config.hudShowUtilization = !config.hudShowUtilization; saveConfig(); }
    public static void toggleHudShowTemperatures() { config.hudShowTemperatures = !config.hudShowTemperatures; saveConfig(); }
    public static void toggleHudShowParallelism() { config.hudShowParallelism = !config.hudShowParallelism; saveConfig(); }
    public static void toggleHudShowLogic() { config.hudShowLogic = !isHudShowLogic(); saveConfig(); }
    public static void toggleHudShowBackground() { config.hudShowBackground = !isHudShowBackground(); saveConfig(); }
    public static void toggleHudShowFrameBudget() { config.hudShowFrameBudget = !isHudShowFrameBudget(); saveConfig(); }
    public static void toggleHudShowMemory() { config.hudShowMemory = !config.hudShowMemory; saveConfig(); }
    public static void toggleHudShowMemoryAllocationRate() { config.hudShowMemoryAllocationRate = !isHudShowMemoryAllocationRate(); saveConfig(); }
    public static void toggleHudShowVram() { config.hudShowVram = !isHudShowVram(); saveConfig(); }
    public static void toggleHudShowNetwork() { config.hudShowNetwork = !isHudShowNetwork(); saveConfig(); }
    public static void toggleHudShowChunkActivity() { config.hudShowChunkActivity = !isHudShowChunkActivity(); saveConfig(); }
    public static void toggleHudShowWorld() { config.hudShowWorld = !config.hudShowWorld; saveConfig(); }
    public static void toggleHudShowDiskIo() { config.hudShowDiskIo = !isHudShowDiskIo(); saveConfig(); }
    public static void toggleHudShowInputLatency() { config.hudShowInputLatency = !isHudShowInputLatency(); saveConfig(); }
    public static void toggleHudShowSession() { config.hudShowSession = !config.hudShowSession; saveConfig(); }
    public static void toggleHudShowFpsRateOfChange() { config.hudShowFpsRateOfChange = !config.hudShowFpsRateOfChange; saveConfig(); }
    public static void toggleHudShowFrameRateOfChange() { config.hudShowFrameRateOfChange = !config.hudShowFrameRateOfChange; saveConfig(); }
    public static void toggleHudShowTickRateOfChange() { config.hudShowTickRateOfChange = !config.hudShowTickRateOfChange; saveConfig(); }
    public static void toggleHudShowUtilizationRateOfChange() { config.hudShowUtilizationRateOfChange = !config.hudShowUtilizationRateOfChange; saveConfig(); }
    public static void toggleHudShowWorldRateOfChange() { config.hudShowWorldRateOfChange = !config.hudShowWorldRateOfChange; saveConfig(); }
    public static void toggleHudShowVramRateOfChange() { config.hudShowVramRateOfChange = !isHudShowVramRateOfChange(); saveConfig(); }
    public static void toggleHudShowNetworkRateOfChange() { config.hudShowNetworkRateOfChange = !isHudShowNetworkRateOfChange(); saveConfig(); }
    public static void toggleHudShowChunkActivityRateOfChange() { config.hudShowChunkActivityRateOfChange = !isHudShowChunkActivityRateOfChange(); saveConfig(); }
    public static void toggleHudShowDiskIoRateOfChange() { config.hudShowDiskIoRateOfChange = !isHudShowDiskIoRateOfChange(); saveConfig(); }
    public static void toggleHudShowInputLatencyRateOfChange() { config.hudShowInputLatencyRateOfChange = !isHudShowInputLatencyRateOfChange(); saveConfig(); }
    public static void toggleHudShowZeroRateOfChange() { config.hudShowZeroRateOfChange = !config.hudShowZeroRateOfChange; saveConfig(); }
    public static void toggleHudAutoFocusAlertRow() { config.hudAutoFocusAlertRow = !isHudAutoFocusAlertRow(); saveConfig(); }
    public static void toggleHudBudgetColorMode() { config.hudBudgetColorMode = !isHudBudgetColorMode(); saveConfig(); }
    public static void cycleHudTriggerMode() { config.hudTriggerMode = getHudTriggerMode().next().name(); saveConfig(); }

    public static boolean isTasksColumnVisible(String key) { return isColumnVisible(config.tasksColumns, key); }
    public static boolean isGpuColumnVisible(String key) { return isColumnVisible(config.gpuColumns, key); }
    public static boolean isMemoryColumnVisible(String key) { return isColumnVisible(config.memoryColumns, key); }

    public static String getTasksSearch() { return config.tasksSearch == null ? "" : config.tasksSearch; }
    public static String getGpuSearch() { return config.gpuSearch == null ? "" : config.gpuSearch; }
    public static String getMemorySearch() { return config.memorySearch == null ? "" : config.memorySearch; }
    public static String getStartupSearch() { return config.startupSearch == null ? "" : config.startupSearch; }
    public static String getGlobalSearch() { return config.globalSearch == null ? "" : config.globalSearch; }
    public static String getTaskSort() { return config.taskSort == null || config.taskSort.isBlank() ? "CPU" : config.taskSort; }
    public static boolean isTaskSortDescending() { return config.taskSortDescending; }
    public static String getGpuSort() { return config.gpuSort == null || config.gpuSort.isBlank() ? "EST_GPU" : config.gpuSort; }
    public static boolean isGpuSortDescending() { return config.gpuSortDescending; }
    public static String getMemorySort() { return config.memorySort == null || config.memorySort.isBlank() ? "MEMORY_MB" : config.memorySort; }
    public static boolean isMemorySortDescending() { return config.memorySortDescending; }
    public static String getStartupSort() { return config.startupSort == null || config.startupSort.isBlank() ? "ACTIVE" : config.startupSort; }
    public static boolean isStartupSortDescending() { return config.startupSortDescending; }
    public static boolean isTaskEffectiveView() { return config.taskEffectiveView; }
    public static boolean isTaskShowSharedRows() { return config.taskShowSharedRows; }
    public static boolean isGpuEffectiveView() { return config.gpuEffectiveView; }
    public static boolean isGpuShowSharedRows() { return config.gpuShowSharedRows; }
    public static boolean isMemoryEffectiveView() { return config.memoryEffectiveView; }
    public static boolean isMemoryShowSharedRows() { return config.memoryShowSharedRows; }

    public static void setTasksSearch(String value) { config.tasksSearch = value == null ? "" : value; saveConfig(); }
    public static void setGpuSearch(String value) { config.gpuSearch = value == null ? "" : value; saveConfig(); }
    public static void setMemorySearch(String value) { config.memorySearch = value == null ? "" : value; saveConfig(); }
    public static void setStartupSearch(String value) { config.startupSearch = value == null ? "" : value; saveConfig(); }
    public static void setGlobalSearch(String value) { config.globalSearch = value == null ? "" : value; saveConfig(); }
    public static void setTaskSortState(String sort, boolean descending) { config.taskSort = sort; config.taskSortDescending = descending; saveConfig(); }
    public static void setGpuSortState(String sort, boolean descending) { config.gpuSort = sort; config.gpuSortDescending = descending; saveConfig(); }
    public static void setMemorySortState(String sort, boolean descending) { config.memorySort = sort; config.memorySortDescending = descending; saveConfig(); }
    public static void setStartupSortState(String sort, boolean descending) { config.startupSort = sort; config.startupSortDescending = descending; saveConfig(); }
    public static void setTaskAttributionView(boolean effectiveView, boolean showSharedRows) { config.taskEffectiveView = effectiveView; config.taskShowSharedRows = showSharedRows; saveConfig(); }
    public static void setGpuAttributionView(boolean effectiveView, boolean showSharedRows) { config.gpuEffectiveView = effectiveView; config.gpuShowSharedRows = showSharedRows; saveConfig(); }
    public static void setMemoryAttributionView(boolean effectiveView, boolean showSharedRows) { config.memoryEffectiveView = effectiveView; config.memoryShowSharedRows = showSharedRows; saveConfig(); }

    public static void toggleTasksColumn(String key) { config.tasksColumns = toggleColumn(config.tasksColumns, key); saveConfig(); }
    public static void toggleGpuColumn(String key) { config.gpuColumns = toggleColumn(config.gpuColumns, key); saveConfig(); }
    public static void toggleMemoryColumn(String key) { config.memoryColumns = toggleColumn(config.memoryColumns, key); saveConfig(); }

    public static boolean getOnlyProfileWhenOpen() {
        return getCaptureMode() == ProfilerManager.CaptureMode.OPEN_ONLY;
    }

    public static void setOnlyProfileWhenOpen(boolean value) {
        setCaptureMode(value ? ProfilerManager.CaptureMode.OPEN_ONLY : ProfilerManager.CaptureMode.PASSIVE_LIGHTWEIGHT);
    }

    public static synchronized void loadConfig() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH);
                config = GSON.fromJson(json, ConfigData.class);
                if (config == null) {
                    config = new ConfigData();
                }
                migrateLegacyFields();
            } catch (IOException e) {
                e.printStackTrace();
                config = new ConfigData();
            }
        } else {
            saveConfig();
        }
    }

    public static synchronized void saveConfig() {
        try {
            String json = GSON.toJson(config);
            Files.writeString(CONFIG_PATH, json);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String normalizeColorHex(String value, int fallback) {
        if (value == null) {
            return String.format("#%06X", fallback & 0xFFFFFF);
        }
        String trimmed = value.trim().toUpperCase();
        if (trimmed.startsWith("#")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.length() == 6 && trimmed.matches("[0-9A-F]{6}")) {
            return "#" + trimmed;
        }
        return String.format("#%06X", fallback & 0xFFFFFF);
    }

    private static int parseColor(String value, int fallback) {
        String normalized = normalizeColorHex(value, fallback);
        return 0xFF000000 | Integer.parseInt(normalized.substring(1), 16);
    }

    private static boolean isColumnVisible(String csv, String key) {
        String source = csv == null || csv.isBlank() ? key : csv;
        for (String part : source.split(",")) {
            if (part.trim().equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    private static String toggleColumn(String csv, String key) {
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        if (csv != null && !csv.isBlank()) {
            for (String part : csv.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isBlank()) {
                    values.add(trimmed);
                }
            }
        }
        if (!values.remove(key)) {
            values.add(key);
        }
        return String.join(",", values);
    }

    private static int cycleIntOption(int current, int[] options) {
        for (int i = 0; i < options.length; i++) {
            if (options[i] == current) {
                return options[(i + 1) % options.length];
            }
        }
        return options[0];
    }

    private static void migrateLegacyFields() {
        if (config.captureMode == null || config.captureMode.isBlank()) {
            config.captureMode = config.onlyProfileWhenOpen ? ProfilerManager.CaptureMode.OPEN_ONLY.name() : ProfilerManager.CaptureMode.PASSIVE_LIGHTWEIGHT.name();
        }
        if (config.hudPosition == null || config.hudPosition.isBlank()) {
            config.hudPosition = HudPosition.TOP_LEFT.name();
        }
        if (config.hudLayoutMode == null || config.hudLayoutMode.isBlank()) {
            config.hudLayoutMode = HudLayoutMode.SINGLE_COLUMN.name();
        }
        if (config.hudTriggerMode == null || config.hudTriggerMode.isBlank()) {
            config.hudTriggerMode = config.hudSpikeOnly ? HudTriggerMode.SPIKES_ONLY.name() : HudTriggerMode.ALWAYS.name();
        }
        if (config.hudPreset == null || config.hudPreset.isBlank()) {
            config.hudPreset = HudPreset.COMPACT.name();
        }
        if (config.hudConfigMode == null || config.hudConfigMode.isBlank()) {
            config.hudConfigMode = HudConfigMode.PRESET.name();
        }
        if (config.sessionDurationSeconds <= 0) {
            config.sessionDurationSeconds = 30;
        }
        if (config.metricsUpdateIntervalMs <= 0) {
            config.metricsUpdateIntervalMs = 500;
        }
        if (config.profilerUpdateDelayMs <= 0) {
            config.profilerUpdateDelayMs = 500;
        }
        if (config.hudFpsDisplayDelayMs <= 0) {
            config.hudFpsDisplayDelayMs = 250;
        }
        if (config.hudMemoryDisplayDelayMs <= 0) {
            config.hudMemoryDisplayDelayMs = 500;
        }
        if (config.hudTransparencyPercent <= 0) {
            config.hudTransparencyPercent = 100;
        }
        if (config.frameBudgetTargetFps <= 0) {
            config.frameBudgetTargetFps = 60;
        }
        if (config.performanceAlertFrameThresholdMs <= 0) {
            config.performanceAlertFrameThresholdMs = 25;
        }
        if (config.performanceAlertServerThresholdMs <= 0) {
            config.performanceAlertServerThresholdMs = 20;
        }
        if (config.performanceAlertConsecutiveTicks <= 0) {
            config.performanceAlertConsecutiveTicks = 3;
        }
        if (config.tasksColumns == null || config.tasksColumns.isBlank()) {
            config.tasksColumns = "cpu,threads,samples,invokes";
        }
        if (config.gpuColumns == null || config.gpuColumns.isBlank()) {
            config.gpuColumns = "pct,threads,gpums,rsamples";
        }
        if (config.memoryColumns == null || config.memoryColumns.isBlank()) {
            config.memoryColumns = "classes,mb,pct";
        }
        if (config.tasksSearch == null) config.tasksSearch = "";
        if (config.gpuSearch == null) config.gpuSearch = "";
        if (config.memorySearch == null) config.memorySearch = "";
        if (config.startupSearch == null) config.startupSearch = "";
        if (config.globalSearch == null) config.globalSearch = "";
        if (config.taskSort == null || config.taskSort.isBlank()) config.taskSort = "CPU";
        if (config.gpuSort == null || config.gpuSort.isBlank()) config.gpuSort = "EST_GPU";
        if (config.memorySort == null || config.memorySort.isBlank()) config.memorySort = "MEMORY_MB";
        if (config.startupSort == null || config.startupSort.isBlank()) config.startupSort = "ACTIVE";
        if ((config.cpuGraphColor == null || config.cpuGraphColor.isBlank()) && config.cpuIntelColor != null) config.cpuGraphColor = config.cpuIntelColor;
        if ((config.gpuGraphColor == null || config.gpuGraphColor.isBlank()) && config.gpuNvidiaColor != null) config.gpuGraphColor = config.gpuNvidiaColor;
        config.cpuGraphColor = normalizeColorHex(config.cpuGraphColor, 0xFF5EA9FF);
        config.gpuGraphColor = normalizeColorHex(config.gpuGraphColor, 0xFF77DD77);
        config.worldEntityGraphColor = normalizeColorHex(config.worldEntityGraphColor, 0xFFFFC857);
        config.worldLoadedChunkGraphColor = normalizeColorHex(config.worldLoadedChunkGraphColor, 0xFF70C7A7);
        config.worldRenderedChunkGraphColor = normalizeColorHex(config.worldRenderedChunkGraphColor, 0xFF5EA9FF);
        saveConfig();
    }

    private static class ConfigData {
        public boolean onlyProfileWhenOpen = true;
        public String captureMode = ProfilerManager.CaptureMode.OPEN_ONLY.name();
        public boolean hudEnabled = false;
        public String hudPosition = HudPosition.TOP_LEFT.name();
        public String hudLayoutMode = HudLayoutMode.SINGLE_COLUMN.name();
        public int sessionDurationSeconds = 30;
        public int metricsUpdateIntervalMs = 500;
        public int profilerUpdateDelayMs = 500;
        public int hudFpsDisplayDelayMs = 250;
        public int hudMemoryDisplayDelayMs = 500;
        public int hudTransparencyPercent = 100;
        public int frameBudgetTargetFps = 60;
        public boolean performanceAlertsEnabled = true;
        public boolean performanceAlertChatEnabled = true;
        public int performanceAlertFrameThresholdMs = 25;
        public int performanceAlertServerThresholdMs = 20;
        public int performanceAlertConsecutiveTicks = 3;
        public boolean hudShowFps = true;
        public boolean hudShowFrame = true;
        public boolean hudShowTicks = true;
        public boolean hudShowUtilization = true;
        public boolean hudShowTemperatures = true;
        public boolean hudShowParallelism = false;
        public boolean hudShowLogic = true;
        public boolean hudShowBackground = true;
        public boolean hudShowFrameBudget = true;
        public boolean hudShowMemory = true;
        public boolean hudShowMemoryAllocationRate = true;
        public boolean hudShowVram = true;
        public boolean hudShowNetwork = false;
        public boolean hudShowChunkActivity = false;
        public boolean hudShowWorld = true;
        public boolean hudShowDiskIo = false;
        public boolean hudShowInputLatency = false;
        public boolean hudShowSession = true;
        public boolean hudShowFpsRateOfChange = true;
        public boolean hudShowFrameRateOfChange = true;
        public boolean hudShowTickRateOfChange = true;
        public boolean hudShowUtilizationRateOfChange = true;
        public boolean hudShowWorldRateOfChange = true;
        public boolean hudShowVramRateOfChange = true;
        public boolean hudShowNetworkRateOfChange = true;
        public boolean hudShowChunkActivityRateOfChange = true;
        public boolean hudShowDiskIoRateOfChange = true;
        public boolean hudShowInputLatencyRateOfChange = true;
        public boolean hudShowZeroRateOfChange = false;
        public boolean hudAutoFocusAlertRow = true;
        public boolean hudBudgetColorMode = true;
        public boolean hudSpikeOnly = false;
        public String hudTriggerMode = HudTriggerMode.ALWAYS.name();
        public String hudPreset = HudPreset.COMPACT.name();
        public String hudConfigMode = HudConfigMode.PRESET.name();
        public boolean hudExpandedOnWarning = true;
        public String tasksColumns = "cpu,threads,samples,invokes";
        public String gpuColumns = "pct,threads,gpums,rsamples";
        public String memoryColumns = "classes,mb,pct";
        public String tasksSearch = "";
        public String gpuSearch = "";
        public String memorySearch = "";
        public String startupSearch = "";
        public String globalSearch = "";
        public String taskSort = "CPU";
        public boolean taskSortDescending = true;
        public String gpuSort = "EST_GPU";
        public boolean gpuSortDescending = true;
        public String memorySort = "MEMORY_MB";
        public boolean memorySortDescending = true;
        public String startupSort = "ACTIVE";
        public boolean startupSortDescending = true;
        public boolean taskEffectiveView = true;
        public boolean taskShowSharedRows = false;
        public boolean gpuEffectiveView = true;
        public boolean gpuShowSharedRows = false;
        public boolean memoryEffectiveView = true;
        public boolean memoryShowSharedRows = false;
        public String cpuGraphColor = "#5EA9FF";
        public String gpuGraphColor = "#77DD77";
        public String worldEntityGraphColor = "#FFC857";
        public String worldLoadedChunkGraphColor = "#70C7A7";
        public String worldRenderedChunkGraphColor = "#5EA9FF";
        public String cpuIntelColor = "#5EA9FF";
        public String cpuAmdColor = "#FF6B6B";
        public String gpuNvidiaColor = "#77DD77";
        public String gpuAmdColor = "#FF6B6B";
        public String gpuIntegratedColor = "#5EA9FF";
    }
}



