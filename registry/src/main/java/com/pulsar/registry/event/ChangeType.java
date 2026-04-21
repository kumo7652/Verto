package com.pulsar.registry.event;

/**
 * 服务变更类型枚举
 *
 * <p>对应 etcd Watch 事件类型，用于标识服务节点的变更方式。
 */
public enum ChangeType {

    /**
     * 节点新增（etcd PUT 事件，key 不存在时）
     */
    NODE_ADDED,

    /**
     * 节点删除（etcd DELETE 事件）
     */
    NODE_DELETED,

    /**
     * 节点更新（etcd PUT 事件，key 已存在时，如节点元信息变更）
     */
    NODE_UPDATED
}
