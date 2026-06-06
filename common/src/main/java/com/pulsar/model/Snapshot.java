package com.pulsar.model;

import lombok.Getter;

/**
 * 线程池运行指标快照，供 monitor 模块消费。
 */
@Getter
@SuppressWarnings("ClassCanBeRecord")
public class Snapshot {

    private final String name;
    private final int activeCount;
    private final int poolSize;
    private final int coreThreads;
    private final int queueSize;
    private final int queueRemaining;
    private final long completedTasks;
    private final long rejectedTasks;
    private final boolean scheduled;

    public Snapshot(String name, int activeCount, int poolSize, int coreThreads,
                    int queueSize, int queueRemaining,
                    long completedTasks, long rejectedTasks, boolean scheduled) {
        this.name = name;
        this.activeCount = activeCount;
        this.poolSize = poolSize;
        this.coreThreads = coreThreads;
        this.queueSize = queueSize;
        this.queueRemaining = queueRemaining;
        this.completedTasks = completedTasks;
        this.rejectedTasks = rejectedTasks;
        this.scheduled = scheduled;
    }

    public double utilization() {
        return poolSize > 0 ? (double) activeCount / poolSize : 0;
    }

    public int queueCapacity() {
        return queueSize + queueRemaining;
    }

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
