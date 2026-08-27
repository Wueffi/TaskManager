package wueffi.taskmanager.client;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.ChunkPos;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class WorldTabRenderer {

    private WorldTabRenderer() {
    }

    static void render(TaskManagerScreen screen, DrawContext ctx, int x, int y, int w, int h) {
        var textRenderer = screen.uiTextRenderer();
        int left = x + TaskManagerScreen.PADDING;
        screen.beginFullPageScissor(ctx, x, y, w, h);
        TaskManagerScreen.LagMapLayout layout = screen.getLagMapLayout(y, w, h);
        screen.lastRenderedLagMapLayout = layout;
        int top = screen.getFullPageScrollTop(y);
        top = screen.renderSectionHeader(ctx, left, top, "World", "Chunk pressure, entity hotspots, and block-entity drilldown grouped into world-focused views.");
        int lagTabW = 76;
        int entitiesTabW = 70;
        int chunksTabW = 72;
        int blockTabW = 108;
        int tabX = left;
        screen.drawTopChip(ctx, tabX, layout.miniTabY(), lagTabW, 16, screen.worldMiniTab == TaskManagerScreen.WorldMiniTab.LAG_MAP);
        tabX += lagTabW + 6;
        screen.drawTopChip(ctx, tabX, layout.miniTabY(), entitiesTabW, 16, screen.worldMiniTab == TaskManagerScreen.WorldMiniTab.ENTITIES);
        tabX += entitiesTabW + 6;
        screen.drawTopChip(ctx, tabX, layout.miniTabY(), chunksTabW, 16, screen.worldMiniTab == TaskManagerScreen.WorldMiniTab.CHUNKS);
        tabX += chunksTabW + 6;
        screen.drawTopChip(ctx, tabX, layout.miniTabY(), blockTabW, 16, screen.worldMiniTab == TaskManagerScreen.WorldMiniTab.BLOCK_ENTITIES);
        tabX = left;
        ctx.drawText(textRenderer, "Lag Map", tabX + 16, layout.miniTabY() + 4, screen.worldMiniTab == TaskManagerScreen.WorldMiniTab.LAG_MAP ? TaskManagerScreen.TEXT_PRIMARY : TaskManagerScreen.TEXT_DIM, false);
        tabX += lagTabW + 6;
        ctx.drawText(textRenderer, "Entities", tabX + 12, layout.miniTabY() + 4, screen.worldMiniTab == TaskManagerScreen.WorldMiniTab.ENTITIES ? TaskManagerScreen.TEXT_PRIMARY : TaskManagerScreen.TEXT_DIM, false);
        tabX += entitiesTabW + 6;
        ctx.drawText(textRenderer, "Chunks", tabX + 14, layout.miniTabY() + 4, screen.worldMiniTab == TaskManagerScreen.WorldMiniTab.CHUNKS ? TaskManagerScreen.TEXT_PRIMARY : TaskManagerScreen.TEXT_DIM, false);
        tabX += chunksTabW + 6;
        ctx.drawText(textRenderer, "Block Entities", tabX + 14, layout.miniTabY() + 4, screen.worldMiniTab == TaskManagerScreen.WorldMiniTab.BLOCK_ENTITIES ? TaskManagerScreen.TEXT_PRIMARY : TaskManagerScreen.TEXT_DIM, false);
        int findingsCount = ProfilerManager.getInstance().getLatestRuleFindings().size();
        ctx.drawText(textRenderer, String.format(Locale.ROOT, "Selected chunk: %s | hot chunks: %d | findings: %d", screen.selectedLagChunk == null ? "none" : (screen.selectedLagChunk.x + "," + screen.selectedLagChunk.z), ProfilerManager.getInstance().getLatestHotChunks().size(), findingsCount), left, layout.summaryY(), TaskManagerScreen.TEXT_DIM, false);
        SystemMetricsProfiler metrics = SystemMetricsProfiler.getInstance();
        int worldGraphWidth = screen.getPreferredGraphWidth(w);
        int worldGraphX = x + Math.max(TaskManagerScreen.PADDING, (w - worldGraphWidth) / 2);
        top = layout.summaryY() + 18;

        if (screen.worldMiniTab == TaskManagerScreen.WorldMiniTab.LAG_MAP) {
            screen.renderLagMap(ctx, layout.left(), layout.mapRenderY(), layout.mapWidth(), layout.mapHeight());
            top = layout.mapTop() + (layout.cell() * ((layout.radius() * 2) + 1)) + 18;
            top = screen.renderLagChunkDetail(ctx, left, top, w - 24, h - 40) + 8;
            ctx.drawText(textRenderer, "Top thread CPU load", left, top, TaskManagerScreen.TEXT_PRIMARY, false);
            top += 16;
            if (screen.snapshot.systemMetrics().threadLoadPercentByName().isEmpty()) {
                ctx.drawText(textRenderer, "Waiting for JVM thread CPU samples...", left, top, TaskManagerScreen.TEXT_DIM, false);
                ctx.disableScissor();
                return;
            }
            int shown = 0;
            for (Map.Entry<String, ThreadLoadProfiler.ThreadSnapshot> entry : screen.snapshot.systemMetrics().threadDetailsByName().entrySet()) {
                ThreadLoadProfiler.ThreadSnapshot details = entry.getValue();
                String summary = screen.cleanProfilerLabel(entry.getKey()) + " | " + String.format(Locale.ROOT, "%.1f%% %s", details.loadPercent(), details.state());
                top = screen.renderWrappedText(ctx, left, top, w - 24, summary, screen.getHeatColor(details.loadPercent()));
                String waitLine = "blocked " + details.blockedCountDelta() + " / " + details.blockedTimeDeltaMs() + "ms | waited " + details.waitedCountDelta() + " / " + details.waitedTimeDeltaMs() + "ms | lock " + screen.describeLock(details);
                top = screen.renderWrappedText(ctx, left + 8, top, w - 32, waitLine, TaskManagerScreen.TEXT_DIM);
                shown++;
                if (shown >= 5) {
                    break;
                }
            }
            top += 8;
            top = screen.renderEntityHotspotSection(ctx, left, top, w - 24, ProfilerManager.getInstance().getLatestEntityHotspots(), "Entity Hotspots") + 8;
            top += screen.renderRuleFindingsSection(ctx, left, top, w - 24, ProfilerManager.getInstance().getLatestRuleFindings()) + 8;
        } else if (screen.worldMiniTab == TaskManagerScreen.WorldMiniTab.ENTITIES) {
            screen.drawMetricRow(ctx, worldGraphX, top, worldGraphWidth, "Entities", Integer.toString(screen.snapshot.entityCounts().totalEntities()));
            top += 18;
            screen.renderSeriesGraph(ctx, worldGraphX, top, worldGraphWidth, 120, metrics.getOrderedEntityCountHistory(), null, "Entities Over Time", "entities", screen.getWorldEntityGraphColor(), 0, metrics.getHistorySpanSeconds());
            top += 138;
            top += screen.renderGraphLegend(ctx, worldGraphX, top, new String[]{"Entities"}, new int[]{screen.getWorldEntityGraphColor()}) + 8;
            screen.drawMetricRow(ctx, worldGraphX, top, worldGraphWidth, "Living", Integer.toString(screen.snapshot.entityCounts().livingEntities()));
            top += 16;
            screen.drawMetricRow(ctx, worldGraphX, top, worldGraphWidth, "Block Entities", Integer.toString(screen.snapshot.entityCounts().blockEntities()));
            top += 20;
            top = screen.renderEntityHotspotSection(ctx, left, top, w - 24, ProfilerManager.getInstance().getLatestEntityHotspots(), "Entity Hotspots") + 8;
            top = screen.renderStringListSection(ctx, left, top, w - 24, "Entity Tick Cost [measured CPU]", EntityCostProfiler.getInstance().buildTopTickLines()) + 8;
            top = screen.renderStringListSection(ctx, left, top, w - 24, "Entity Render Prep Cost [measured CPU]", EntityCostProfiler.getInstance().buildTopRenderPrepLines()) + 8;
            top += screen.renderRuleFindingsSection(ctx, left, top, w - 24, ProfilerManager.getInstance().getLatestRuleFindings()) + 8;
        } else if (screen.worldMiniTab == TaskManagerScreen.WorldMiniTab.CHUNKS) {
            screen.drawMetricRow(ctx, worldGraphX, top, worldGraphWidth, "Chunks", screen.snapshot.chunkCounts().loadedChunks() + " loaded | " + screen.snapshot.chunkCounts().renderedChunks() + " rendered");
            top += 18;
            screen.renderSeriesGraph(ctx, worldGraphX, top, worldGraphWidth, 120, metrics.getOrderedLoadedChunkHistory(), metrics.getOrderedRenderedChunkHistory(), "Chunks Over Time", "chunks", screen.getWorldLoadedChunkGraphColor(), screen.getWorldRenderedChunkGraphColor(), metrics.getHistorySpanSeconds());
            top += 138;
            top += screen.renderGraphLegend(ctx, worldGraphX, top, new String[]{"Loaded", "Rendered"}, new int[]{screen.getWorldLoadedChunkGraphColor(), screen.getWorldRenderedChunkGraphColor()}) + 8;
            screen.drawMetricRow(ctx, worldGraphX, top, worldGraphWidth, "Chunk Generating", Integer.toString(screen.snapshot.systemMetrics().chunksGenerating()));
            top += 16;
            screen.drawMetricRow(ctx, worldGraphX, top, worldGraphWidth, "Chunk Meshing", Integer.toString(screen.snapshot.systemMetrics().chunksMeshing()));
            top += 16;
            screen.drawMetricRow(ctx, worldGraphX, top, worldGraphWidth, "Chunk Uploading", Integer.toString(screen.snapshot.systemMetrics().chunksUploading()));
            top += 16;
            screen.drawMetricRow(ctx, worldGraphX, top, worldGraphWidth, "Light Updates", Integer.toString(screen.snapshot.systemMetrics().lightsUpdatePending()));
            top += 20;
            top = screen.renderStringListSection(ctx, left, top, w - 24, "Chunk Pipeline Drill-Down", screen.buildChunkPipelineDrilldownLines()) + 8;
            top = screen.renderStringListSection(ctx, left, top, w - 24, "Chunk Load / Generation Cost [measured CPU]", ChunkWorkProfiler.getInstance().buildTopLines()) + 8;
            top += screen.renderRuleFindingsSection(ctx, left, top, w - 24, ProfilerManager.getInstance().getLatestRuleFindings()) + 8;
        } else {
            top = screen.renderBlockEntityHotspotSection(ctx, left, top, w - 24, ProfilerManager.getInstance().getLatestBlockEntityHotspots(), "Block Entity Hotspots") + 8;
            if (screen.selectedLagChunk != null) {
                ctx.drawText(textRenderer, "Selected chunk block entities", left, top, TaskManagerScreen.TEXT_PRIMARY, false);
                top += 14;
                MinecraftClient client = MinecraftClient.getInstance();
                Map<String, Integer> blockEntityCounts = new HashMap<>();
                if (client.world != null) {
                    for (BlockEntity blockEntity : client.world.getBlockEntities()) {
                        ChunkPos chunkPos = new ChunkPos(blockEntity.getPos());
                        if (chunkPos.x == screen.selectedLagChunk.x && chunkPos.z == screen.selectedLagChunk.z) {
                            blockEntityCounts.merge(screen.cleanProfilerLabel(blockEntity.getClass().getSimpleName()), 1, Integer::sum);
                        }
                    }
                }
                top = screen.renderCountMap(ctx, left, top, w - 24, "Top block entities in selected chunk [measured counts]", blockEntityCounts) + 8;
            } else {
                top = screen.renderWrappedText(ctx, left, top, w - 24, "Select a chunk from the Lag Map mini-tab to inspect block entities for that chunk.", TaskManagerScreen.TEXT_DIM) + 8;
            }
            top += screen.renderRuleFindingsSection(ctx, left, top, w - 24, ProfilerManager.getInstance().getLatestRuleFindings()) + 8;
        }
        ctx.disableScissor();
    }
}
