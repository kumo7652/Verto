package com.pulsar.registry.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.pulsar.model.ServiceNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Caffeine + nodeIndex 双层缓存的默认实现
 *
 * <h3>双层缓存架构</h3>
 * <ul>
 *   <li>主缓存（Caffeine）：serviceKey → List&lt;ServiceNode&gt;，提供 TTL 过期、容量淘汰、统计</li>
 *   <li>节点索引（ConcurrentHashMap）：serviceKey → {nodeKey → ServiceNode}，支持 O(1) 增量更新</li>
 * </ul>
 *
 * <h3>为何需要二级索引</h3>
 * <p>Caffeine 缓存的 value 是 List，增量操作（add/remove 单个节点）需要遍历 List 查找。
 * nodeIndex 以 nodeKey 为二级 key，将增量操作从 O(n) 降为 O(1)，
 * 每次增量操作后同步更新主缓存的 List（从 nodeIndex 重建），保证主缓存数据一致性。
 *
 * <h3>一致性保证</h3>
 * <p>nodeIndex 和主缓存之间的同步通过"先更新 nodeIndex，再重建主缓存"的顺序保证。
 * 所有增量操作和全量操作均在同一个方法内完成两级同步，不存在中间状态泄漏。
 *
 * @see ServiceCache 缓存接口
 * @see CacheConfig 缓存配置
 */
@Slf4j
public class DefaultServiceCache implements ServiceCache {

    /**
     * 主缓存：Caffeine 提供 TTL 过期、容量淘汰、统计能力
     * Key: serviceKey（如 order-service:1.0）
     * Value: 该服务的所有节点列表
     */
    private final Cache<String, List<ServiceNode>> cache;

    /**
     * 节点二级索引：用于 O(1) 增量更新
     * 外层 Key: serviceKey
     * 内层 Key: serviceNodeKey（如 order-service-001）
     * 内层 Value: ServiceNode
     */
    private final Map<String, Map<String, ServiceNode>> nodeIndex;

    /**
     * 使用默认配置构造
     */
    public DefaultServiceCache() {
        this(CacheConfig.builder().build());
    }

    /**
     * 使用自定义配置构造
     *
     * @param config 缓存配置
     */
    public DefaultServiceCache(CacheConfig config) {
        Caffeine<Object, Object> builder = Caffeine.newBuilder()
                .maximumSize(config.getMaxSize())
                .expireAfterWrite(config.getExpire(), TimeUnit.MILLISECONDS);

        if (config.isEnableMonitor()) {
            builder.recordStats();
        }

        this.cache = builder.build();
        this.nodeIndex = new ConcurrentHashMap<>();
    }

    // ========== 全量操作 ==========

    @Override
    public void put(String serviceKey, List<ServiceNode> nodes) {
        // 同步更新 nodeIndex
        Map<String, ServiceNode> index = new ConcurrentHashMap<>();
        for (ServiceNode node : nodes) {
            index.put(node.getServiceNodeKey(), node);
        }
        nodeIndex.put(serviceKey, index);

        // 更新主缓存
        cache.put(serviceKey, new ArrayList<>(nodes));
        log.debug("缓存全量写入: {}, 节点数: {}", serviceKey, nodes.size());
    }

    @Override
    public List<ServiceNode> get(String serviceKey) {
        return cache.getIfPresent(serviceKey);
    }

    @Override
    public void invalidate(String serviceKey) {
        cache.invalidate(serviceKey);
        nodeIndex.remove(serviceKey);
        log.debug("缓存失效: {}", serviceKey);
    }

    @Override
    public void invalidateAll() {
        cache.invalidateAll();
        nodeIndex.clear();
        log.debug("缓存全量失效");
    }

    // ========== 增量操作 ==========

    @Override
    public void addNode(String serviceKey, ServiceNode node) {
        Map<String, ServiceNode> index = nodeIndex.computeIfAbsent(serviceKey,
                k -> new ConcurrentHashMap<>());
        index.put(node.getServiceNodeKey(), node);
        syncToMainCache(serviceKey);
        log.debug("缓存增量添加节点: {} → {}", serviceKey, node.getServiceNodeKey());
    }

    @Override
    public void removeNode(String serviceKey, ServiceNode node) {
        Map<String, ServiceNode> index = nodeIndex.get(serviceKey);
        if (index != null) {
            index.remove(node.getServiceNodeKey());
            if (index.isEmpty()) {
                cache.invalidate(serviceKey);
                nodeIndex.remove(serviceKey);
            } else {
                syncToMainCache(serviceKey);
            }
        }
        log.debug("缓存增量移除节点: {} → {}", serviceKey, node.getServiceNodeKey());
    }

    @Override
    public void updateNode(String serviceKey, ServiceNode node) {
        Map<String, ServiceNode> index = nodeIndex.get(serviceKey);
        if (index != null && index.containsKey(node.getServiceNodeKey())) {
            index.put(node.getServiceNodeKey(), node);
            syncToMainCache(serviceKey);
            log.debug("缓存增量更新节点: {} → {}", serviceKey, node.getServiceNodeKey());
        }
    }

    // ========== 状态查询 ==========

    @Override
    public boolean contains(String serviceKey) {
        return cache.asMap().containsKey(serviceKey);
    }

    @Override
    public int size() {
        return (int) cache.estimatedSize();
    }

    @Override
    public CacheMonitor getStats() {
        CacheStats stats = cache.stats();
        return new CacheMonitor(
                stats.hitCount(),
                stats.missCount(),
                stats.loadCount(),
                stats.evictionCount(),
                stats.hitRate()
        );
    }

    // ========== 内部方法 ==========

    /**
     * 将 nodeIndex 同步到主缓存
     * <p>增量操作后调用，从 nodeIndex 重建 List 写入主缓存，
     * 保证主缓存与索引的数据一致性。
     */
    private void syncToMainCache(String serviceKey) {
        Map<String, ServiceNode> index = nodeIndex.get(serviceKey);
        if (index != null) {
            cache.put(serviceKey, new ArrayList<>(index.values()));
        }
    }
}
