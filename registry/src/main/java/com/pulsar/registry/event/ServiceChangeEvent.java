package com.pulsar.registry.event;

import com.pulsar.model.ServiceNode;
import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 服务变更事件
 *
 * <p>封装服务节点变更的详细信息，由 EtcdRegistry 的 Watch 回调生成，
 * 通过 ServiceChangeListener 通知消费端。
 *
 * <h3>事件结构</h3>
 * <ul>
 *   <li>serviceKey: 变更的服务标识（如 order-service:1.0）</li>
 *   <li>type: 变更类型（新增、删除、更新）</li>
 *   <li>addedNodes: 新增的节点列表，删除事件时为空列表</li>
 *   <li>deletedNodes: 删除的节点列表，新增/更新事件时为空列表</li>
 *   <li>updatedNodes: 更新的节点列表，新增/删除事件时为空列表</li>
 * </ul>
 *
 * @see ChangeType 变更类型
 * @see com.pulsar.registry.ServiceListener 监听器接口
 */
@Data
@Builder
public class ServiceChangeEvent {

    /**
     * 变更的服务标识
     * 格式：serviceName:version，如 order-service:1.0
     */
    private String serviceKey;

    /**
     * 变更类型
     */
    private ChangeType type;

    /**
     * 新增的节点列表
     * 仅 NODE_ADDED 事件有值，其他事件为空列表
     */
    @Builder.Default
    private List<ServiceNode> addedNodes = Collections.emptyList();

    /**
     * 删除的节点列表
     * 仅 NODE_DELETED 事件有值，其他事件为空列表
     */
    @Builder.Default
    private List<ServiceNode> deletedNodes = Collections.emptyList();

    /**
     * 更新的节点列表
     * 仅 NODE_UPDATED 事件有值，其他事件为空列表
     */
    @Builder.Default
    private List<ServiceNode> updatedNodes = Collections.emptyList();
}