package com.pulsar.model;

/**
 * <h3>活跃请求计数提供者</h3>
 * 函数式接口，解耦 loadbalancer 与 transport 模块的依赖。
 * transport 层实现此接口注入到 LeastActiveLoadBalancer。
 */
@FunctionalInterface
public interface ActiveCountProvider {
    int getActiveCount(ServiceNode node);
}
