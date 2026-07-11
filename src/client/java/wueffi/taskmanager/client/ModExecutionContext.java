package wueffi.taskmanager.client;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ModExecutionContext {

    public record ActiveContext(String modId, String reason) {}

    private static final ThreadLocal<Deque<ActiveContext>> CONTEXT_STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static final Map<Long, ActiveContext> ACTIVE_CONTEXTS_BY_THREAD = new ConcurrentHashMap<>();

    private ModExecutionContext() {
    }

    public static void push(String modId, String reason) {
        if (modId == null || modId.isBlank() || modId.startsWith("shared/") || modId.startsWith("runtime/")) {
            return;
        }

        ActiveContext context = new ActiveContext(modId, reason == null || reason.isBlank() ? "scoped callback" : reason);
        Deque<ActiveContext> stack = CONTEXT_STACK.get();
        stack.addLast(context);
        ACTIVE_CONTEXTS_BY_THREAD.put(Thread.currentThread().threadId(), context);
    }

    public static void pop() {
        long threadId = Thread.currentThread().threadId();
        Deque<ActiveContext> stack = CONTEXT_STACK.get();
        if (stack.isEmpty()) {
            ACTIVE_CONTEXTS_BY_THREAD.remove(threadId);
            return;
        }

        stack.removeLast();
        if (stack.isEmpty()) {
            ACTIVE_CONTEXTS_BY_THREAD.remove(threadId);
            CONTEXT_STACK.remove();
            return;
        }

        ACTIVE_CONTEXTS_BY_THREAD.put(threadId, stack.peekLast());
    }

    public static ActiveContext getActiveContext(long threadId) {
        return ACTIVE_CONTEXTS_BY_THREAD.get(threadId);
    }

    public static ActiveContext getCurrentContext() {
        Deque<ActiveContext> stack = CONTEXT_STACK.get();
        return stack.isEmpty() ? null : stack.peekLast();
    }

    public static void clearCurrentThread() {
        CONTEXT_STACK.remove();
        ACTIVE_CONTEXTS_BY_THREAD.remove(Thread.currentThread().threadId());
    }
}
