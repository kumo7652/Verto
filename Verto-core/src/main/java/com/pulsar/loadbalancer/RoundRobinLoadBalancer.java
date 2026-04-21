package com.pulsar.loadbalancer;

import com.pulsar.extension.SpiExtension;
import com.pulsar.model.ServiceNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@SpiExtension(name = "roundRobin")
public class RoundRobinLoadBalancer implements LoadBalancer {
    private final AtomicInteger currentIndex = new AtomicInteger(0);

    @Override
    public ServiceNode select(Map<String, Object> requestParams, List<ServiceNode> serviceNodes) {
        if (serviceNodes.isEmpty()) {
            return null;
        }

        int size = serviceNodes.size();

        if (size == 1) {
            return serviceNodes.get(0);
        }

        int index = currentIndex.getAndIncrement() % size;
        return serviceNodes.get(index);
    }
}