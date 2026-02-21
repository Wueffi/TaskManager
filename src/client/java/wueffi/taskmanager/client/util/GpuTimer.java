package wueffi.taskmanager.client.util;

import org.lwjgl.opengl.ARBTimerQuery;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import wueffi.taskmanager.client.RenderPhaseProfiler;
import wueffi.taskmanager.client.TaskManagerScreen;
import wueffi.taskmanager.client.taskmanagerClient;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

public class GpuTimer {

    private static boolean supported = false;
    private static boolean checkedSupport = false;

    private static final Map<String, Deque<Integer>> pending = new HashMap<>();

    private record ActiveQuery(String phase, int queryId) {}
    private static ActiveQuery active = null;

    public static boolean isSupported() {
        if (!checkedSupport) {
            checkedSupport = true;
            try {
                supported = GL.getCapabilities().GL_ARB_timer_query ||
                            GL.getCapabilities().OpenGL33;
            } catch (Exception e) {
                supported = false;
            }
        }
        return supported;
    }

    public static void begin(String phase) {
        if (!TaskManagerScreen.isProfilingActive()) return;
        if (!isSupported()) return;
        if (active != null) {
            return;
        }
        try {
            int queryId = GL15.glGenQueries();
            GL33.glBeginQuery(ARBTimerQuery.GL_TIME_ELAPSED, queryId);
            active = new ActiveQuery(phase, queryId);
        } catch (Exception e) {
            taskmanagerClient.LOGGER.debug("GpuTimer.begin failed: {}", e.getMessage());
        }
    }

    public static void end(String phase) {
        if (!isSupported()) return;
        if (active == null || !active.phase().equals(phase)) return;
        try {
            GL33.glEndQuery(ARBTimerQuery.GL_TIME_ELAPSED);
            pending.computeIfAbsent(active.phase(), k -> new ArrayDeque<>())
                   .addLast(active.queryId());
            active = null;
        } catch (Exception e) {
            taskmanagerClient.LOGGER.debug("GpuTimer.end failed: {}", e.getMessage());
            active = null;
        }
    }

    public static void collectResults() {
        if (!isSupported()) return;
        RenderPhaseProfiler profiler = RenderPhaseProfiler.getInstance();

        pending.forEach((phase, queue) -> {
            while (!queue.isEmpty()) {
                int queryId = queue.peekFirst();
                int available = GL15.glGetQueryObjecti(queryId, GL15.GL_QUERY_RESULT_AVAILABLE);
                if (available == 0) break; // Not ready yet, check next frame
                queue.pollFirst();
                long gpuNs = GL15.glGetQueryObjecti(queryId, GL15.GL_QUERY_RESULT);
                GL15.glDeleteQueries(queryId);
                profiler.recordGpuResult(phase, gpuNs < 0 ? gpuNs + 0x100000000L : gpuNs);
            }
        });
    }

    public static long readQuery64(int queryId) {
        int available = GL15.glGetQueryObjecti(queryId, GL15.GL_QUERY_RESULT_AVAILABLE);
        if (available == 0) return -1;
        return ARBTimerQuery.glGetQueryObjectui64(queryId, GL15.GL_QUERY_RESULT);
    }
}
