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
 * 权重通过 {@link #getWeight(ServiceNode)} 获取（含预热）。</p>
 */
@SpiExtension(name = "weighted-random")
public class WeightedRandomBalancer extends AbstractLoadBalancer {

    @Override
    protected Optional<ServiceNode> doSelect(LoadBalancerContext context, List<ServiceNode> nodes) {
        int totalWeight = 0;
        for (ServiceNode node : nodes) {
            totalWeight += getWeight(node);
        }

        if (totalWeight <= 0) {
            return Optional.of(nodes.get(ThreadLocalRandom.current().nextInt(nodes.size())));
        }

        int offset = ThreadLocalRandom.current().nextInt(totalWeight);
        for (ServiceNode node : nodes) {
            offset -= getWeight(node);
            if (offset < 0) {
                return Optional.of(node);
            }
        }
        return Optional.of(nodes.get(nodes.size() - 1));
    }
}
