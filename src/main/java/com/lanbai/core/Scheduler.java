package com.lanbai.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 延迟执行调度器，由服务器 tick 驱动。
 * 用于「3 秒后爆炸」这类需要延后生效的效果。
 */
public final class Scheduler {

    private static final List<Task> TASKS = new ArrayList<>();
    private static long tick = 0;

    private Scheduler() {
    }

    /** 每 tick 调用一次 */
    public static void tick() {
        tick++;
        if (TASKS.isEmpty()) return;
        Iterator<Task> it = TASKS.iterator();
        while (it.hasNext()) {
            Task t = it.next();
            if (t.due > tick) continue;
            it.remove();
            try {
                t.action.run();
            } catch (Exception ignored) {
            }
        }
    }

    /** 延后 delayTicks 执行（至少 1 tick） */
    public static void schedule(int delayTicks, Runnable action) {
        TASKS.add(new Task(tick + Math.max(1, delayTicks), action));
    }

    public static long now() {
        return tick;
    }

    public static int size() {
        return TASKS.size();
    }

    public static void clear() {
        TASKS.clear();
    }

    private static final class Task {
        final long due;
        final Runnable action;

        Task(long due, Runnable action) {
            this.due = due;
            this.action = action;
        }
    }
}
