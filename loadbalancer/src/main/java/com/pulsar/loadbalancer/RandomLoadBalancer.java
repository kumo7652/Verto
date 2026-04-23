package com.pulsar.loadbalancer;

import com.pulsar.extension.SpiExtension;
import com.pulsar.model.ServiceNode;

import java.util.List;
import java.util.Map;
import java.util.Random;

@SpiExtension(name = "random")
public class RandomLoadBalancer implements LoadBalancer {
    private final Random random = new Random();

    @Override
    public ServiceNode select(Map<String, Object> requestParams, List<ServiceNode> serviceNodes) {
        int size = serviceNodes.size();
        if (size == 0) {
            return null;
        }

        if (size == 1) {
            return serviceNodes.get(0);
        }

        return serviceNodes.get(random.nextInt(size));
    }
}