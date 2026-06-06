package com.pulsar.loadbalancer;

import com.pulsar.extension.SpiExtension;
import com.pulsar.model.LoadBalancerContext;
import com.pulsar.model.ServiceNode;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <h3>平滑加权轮询负载均衡器</h3>
 *
 * <p>Nginx 风格平滑加权轮询算法：每轮各节点 currentWeight 累加静态 weight，
 * 选 currentWeight 最大者，命中后减去总权重。</p>
 *
 * <p>并发安全：currentWeight 使用 AtomicInteger + CAS，无锁化。
 * 参考 Dubbo RoundRobinLoadBalance。</p>
 */
@SpiExtension(name = "weighted-round-robin")
public class WeightedRoundRobinBalancer extends AbstractLoadBalancer {

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, NodeState>> serviceStates =
            new ConcurrentHashMap<>();

    @Override
    protected Optional<ServiceNode> doSelect(LoadBalancerContext context, List<ServiceNode> nodes) {
        String serviceKey = context.serviceKey();
        ConcurrentHashMap<String, NodeState> stateMap = serviceStates.computeIfAbsent(
                serviceKey, k -> new ConcurrentHashMap<>());

        // 同步节点列表：移除下线节点，加入新节点
        for (ServiceNode node : nodes) {
            String key = node.getServiceNodeKey();
            stateMap.computeIfAbsent(key, k -> new NodeState(node, getWeight(node)));
        }
        stateMap.keySet().removeIf(key -> {
            for (ServiceNode node : nodes) {
                if (node.getServiceNodeKey().equals(key)) {
                    return false;
                }
            }
            return true;
        });

        if (stateMap.isEmpty()) {
            return Optional.empty();
        }

        int totalWeight = 0;
        NodeState best = null;
        int maxCurrent = Integer.MIN_VALUE;

        for (NodeState state : stateMap.values()) {
            totalWeight += state.weight;
            int current = state.currentWeight.addAndGet(state.weight);
            if (current > maxCurrent) {
                maxCurrent = current;
                best = state;
            }
        }

        if (best != null) {
            best.currentWeight.addAndGet(-totalWeight);
            return Optional.of(best.node);
        }
        return Optional.empty();
    }

    private static class NodeState {
        final ServiceNode node;
        final int weight;
        final AtomicInteger currentWeight;

        NodeState(ServiceNode node, int weight) {
            this.node = node;
            this.weight = weight;
            this.currentWeight = new AtomicInteger(0);
        }
    }
}
