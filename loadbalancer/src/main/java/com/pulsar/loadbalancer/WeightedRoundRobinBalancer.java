package com.pulsar.loadbalancer;

import com.pulsar.extension.SpiExtension;
import com.pulsar.model.LoadBalancerContext;
import com.pulsar.model.ServiceNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h3>平滑加权轮询负载均衡器</h3>
 *
 * <p>采用 Nginx 风格平滑加权轮询算法，解决普通加权轮询在权重差异大时
 * 对高权重节点连续请求的"扎堆"问题。</p>
 *
 * <p>算法步骤：</p>
 * <ol>
 *   <li>每轮每个节点的 currentWeight 累加自身静态 weight</li>
 *   <li>选出 currentWeight 最大的节点</li>
 *   <li>该节点的 currentWeight 减去本轮总权重</li>
 * </ol>
 *
 * <p>示例：权重 [5, 1, 1]，7 次选择的序列为 A, A, B, A, C, A, A，
 * 高权重节点 A 被均匀穿插而非连续命中 5 次后再轮到 B、C。</p>
 *
 * <p>线程安全：同一服务的选调用 synchronized 串行化，
 * 不同服务之间并发执行互不影响。</p>
 */
@SpiExtension(name = "weighted-round-robin")
public class WeightedRoundRobinBalancer implements LoadBalancer {

    private final ConcurrentHashMap<String, List<NodeState>> serviceStates = new ConcurrentHashMap<>();

    @Override
    public Optional<ServiceNode> select(LoadBalancerContext context, List<ServiceNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return Optional.empty();
        }
        if (nodes.size() == 1) {
            return Optional.of(nodes.get(0));
        }

        String serviceKey = context.serviceKey();
        List<NodeState> states = serviceStates.computeIfAbsent(serviceKey,
                k -> new ArrayList<>());

        synchronized (states) {
            // 同步节点列表：移除下线节点，加入新节点
            syncStates(states, nodes);
            if (states.isEmpty()) {
                return Optional.empty();
            }

            int totalWeight = 0;
            NodeState best = null;

            // 每轮 currentWeight 累加静态 weight，选最大者
            for (NodeState state : states) {
                totalWeight += state.weight;
                state.currentWeight += state.weight;
                if (best == null || state.currentWeight > best.currentWeight) {
                    best = state;
                }
            }

            if (best == null) {
                return Optional.empty();
            }
            // 选中节点减去总权重，使其在下轮中优先级降低
            best.currentWeight -= totalWeight;
            return Optional.of(best.node);
        }
    }

    private void syncStates(List<NodeState> states, List<ServiceNode> nodes) {
        states.removeIf(s -> {
            for (ServiceNode node : nodes) {
                if (node.getServiceNodeKey().equals(s.node.getServiceNodeKey())) {
                    return false;
                }
            }
            return true;
        });

        for (ServiceNode node : nodes) {
            boolean found = false;
            for (NodeState state : states) {
                if (state.node.getServiceNodeKey().equals(node.getServiceNodeKey())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                states.add(new NodeState(node));
            }
        }
    }

    private static class NodeState {
        final ServiceNode node;
        final int weight;
        int currentWeight;

        NodeState(ServiceNode node) {
            this.node = node;
            this.weight = node.getWeight();
            this.currentWeight = 0;
        }
    }
}
