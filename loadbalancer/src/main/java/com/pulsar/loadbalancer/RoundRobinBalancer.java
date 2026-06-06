package com.pulsar.loadbalancer;

import com.pulsar.extension.SpiExtension;
import com.pulsar.model.LoadBalancerContext;
import com.pulsar.model.ServiceNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <h3>轮询负载均衡器</h3>
 *
 * <p>每个服务维护一个原子计数器，请求依次分配到各节点。
 * 适合性能相近的服务实例。</p>
 */
@SpiExtension(name = "round-robin")
public class RoundRobinBalancer extends AbstractLoadBalancer {
    private final Map<String, AtomicInteger> serviceIndexes = new ConcurrentHashMap<>();

    @Override
    protected Optional<ServiceNode> doSelect(LoadBalancerContext context, List<ServiceNode> nodes) {
        int size = nodes.size();
        String serviceKey = context.serviceKey();
        AtomicInteger counter = serviceIndexes.computeIfAbsent(serviceKey,
                k -> new AtomicInteger(0));

        int index = counter.getAndUpdate(i -> i >= Integer.MAX_VALUE / 2 ? 0 : i + 1);
        return Optional.of(nodes.get(index % size));
    }
}
