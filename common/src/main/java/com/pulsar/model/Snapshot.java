package com.pulsar.model;

/**
 * 线程池运行指标快照，供 monitor 模块消费。
 */
public record Snapshot(String name, int activeCount, int poolSize, int coreThreads,
                       int queueSize, int queueRemaining,
                       long completedTasks, long rejectedTasks, boolean scheduled) {

    public double utilization() {
        return poolSize > 0 ? (double) activeCount / poolSize : 0;
    }

    public int queueCapacity() {
        return queueSize + queueRemaining;
    }

    // ---- 工厂方法 ----

    public static Snapshot of(String name, int activeCount, int poolSize, int coreThreads,
                              int queueSize, int queueRemaining, long completedTasks,
                              long rejectedTasks) {
        return new Snapshot(name, activeCount, poolSize, coreThreads,
                queueSize, queueRemaining, completedTasks, rejectedTasks, false);
    }

    public static Snapshot scheduled(String name) {
        return new Snapshot(name, -1, -1, -1, -1, -1, -1, -1, true);
    }
}
