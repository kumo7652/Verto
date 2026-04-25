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
 * <p>特点：
 * <ul>
 *   <li>请求绝对均匀分布</li>
 *   <li>无权重时表现最优</li>
 *   <li>适合性能相近的服务实例</li>
 * </ul>
 */
@SpiExtension(name = "round-robin")
public class RoundRobinBalancer implements LoadBalancer {
    private final Map<String, AtomicInteger> serviceIndexes = new ConcurrentHashMap<>();

    @Override
    public Optional<ServiceNode> select(LoadBalancerContext context, List<ServiceNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return Optional.empty();
        }

        int size = nodes.size();
        if (size == 1) {
            return Optional.of(nodes.get(0));
        }

        String serviceKey = context.serviceKey();
        AtomicInteger counter = serviceIndexes.computeIfAbsent(serviceKey,
                k -> new AtomicInteger(0));

        // 达到界限后重置，防止Integer溢出
        int current = counter.getAndIncrement();
        if (current >= Integer.MAX_VALUE / 2) {
            counter.compareAndSet(current, 0);
        }

        int index = (current % size);

        return Optional.of(nodes.get(index));
    }
}
