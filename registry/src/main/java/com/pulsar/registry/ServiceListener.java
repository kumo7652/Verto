package com.pulsar.registry;

import com.pulsar.registry.event.ServiceChangeEvent;

/**
 * 服务变更监听器
 *
 * <p>用于消费端订阅服务变更事件。当注册中心检测到服务节点上下线时，
 * 通过此接口回调通知消费端，实现服务的动态感知。
 *
 * <h3>使用场景</h3>
 * <ul>
 *   <li>消费端感知服务节点上下线，更新本地路由表</li>
 *   <li>监控告警：节点异常下线时触发告警</li>
 *   <li>统计上报：收集服务变更频率等指标</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>实现类应保证线程安全，回调可能在 Watch 线程中执行，
 * 不应进行阻塞操作或长时间处理。
 *
 * @see ServiceChangeEvent 服务变更事件
 * @see com.pulsar.registry.Registry#subscribe(String, ServiceListener)
 */
@FunctionalInterface
public interface ServiceListener {

    /**
     * 服务变更回调
     *
     * <p>当监听的服务发生节点变更时触发。事件包含变更类型
     * （添加/删除/更新）和变更的节点列表。
     *
     * @param event 服务变更事件，不为 null
     */
    void onChange(ServiceChangeEvent event);
}
