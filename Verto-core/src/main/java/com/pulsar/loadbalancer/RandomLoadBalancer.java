package com.pulsar.loadbalancer;

import com.pulsar.extension.SpiExtension;
import com.pulsar.registry.model.ServiceInstance;

import java.util.List;
import java.util.Map;
import java.util.Random;

@SpiExtension(name = "random")
public class RandomLoadBalancer implements LoadBalancer {
    private final Random random = new Random();

    @Override
    public ServiceInstance select(Map<String, Object> requestParams, List<ServiceInstance> serviceInstances) {
        int size = serviceInstances.size();
        if (size == 0) {
            return null;
        }

        if (size == 1) {
            return serviceInstances.get(0);
        }

        return serviceInstances.get(random.nextInt(size));
    }
}