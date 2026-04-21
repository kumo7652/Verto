package com.pulsar.registry.cache;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 缓存统计数据
 *
 * <p>提供缓存的运行指标，用于监控和性能调优。
 * 由 Caffeine 内置的统计功能驱动。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CacheMonitor {

    /**
     * 缓存命中次数
     */
    private long hitCount;

    /**
     * 缓存未命中次数
     */
    private long missCount;

    /**
     * 缓存加载次数（首次写入）
     */
    private long loadCount;

    /**
     * 缓存驱逐次数（因容量限制或过期被移除）
     */
    private long evictionCount;

    /**
     * 缓存命中率（0.0 ~ 1.0）
     */
    private double hitRate;
}