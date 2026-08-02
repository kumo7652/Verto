package com.pulsar.loadbalancer;

import com.pulsar.extension.SpiExtension;
import com.pulsar.model.ActiveCounter;
import com.pulsar.model.LoadBalancerContext;
import com.pulsar.model.ServiceNode;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * <h3>最小活跃数负载均衡器</h3>
 *
 * <p>选择当前活跃请求数最少的节点，自动避开过载或慢节点。
 * 参考 Dubbo LeastActiveLoadBalance：选出 active 最小的一组节点，
 * 在该组内按权重随机选择。</p>
 *
 * <p>{@link ActiveCounter} 通过 setter 注入，未注入时退化为纯随机。</p>
 */
@Setter
@SpiExtension(name = "least-active")
public class LeastActiveLoadBalancer extends AbstractLoadBalancer {

    private volatile ActiveCounter activeCounter;

    @Override
    protected Optional<ServiceNode> doSelect(LoadBalancerContext context, List<ServiceNode> nodes) {
        ActiveCounter provider = this.activeCounter;
        if (provider == null) {
            int index = ThreadLocalRandom.current().nextInt(nodes.size());
            return Optional.of(nodes.get(index));
        }

        int leastActive = Integer.MAX_VALUE;
        List<ServiceNode> leastActives = new ArrayList<>(nodes.size());

        for (ServiceNode node : nodes) {
            int active = provider.getActiveCount(node);
            if (active < leastActive) {
                leastActive = active;
                leastActives.clear();
                leastActives.add(node);
            } else if (active == leastActive) {
                leastActives.add(node);
            }
        }

        if (leastActives.size() == 1) {
            return Optional.of(leastActives.get(0));
        }

        // 权重相同则纯随机，否则加权随机
        int totalWeight = 0;
        boolean sameWeight = true;
        int firstWeight = -1;
        for (ServiceNode node : leastActives) {
            int w = getWeight(node);
            totalWeight += w;
            if (firstWeight == -1) {
                firstWeight = w;
            } else if (w != firstWeight) {
                sameWeight = false;
            }
        }

        if (totalWeight <= 0 || sameWeight) {
            int index = ThreadLocalRandom.current().nextInt(leastActives.size());
            return Optional.of(leastActives.get(index));
        }

        int offset = ThreadLocalRandom.current().nextInt(totalWeight);
        for (ServiceNode node : leastActives) {
            offset -= getWeight(node);
            if (offset < 0) {
                return Optional.of(node);
            }
        }
        return Optional.of(leastActives.get(leastActives.size() - 1));
    }
}
