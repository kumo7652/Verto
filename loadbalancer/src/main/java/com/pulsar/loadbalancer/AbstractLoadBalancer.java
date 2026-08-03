package com.pulsar.loadbalancer;

import com.pulsar.LoadBalancer;
import com.pulsar.model.LoadBalancerContext;
import com.pulsar.model.ServiceNode;

import java.util.List;
import java.util.Optional;

/**
 * <h3>负载均衡器抽象基类</h3>
 * 模板方法模式：统一处理空列表/单节点边界情况，
 * 并提供权重预热计算。
 */
public abstract class AbstractLoadBalancer implements LoadBalancer {

    private static final int DEFAULT_WARMUP_MS = 10 * 60 * 1000;

    @Override
    public Optional<ServiceNode> select(LoadBalancerContext context, List<ServiceNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return Optional.empty();
        }
        if (nodes.size() == 1) {
            return Optional.of(nodes.get(0));
        }
        return doSelect(context, nodes);
    }

    protected abstract Optional<ServiceNode> doSelect(LoadBalancerContext context, List<ServiceNode> nodes);

    /**
     * 计算权重，含二次方预热曲线。
     * uptime² / warmup² × weight。
     */
    protected int getWeight(ServiceNode node) {
        int weight = node.getWeight();
        if (weight <= 0) {
            return 1;
        }

        long startTime = node.getStartTimestamp();
        if (startTime == 0) {
            return weight;
        }

        long uptime = System.currentTimeMillis() - startTime;
        if (uptime <= 0) {
            return 1;
        }
        if (uptime >= DEFAULT_WARMUP_MS) {
            return weight;
        }

        int ww = (int) (Math.pow((double) uptime / DEFAULT_WARMUP_MS, 2) * weight);
        return Math.max(1, Math.min(ww, weight));
    }
}
