package wueffi.taskmanager.client;

import com.sun.management.OperatingSystemMXBean;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Union;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Pdh;
import com.sun.jna.platform.win32.PdhUtil;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.W32APIOptions;
import com.sun.jna.win32.StdCallLibrary;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class Sensors {

    record Sample(
            boolean active,
            String counterSource,
            String sensorSource,
            String sensorErrorCode,
            String cpuTemperatureProvider,
            String gpuTemperatureProvider,
            String gpuHotSpotTemperatureProvider,
            double cpuCoreLoadPercent,
            double gpuCoreLoadPercent,
            double gpuTemperatureC,
            double gpuHotSpotTemperatureC,
            double cpuTemperatureC
    ) {
        static Sample empty() {
            return new Sample(false, "Unavailable", "Unavailable", "No bridge data", "Unavailable", "Unavailable", "Unavailable", -1.0, -1.0, -1.0, -1.0, -1.0);
        }
    }

    private static final boolean IS_WINDOWS;

    static {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        IS_WINDOWS = os.contains("win");
    }

    private static final int FILE_MAP_READ = 0x0004;
    private static final int HWINFOMAP_BYTES = 512 * 1024;
    private static final int CORETEMP_BYTES = 4096;
    private final GpuEngineSampler gpuEngineSampler;

    Sensors() {
        this.gpuEngineSampler = IS_WINDOWS ? new WindowsGpuEngineSampler() : new DummyGpuEngineSampler();
    }

    Sample sample(String activeRenderer, String activeVendor) {
        if (!IS_WINDOWS) {
            return Sample.empty();
        }

        SensorAccumulator sensors = new SensorAccumulator(activeRenderer, activeVendor);
        double cpuLoad = sampleCpuLoad();
        double pdhGpuLoad = gpuEngineSampler.sample(sensors);
        readHwInfoSharedMemory(sensors);
        if (sensors.gpuLoad < 0.0 && pdhGpuLoad >= 0.0) {
            sensors.acceptFallbackGpuLoad("Windows PDH GPU Counters", "GPU Engine / Utilization Percentage", pdhGpuLoad);
        }
        if (sensors.cpuTemperature < 0.0) {
            readCoreTempSharedMemory(sensors);
        }
        String counterSource = buildCounterSource(cpuLoad, sensors.gpuLoad, pdhGpuLoad);
        return new Sample(
                cpuLoad >= 0.0 || sensors.gpuLoad >= 0.0 || sensors.cpuTemperature >= 0.0 || sensors.gpuTemperature >= 0.0,
                counterSource,
                sensors.buildSensorSource(),
                sensors.buildErrorSummary(),
                sensors.cpuTemperatureProvider,
                sensors.gpuTemperatureProvider,
                sensors.gpuHotSpotTemperatureProvider,
                cpuLoad,
                sensors.gpuLoad,
                sensors.gpuTemperature,
                sensors.gpuHotSpotTemperature,
                sensors.cpuTemperature
        );
    }

    private String buildCounterSource(double cpuLoad, double gpuLoad, double pdhGpuLoad) {
        List<String> parts = new ArrayList<>();
        if (cpuLoad >= 0.0) {
            parts.add("JVM MXBean");
        }
        if (pdhGpuLoad >= 0.0) {
            parts.add("Windows PDH GPU Counters");
        } else if (gpuLoad >= 0.0) {
            parts.add("HWiNFO Shared Memory");
        }
        return parts.isEmpty() ? "Unavailable" : String.join(" + ", parts);
    }

    private double sampleCpuLoad() {
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof OperatingSystemMXBean sunBean) {
                double value = sunBean.getCpuLoad();
                if (Double.isFinite(value) && value >= 0.0) {
                    return Math.max(0.0, Math.min(100.0, value * 100.0));
                }
            }
        } catch (Throwable ignored) {
        }
        return -1.0;
    }

    private void readHwInfoSharedMemory(SensorAccumulator sensors) {
        if (!IS_WINDOWS) return;
        String[] mappingNames = {"Global\\HWiNFO_SENS_SM2", "HWiNFO_SENS_SM2"};
        for (String mappingName : mappingNames) {
            sensors.addAttempt("HWiNFO Shared Memory");
            byte[] bytes = readMapping(mappingName, HWINFOMAP_BYTES);
            if (bytes == null || bytes.length < 40) {
                continue;
            }
            try {
                ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                if (!"HWiN".equals(readAscii(buffer, 0, 4))) {
                    continue;
                }
                int readingOffset = buffer.getInt(28);
                int readingSize = buffer.getInt(32);
                int readingCount = buffer.getInt(36);
                if (readingOffset <= 0 || readingSize <= 0 || readingCount <= 0) {
                    continue;
                }
                int maxCount = Math.min(readingCount, 4096);
                for (int i = 0; i < maxCount; i++) {
                    int entryOffset = readingOffset + (i * readingSize);
                    if (entryOffset < 0 || entryOffset + 316 > bytes.length) {
                        break;
                    }
                    String labelOrig = readAscii(buffer, entryOffset + 12, 128);
                    String labelUser = readAscii(buffer, entryOffset + 140, 128);
                    String unit = readAscii(buffer, entryOffset + 268, 16);
                    double value = buffer.getDouble(entryOffset + 284);
                    String label = labelUser.isBlank() ? labelOrig : labelUser;
                    if (label.isBlank() || !Double.isFinite(value)) {
                        continue;
                    }
                    sensors.acceptSensor("HWiNFO Shared Memory", label, label, label, unit, value);
                }
                return;
            } catch (Throwable error) {
                sensors.addError("HWiNFO Shared Memory", error.getMessage());
            }
        }
    }

    private void readCoreTempSharedMemory(SensorAccumulator sensors) {
        if (!IS_WINDOWS) return;
        String[] mappingNames = {"CoreTempMappingObjectEx", "CoreTempMappingObject"};
        for (String mappingName : mappingNames) {
            sensors.addAttempt("Core Temp Shared Memory");
            byte[] bytes = readMapping(mappingName, CORETEMP_BYTES);
            if (bytes == null || bytes.length < 2688) {
                continue;
            }
            try {
                ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                long coreCount = Integer.toUnsignedLong(buffer.getInt(1536));
                int safeCoreCount = (int) Math.min(coreCount, 256L);
                double maxTemp = -1.0;
                int tempOffset = 1544;
                for (int i = 0; i < safeCoreCount && tempOffset + 4 <= bytes.length; i++) {
                    float tempValue = buffer.getFloat(tempOffset);
                    if (Float.isFinite(tempValue) && tempValue > 0.0f) {
                        maxTemp = Math.max(maxTemp, tempValue);
                    }
                    tempOffset += 4;
                }
                if (maxTemp <= 0.0) {
                    continue;
                }
                int fahrenheit = Byte.toUnsignedInt(bytes[2684]);
                int deltaToTjMax = Byte.toUnsignedInt(bytes[2685]);
                long tjMax = Integer.toUnsignedLong(buffer.getInt(1024));
                if (deltaToTjMax != 0 && tjMax > 0) {
                    maxTemp = tjMax - maxTemp;
                }
                if (fahrenheit != 0) {
                    maxTemp = (maxTemp - 32.0) * 5.0 / 9.0;
                }
                if (maxTemp > 0.0) {
                    sensors.acceptCpuTemperature("Core Temp Shared Memory", mappingName + " / max-core", maxTemp);
                    return;
                }
            } catch (Throwable error) {
                sensors.addError("Core Temp Shared Memory", error.getMessage());
            }
        }
    }

    private byte[] readMapping(String mappingName, int size) {
        try (MappedView mappedView = MappedView.open(mappingName, size)) {
            if (mappedView == null || mappedView.view() == null) {
                return null;
            }
            return mappedView.view().getByteArray(0, size);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String readAscii(ByteBuffer buffer, int offset, int length) {
        if (offset < 0 || length <= 0 || offset + length > buffer.capacity()) {
            return "";
        }
        byte[] raw = new byte[length];
        int originalPosition = buffer.position();
        buffer.position(offset);
        buffer.get(raw);
        buffer.position(originalPosition);
        int end = 0;
        while (end < raw.length && raw[end] != 0) {
            end++;
        }
        return new String(raw, 0, end, StandardCharsets.US_ASCII).trim();
    }

    private static final class SensorAccumulator {
        private final String normalizedTarget;
        private final List<String> attempts = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private String cpuTemperatureProvider = "Unavailable";
        private String gpuTemperatureProvider = "Unavailable";
        private String gpuHotSpotTemperatureProvider = "Unavailable";
        private String cpuTemperatureMatch = "none";
        private String gpuTemperatureMatch = "none";
        private String gpuHotSpotTemperatureMatch = "none";
        private boolean preferredGpuMatchFound;
        private double cpuTemperature = -1.0;
        private double gpuTemperature = -1.0;
        private double gpuHotSpotTemperature = -1.0;
        private double gpuLoad = -1.0;

        private SensorAccumulator(String activeRenderer, String activeVendor) {
            this.normalizedTarget = normalizeName(activeRenderer + " " + activeVendor);
        }

        private void addAttempt(String attempt) {
            if (!attempts.contains(attempt)) {
                attempts.add(attempt);
            }
        }

        private void addError(String provider, String error) {
            String message = provider + ": " + ((error == null || error.isBlank()) ? "Unknown error" : error);
            if (!errors.contains(message)) {
                errors.add(message);
            }
        }

        private void acceptSensor(String origin, String hardwareName, String sensorName, String sensorIdentifier, String unit, double value) {
            String normalizedUnit = unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT);
            String search = (hardwareName + " " + sensorName + " " + sensorIdentifier).toLowerCase(Locale.ROOT);
            if (normalizedUnit.contains("c")) {
                boolean cpuMatch = search.matches(".*(cpu package|package id|tctl|tdie|ccd|die|cpu core|core max|core average|processor|socket|ryzen|intel).*")
                        || ((hardwareName + " " + sensorName).toLowerCase(Locale.ROOT).matches(".*(cpu|processor).*") && sensorName.toLowerCase(Locale.ROOT).matches(".*(temperature|tdie|tctl|package|cpu).*"))
                        || sensorName.equalsIgnoreCase("cpu");
                boolean gpuMatch = search.matches(".*(gpu temperature|gpu core|hot spot|hotspot|junction|junction temperature|edge|graphics|mem junction|memory junction|radeon|nvidia|intel graphics|hot point).*")
                        || ((hardwareName + " " + sensorName).toLowerCase(Locale.ROOT).matches(".*(gpu|graphics|radeon|nvidia|intel).*") && sensorName.toLowerCase(Locale.ROOT).matches(".*(temperature|edge|junction|hot).*"));
                if (cpuMatch && (cpuTemperature < 0.0 || value > cpuTemperature)) {
                    acceptCpuTemperature(origin, hardwareName + " / " + sensorName, value);
                }
                if (gpuMatch) {
                    boolean hotSpotMatch = search.matches(".*(hot spot|hotspot|junction|junction temperature|mem junction|memory junction|hot point).*");
                    boolean preferred = isPreferredGpu(hardwareName, sensorName, sensorIdentifier);
                    if (preferred) {
                        if (hotSpotMatch) {
                            if (gpuHotSpotTemperature < 0.0 || value > gpuHotSpotTemperature) {
                                gpuHotSpotTemperature = roundOneDecimal(value);
                                gpuHotSpotTemperatureProvider = origin;
                                gpuHotSpotTemperatureMatch = hardwareName + " / " + sensorName;
                            }
                        } else if (!preferredGpuMatchFound || gpuTemperature < 0.0 || value > gpuTemperature) {
                            gpuTemperature = roundOneDecimal(value);
                            gpuTemperatureProvider = origin;
                            gpuTemperatureMatch = hardwareName + " / " + sensorName;
                        }
                        preferredGpuMatchFound = true;
                    } else if (hotSpotMatch) {
                        if (gpuHotSpotTemperature < 0.0 || value > gpuHotSpotTemperature) {
                            gpuHotSpotTemperature = roundOneDecimal(value);
                            gpuHotSpotTemperatureProvider = origin;
                            gpuHotSpotTemperatureMatch = hardwareName + " / " + sensorName;
                        }
                    } else if (!preferredGpuMatchFound && (gpuTemperature < 0.0 || value > gpuTemperature)) {
                        gpuTemperature = roundOneDecimal(value);
                        gpuTemperatureProvider = origin;
                        gpuTemperatureMatch = hardwareName + " / " + sensorName;
                    }
                }
            }
            if (normalizedUnit.contains("%")) {
                boolean gpuLoadMatch = search.matches(".*(gpu core load|gpu usage|gpu utilization|gpu load|graphics engine|graphics usage|3d usage|d3d usage).*");
                if (gpuLoadMatch && value >= 0.0) {
                    boolean preferred = isPreferredGpu(hardwareName, sensorName, sensorIdentifier);
                    if (preferred) {
                        if (!preferredGpuMatchFound || gpuLoad < 0.0 || value > gpuLoad) {
                            gpuLoad = Math.max(0.0, Math.min(100.0, value));
                            preferredGpuMatchFound = true;
                            if ("Unavailable".equals(gpuTemperatureProvider)) {
                                gpuTemperatureProvider = origin;
                                gpuTemperatureMatch = hardwareName + " / " + sensorName;
                            }
                        }
                    } else if (!preferredGpuMatchFound && value > gpuLoad) {
                        gpuLoad = Math.max(0.0, Math.min(100.0, value));
                        if ("Unavailable".equals(gpuTemperatureProvider)) {
                            gpuTemperatureProvider = origin;
                            gpuTemperatureMatch = hardwareName + " / " + sensorName;
                        }
                    }
                }
            }
        }

        private void acceptCpuTemperature(String origin, String match, double value) {
            cpuTemperature = roundOneDecimal(value);
            cpuTemperatureProvider = origin;
            cpuTemperatureMatch = match;
        }

        private void acceptFallbackGpuLoad(String origin, String match, double value) {
            if (!Double.isFinite(value) || value < 0.0) {
                return;
            }
            gpuLoad = Math.max(0.0, Math.min(100.0, value));
            if ("Unavailable".equals(gpuTemperatureProvider)) {
                gpuTemperatureProvider = origin;
                gpuTemperatureMatch = match;
            }
        }

        private String buildSensorSource() {
            String attemptText = attempts.isEmpty() ? "" : " | Tried: " + String.join(", ", attempts);
            return "CPU: " + cpuTemperatureProvider + " [" + cpuTemperatureMatch + "]"
                    + " | GPU: " + gpuTemperatureProvider + " [" + gpuTemperatureMatch + "]"
                    + " | GPU Hot Spot: " + gpuHotSpotTemperatureProvider + " [" + gpuHotSpotTemperatureMatch + "]"
                    + attemptText;
        }

        private String buildErrorSummary() {
            return errors.isEmpty() ? "none" : String.join(" | ", errors);
        }

        private boolean isPreferredGpu(String hardwareName, String sensorName, String sensorIdentifier) {
            String candidate = normalizeName(hardwareName + " " + sensorName + " " + sensorIdentifier);
            if (normalizedTarget.isBlank() || candidate.isBlank()) {
                return false;
            }
            if (normalizedTarget.contains(candidate) || candidate.contains(normalizedTarget)) {
                return true;
            }
            int hits = 0;
            for (String token : normalizedTarget.split(" ")) {
                if (token.length() >= 3 && candidate.contains(token)) {
                    hits++;
                }
            }
            return hits >= 2;
        }

        private static double roundOneDecimal(double value) {
            return Math.round(value * 10.0) / 10.0;
        }

        private static String normalizeName(String value) {
            if (value == null || value.isBlank()) {
                return "";
            }
            return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        }
    }

    private static final class MappedView implements AutoCloseable {
        private final WinNT.HANDLE mapping;
        private final Pointer view;

        private MappedView(WinNT.HANDLE mapping, Pointer view) {
            this.mapping = mapping;
            this.view = view;
        }

        static MappedView open(String mappingName, int size) {
            WinNT.HANDLE mapping = Kernel32.INSTANCE.OpenFileMapping(FILE_MAP_READ, false, mappingName);
            if (mapping == null) {
                return null;
            }
            Pointer view = null;
            boolean success = false;
            try {
                view = Kernel32.INSTANCE.MapViewOfFile(mapping, FILE_MAP_READ, 0, 0, size);
                if (view == null) {
                    return null;
                }
                MappedView mappedView = new MappedView(mapping, view);
                success = true;
                return mappedView;
            } finally {
                if (!success) {
                    if (view != null) {
                        try { Kernel32.INSTANCE.UnmapViewOfFile(view); } catch (Throwable ignored) {}
                    }
                    try { Kernel32.INSTANCE.CloseHandle(mapping); } catch (Throwable ignored) {}
                }
            }
        }

        Pointer view() {
            return view;
        }

        @Override
        public void close() {
            if (view != null) {
                try { Kernel32.INSTANCE.UnmapViewOfFile(view); } catch (Throwable ignored) {}
            }
            if (mapping != null) {
                try { Kernel32.INSTANCE.CloseHandle(mapping); } catch (Throwable ignored) {}
            }
        }
    }

    private interface GpuEngineSampler {
        double sample(SensorAccumulator sensors);
    }

   private static final class WindowsGpuEngineSampler implements GpuEngineSampler {
        private static final long INSTANCE_REFRESH_MS = 5_000L;
        private WinNT.HANDLE queryHandle;
        private final List<WinNT.HANDLE> counterHandles = new ArrayList<>();
        private long lastRefreshAtMillis;
        private boolean primed;

        @Override
        public double sample(SensorAccumulator sensors) {
            synchronized (this) {
                sensors.addAttempt("Windows PDH GPU Counters");
                if (!ensureQuery(sensors)) {
                    return -1.0;
                }
                refreshCountersIfNeeded(sensors, false);
                if (counterHandles.isEmpty()) {
                    return -1.0;
                }
                int collectStatus = Pdh.INSTANCE.PdhCollectQueryData(queryHandle);
                if (collectStatus != 0) {
                    sensors.addError("Windows PDH GPU Counters", "Collect failed 0x" + Integer.toHexString(collectStatus));
                    refreshCountersIfNeeded(sensors, true);
                    return -1.0;
                }
                if (!primed) {
                    primed = true;
                    return -1.0;
                }
                double max = -1.0;
                for (WinNT.HANDLE counterHandle : counterHandles) {
                    double value = readCounterValue(counterHandle);
                    if (Double.isFinite(value) && value >= 0.0) {
                        max = Math.max(max, value);
                    }
                }
                return max >= 0.0 ? Math.max(0.0, Math.min(100.0, max)) : -1.0;
            }
        }

        private boolean ensureQuery(SensorAccumulator sensors) {
            if (queryHandle != null) {
                return true;
            }
            WinNT.HANDLEByReference queryRef = new WinNT.HANDLEByReference();
            int status = Pdh.INSTANCE.PdhOpenQuery(null, null, queryRef);
            if (status != 0) {
                sensors.addError("Windows PDH GPU Counters", "OpenQuery failed 0x" + Integer.toHexString(status));
                return false;
            }
            queryHandle = queryRef.getValue();
            return queryHandle != null;
        }

        private void refreshCountersIfNeeded(SensorAccumulator sensors, boolean force) {
            long now = System.currentTimeMillis();
            if (!force && !counterHandles.isEmpty() && now - lastRefreshAtMillis < INSTANCE_REFRESH_MS) {
                return;
            }
            clearCounters();
            lastRefreshAtMillis = now;
            primed = false;
            try {
                PdhUtil.PdhEnumObjectItems items = PdhUtil.PdhEnumObjectItems(null, null, "GPU Engine", Pdh.PERF_DETAIL_COSTLY);
                for (String instance : items.getInstances()) {
                    if (!isInterestingGpuEngine(instance)) {
                        continue;
                    }
                    WinNT.HANDLEByReference counterRef = new WinNT.HANDLEByReference();
                    String path = "\\GPU Engine(" + instance + ")\\Utilization Percentage";
                    int status = Pdh.INSTANCE.PdhAddEnglishCounter(queryHandle, path, null, counterRef);
                    if (status == 0 && counterRef.getValue() != null) {
                        counterHandles.add(counterRef.getValue());
                    }
                }
            } catch (Throwable error) {
                sensors.addError("Windows PDH GPU Counters", error.getMessage());
            }
        }

        private void clearCounters() {
            for (WinNT.HANDLE counterHandle : counterHandles) {
                try {
                    Pdh.INSTANCE.PdhRemoveCounter(counterHandle);
                } catch (Throwable ignored) {
                }
            }
            counterHandles.clear();
        }

        private boolean isInterestingGpuEngine(String instance) {
            if (instance == null || instance.isBlank()) {
                return false;
            }
            String normalized = instance.toLowerCase(Locale.ROOT);
            return normalized.contains("engtype_3d") || normalized.contains("engtype_compute_0") || normalized.contains("engtype_graphics");
        }

        private double readCounterValue(WinNT.HANDLE counterHandle) {
            WinDef.DWORDByReference counterType = new WinDef.DWORDByReference();
            PdhFormattedCounterValue formattedValue = new PdhFormattedCounterValue();
            int status = PdhExtra.INSTANCE.PdhGetFormattedCounterValue(counterHandle, Pdh.PDH_FMT_DOUBLE, counterType, formattedValue);
            if (status != 0) {
                return -1.0;
            }
            formattedValue.read();
            if (formattedValue.CStatus != 0) {
                return -1.0;
            }
            formattedValue.value.setType(double.class);
            formattedValue.value.read();
            double value = formattedValue.value.doubleValue;
            return Double.isFinite(value) ? value : -1.0;
        }
    }

    private static final class DummyGpuEngineSampler implements GpuEngineSampler {
        @Override
        public double sample(SensorAccumulator sensors) {
            return -1.0;
        }
    }

    private interface PdhExtra extends StdCallLibrary {
        PdhExtra INSTANCE = Native.load("Pdh", PdhExtra.class, W32APIOptions.DEFAULT_OPTIONS);

        int PdhGetFormattedCounterValue(WinNT.HANDLE hCounter, int dwFormat, WinDef.DWORDByReference lpdwType, PdhFormattedCounterValue pValue);
    }

    @Structure.FieldOrder({"CStatus", "value"})
    public static class PdhFormattedCounterValue extends Structure {
        public int CStatus;
        public ValueUnion value;

        public PdhFormattedCounterValue() {
            value = new ValueUnion();
        }
    }

    public static class ValueUnion extends Union {
        public int longValue;
        public double doubleValue;
        public long largeValue;
        public Pointer ansiStringValue;
        public Pointer wideStringValue;
    }
}
