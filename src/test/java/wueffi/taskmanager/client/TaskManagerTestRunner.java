package wueffi.taskmanager.client;

public final class TaskManagerTestRunner {

    private TaskManagerTestRunner() {
    }

    public static void main(String[] args) {
        FrameTimelineProfilerTests.run();
        ProfilerManagerTests.run();
        CollectorMathTests.run();
        AttributionInsightsTests.run();
        AttributionModelBuilderTests.run();
        BoundedMapsTests.run();
        CpuSamplingProfilerTests.run();
        ConfigManagerMigrationTests.run();
        RuleEngineTests.run();
        RenderPhaseProfilerTests.run();
        ShaderPassProfilerTests.run();
        wueffi.taskmanager.client.util.GpuTimerTests.run();
        SystemMetricsProfilerTests.run();
        TaskManagerScreenLayoutTests.run();
        HudOverlayRendererTests.run();
        WindowsTelemetryBridgeTests.run();
        HardwareInfoResolverTests.run();
        SessionExporterTests.run();
        System.out.println("TaskManager tests passed.");
    }
}
