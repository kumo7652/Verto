package com.pulsar.loadbalancer;

import com.pulsar.extension.SpiExtension;
import com.pulsar.registry.model.ServiceInstance;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@SpiExtension(name = "roundRobin")
public class RoundRobinLoadBalancer implements LoadBalancer {
    private final AtomicInteger currentIndex = new AtomicInteger(0);

    @Override
    public ServiceInstance select(Map<String, Object> requestParams, List<ServiceInstance> serviceInstances) {
        if (serviceInstances.isEmpty()) {
            return null;
        }

        int size = serviceInstances.size();

        if (size == 1) {
            return serviceInstances.get(0);
        }

        int index = currentIndex.getAndIncrement() % size;
        return serviceInstances.get(index);
    }
}