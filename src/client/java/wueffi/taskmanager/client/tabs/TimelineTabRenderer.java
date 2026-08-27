package wueffi.taskmanager.client;

import net.minecraft.client.gui.DrawContext;

import java.util.Locale;

final class TimelineTabRenderer {

    private TimelineTabRenderer() {
    }

    static void render(TaskManagerScreen screen, DrawContext ctx, int x, int y, int w, int h) {
        var textRenderer = screen.uiTextRenderer();
        screen.beginFullPageScissor(ctx, x, y, w, h);
        int graphWidth = screen.getPreferredGraphWidth(w);
        int left = x + Math.max(TaskManagerScreen.PADDING, (w - graphWidth) / 2);
        int top = screen.getFullPageScrollTop(y);
        FrameTimelineProfiler frames = FrameTimelineProfiler.getInstance();
        ProfilerManager manager = ProfilerManager.getInstance();
        ProfilerManager.SessionBaseline baseline = manager.getSessionBaseline();
        ProfilerManager.SessionDelta delta = manager.compareToBaseline(baseline);
        top = screen.renderSectionHeader(ctx, left, top, "Timeline", "Frame pacing, FPS lows, jitter, and spike context over the live capture window.");
        screen.drawTopChip(ctx, left, top, 96, 16, baseline != null);
        ctx.drawText(textRenderer, "Set Baseline", left + 10, top + 4, baseline != null ? TaskManagerScreen.TEXT_PRIMARY : TaskManagerScreen.TEXT_DIM, false);
        screen.drawTopChip(ctx, left + 102, top, 112, 16, false);
        ctx.drawText(textRenderer, "Import Export", left + 114, top + 4, TaskManagerScreen.TEXT_DIM, false);
        screen.drawTopChip(ctx, left + 220, top, 74, 16, baseline != null);
        ctx.drawText(textRenderer, "Clear", left + 244, top + 4, baseline != null ? TaskManagerScreen.TEXT_PRIMARY : TaskManagerScreen.TEXT_DIM, false);
        top += 24;
        if (baseline != null && delta != null) {
            screen.drawMetricRow(ctx, left, top, graphWidth, "Compare", String.format(Locale.ROOT,
                    "%s | FPS %s | 1%% low %s | MSPT %s | heap %s",
                    baseline.label(),
                    formatSigned(delta.fpsChange(), false, "fps"),
                    formatSigned(delta.onePercentLowFpsChange(), false, "fps"),
                    formatSigned(delta.msptChange(), true, "ms"),
                    formatSigned(delta.heapChangeMb(), true, "MB")));
            top += 18;
            screen.drawMetricRow(ctx, left, top, graphWidth, "Top Deltas", "CPU " + formatTopDelta(delta.cpuDeltaByMod()) + " | GPU " + formatTopDelta(delta.gpuDeltaByMod()) + " | Memory " + formatTopDelta(delta.memoryDeltaMbByMod()));
            top += 24;
        }
        screen.drawMetricRow(ctx, left, top, graphWidth, "FPS", String.format(Locale.ROOT, "current %.1f | avg %.1f | 1%% low %.1f | 0.1%% low %.1f", frames.getCurrentFps(), frames.getAverageFps(), frames.getOnePercentLowFps(), frames.getPointOnePercentLowFps()));
        top += 24;
        screen.renderSeriesGraph(ctx, left, top, graphWidth, 126, frames.getOrderedFrameMsHistory(), frames.getOrderedSelfCostMsHistory(), "Frame Timeline", "ms/frame", TaskManagerScreen.ACCENT_YELLOW, 0xFFFF8A3D, frames.getHistorySpanSeconds(), true);
        top += 144;
        screen.renderSeriesGraph(ctx, left, top, graphWidth, 126, frames.getOrderedFpsHistory(), null, "FPS Timeline", "fps", TaskManagerScreen.INTEL_COLOR, 0, frames.getHistorySpanSeconds());
        top += 144;
        screen.drawMetricRow(ctx, left, top, graphWidth, "Profiler Self-Cost", String.format(Locale.ROOT, "avg %.2f ms | max %.2f ms", frames.getSelfCostAvgMs(), frames.getSelfCostMaxMs()));
        top += 18;
        double stutterScore = frames.getStutterScore();
        screen.drawMetricRow(ctx, left, top, graphWidth, "Jitter Variance", String.format(Locale.ROOT, "stddev %.2f ms | variance %.2f ms^2 | stutter %.1f", frames.getFrameStdDevMs(), frames.getFrameVarianceMs(), stutterScore));
        top += 18;
        screen.drawMetricRow(ctx, left, top, graphWidth, "Stutter Score", String.format(Locale.ROOT, "%.1f | %s", stutterScore, screen.stutterBand(stutterScore)));
        top += 18;
        ctx.drawText(textRenderer, "Stutter guide", left, top, TaskManagerScreen.TEXT_PRIMARY, false);
        top += 14;
        top = screen.renderWrappedText(ctx, left + 6, top, graphWidth - 12, "0-5 Excellent | 5-10 Good | 10-20 Noticeable | 20-35 Bad | 35+ Severe", screen.stutterBandColor(stutterScore)) + 4;
        top = screen.renderWrappedText(ctx, left + 6, top, graphWidth - 12, "Higher stutter scores mean frame pacing is less consistent even if average FPS still looks healthy.", TaskManagerScreen.TEXT_DIM) + 6;
        screen.drawMetricRow(ctx, left, top, graphWidth, "Frame / Tick Breakdown", String.format(Locale.ROOT, "frame %.2f ms | p95 %.2f | p99 %.2f | build %.2f | gpu %.2f | gpu p95 %.2f | mspt %.2f | mspt p95 %.2f | mspt p99 %.2f", frames.getLatestFrameNs() / 1_000_000.0, frames.getPercentileFrameNs(0.95) / 1_000_000.0, frames.getPercentileFrameNs(0.99) / 1_000_000.0, screen.snapshot.renderPhases().values().stream().mapToLong(RenderPhaseProfiler.PhaseSnapshot::cpuNanos).sum() / 1_000_000.0, screen.snapshot.renderPhases().values().stream().mapToLong(RenderPhaseProfiler.PhaseSnapshot::gpuNanos).sum() / 1_000_000.0, screen.percentileGpuFrameLabel(), TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0, TickProfiler.getInstance().getServerTickP95Ns() / 1_000_000.0, TickProfiler.getInstance().getServerTickP99Ns() / 1_000_000.0));
        top += 22;
        screen.drawMetricRow(ctx, left, top, graphWidth, "Frame Histogram", screen.formatFrameHistogram(frames.getFrameTimeHistogram()));
        top += 22;
        screen.renderSpikeInspector(ctx, left, top, graphWidth);
        ctx.disableScissor();
    }

    private static String formatSigned(double value, boolean lowerIsBetter, String units) {
        String sign = value > 0 ? "+" : "";
        String direction = lowerIsBetter ? (value <= 0 ? "better" : "worse") : (value >= 0 ? "better" : "worse");
        return String.format(Locale.ROOT, "%s%.1f %s %s", sign, value, units, direction);
    }

    private static String formatTopDelta(java.util.Map<String, Double> deltas) {
        if (deltas == null || deltas.isEmpty()) {
            return "no change";
        }
        return deltas.entrySet().stream()
                .sorted((a, b) -> Double.compare(Math.abs(b.getValue()), Math.abs(a.getValue())))
                .limit(2)
                .map(entry -> entry.getKey() + " " + String.format(Locale.ROOT, "%+.1f", entry.getValue()))
                .reduce((left, right) -> left + " | " + right)
                .orElse("no change");
    }
}
