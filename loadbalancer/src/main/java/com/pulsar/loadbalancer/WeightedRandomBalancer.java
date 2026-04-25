package com.pulsar.loadbalancer;

import com.pulsar.extension.SpiExtension;
import com.pulsar.model.LoadBalancerContext;
import com.pulsar.model.ServiceNode;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * <h3>带权随机负载均衡器</h3>
 *
 * <p>按节点权重占比随机选择，权重越大被选中的概率越高。
 * 权重从 {@link ServiceNode#getWeight()} 读取，默认 100。</p>
 *
 * <p>算法：累计所有权重为 totalWeight，在 {@code [0, totalWeight)} 内取随机数，
 * 依次减去每个节点的权重，减到负值时命中该节点。</p>
 */
@SpiExtension(name = "weighted-random")
public class WeightedRandomBalancer implements LoadBalancer {

    @Override
    public Optional<ServiceNode> select(LoadBalancerContext context, List<ServiceNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return Optional.empty();
        }
        if (nodes.size() == 1) {
            return Optional.of(nodes.get(0));
        }

        int totalWeight = 0;
        for (ServiceNode node : nodes) {
            totalWeight += node.getWeight();
        }

        if (totalWeight <= 0) {
            return Optional.of(nodes.get(ThreadLocalRandom.current().nextInt(nodes.size())));
        }

        int offset = ThreadLocalRandom.current().nextInt(totalWeight);
        for (ServiceNode node : nodes) {
            offset -= node.getWeight();
            if (offset < 0) {
                return Optional.of(node);
            }
        }
        return Optional.of(nodes.get(nodes.size() - 1));
    }
}
