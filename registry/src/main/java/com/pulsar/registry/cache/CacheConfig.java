package com.pulsar.registry.cache;

import lombok.Builder;
import lombok.Data;

/**
 * 服务缓存配置
 *
 * <p>用于配置 Caffeine 缓存的各种参数，包括容量限制、过期时间、统计开关等。
 */
@Data
@Builder
public class CacheConfig {

    /**
     * 最大缓存条目数
     * <p>超过此数量后，Caffeine 使用 Window TinyLFU 算法淘汰低频条目
     */
    @Builder.Default
    private int maxSize = 1000;

    /**
     * 写入后过期时间（毫秒）
     * <p>缓存条目写入后经过此时间自动失效，作为 Watch 断连时的兜底机制
     */
    @Builder.Default
    private long expire = 60000L;

    /**
     * 是否启用统计
     * <p>启用后可通过 getStats() 获取命中率、未命中数等指标
     */
    @Builder.Default
    private boolean enableMonitor = true;
}