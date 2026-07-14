package com.pulsar.registry.cache;

import com.pulsar.model.ServiceNode;

import java.util.List;

/**
 * 服务节点本地缓存接口
 *
 * <p>提供服务节点列表的本地缓存能力，支持全量操作和增量更新。
 * 用于减少对注册中心的直接查询压力，提升服务发现性能。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>全量操作：put、get、invalidate 用于整体缓存管理</li>
 *   <li>增量操作：addNode、removeNode、updateNode 用于 Watch 事件的增量更新</li>
 *   <li>统计能力：getStats 提供命中率等监控指标</li>
 * </ul>
 *
 * @see DefaultServiceCache 基于 Caffeine 的默认实现
 * @see CacheConfig 缓存配置
 */
public interface ServiceCache {

    // ========== 全量操作 ==========

    /**
     * 写入服务节点列表到缓存（全量覆盖）
     *
     * @param serviceKey 服务标识，如 order-service:1.0
     * @param nodes      服务节点列表
     */
    void put(String serviceKey, List<ServiceNode> nodes);

    /**
     * 从缓存获取服务节点列表
     *
     * @param serviceKey 服务标识
     * @return 节点列表，不存在时返回 null
     */
    List<ServiceNode> get(String serviceKey);

    /**
     * 失效单个服务的缓存
     *
     * @param serviceKey 服务标识
     */
    void invalidate(String serviceKey);

    /**
     * 失效所有缓存
     */
    void invalidateAll();

    // ========== 增量操作 ==========

    /**
     * 增量添加单个节点
     * <p>Watch 检测到 PUT 事件时调用，无需全量刷新缓存
     *
     * @param serviceKey 服务标识
     * @param node       新增节点
     */
    void addNode(String serviceKey, ServiceNode node);

    /**
     * 增量移除单个节点
     * <p>Watch 检测到 DELETE 事件时调用，无需全量刷新缓存
     *
     * @param serviceKey 服务标识
     * @param node       移除的节点
     */
    void removeNode(String serviceKey, ServiceNode node);

    /**
     * 更新单个节点
     * <p>Watch 检测到节点属性变更时调用
     *
     * @param serviceKey 服务标识
     * @param node       更新后的节点
     */
    void updateNode(String serviceKey, ServiceNode node);

    // ========== 状态查询 ==========

    /**
     * 检查缓存中是否存在指定服务
     *
     * @param serviceKey 服务标识
     * @return true 如果存在
     */
    boolean contains(String serviceKey);

    /**
     * 获取缓存中的服务数量
     *
     * @return 缓存条目数
     */
    int size();

    /**
     * 获取缓存统计信息
     *
     * @return 缓存统计数据
     */
    CacheMonitor getStats();
}
