package com.pulsar.loadbalancer;

import com.pulsar.extension.SpiExtension;
import com.pulsar.registry.model.ServiceInstance;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@SpiExtension(name = "consistentHash")
public class ConsistentHashLoadBalancer implements LoadBalancer {
    private final TreeMap<Integer, ServiceInstance> nodes = new TreeMap<>();
    private static final int VIRTUAL_NODE_COUNT = 100;

    @Override
    public ServiceInstance select(Map<String, Object> requestParams, List<ServiceInstance> serviceInstances) {
        if (serviceInstances.isEmpty()) {
            return null;
        }

        for (ServiceInstance serviceInstance : serviceInstances) {
            for (int i = 0; i < VIRTUAL_NODE_COUNT; i++) {
                int hash = getHash(serviceInstance.getServiceAddress() + "#" + i);
                nodes.put(hash, serviceInstance);
            }
        }

        int hash = getHash(requestParams);

        Map.Entry<Integer, ServiceInstance> entry = nodes.ceilingEntry(hash);
        if (entry == null) {
            entry = nodes.firstEntry();
        }

        return entry.getValue();
    }

    private int getHash(Object key) {
        return key.hashCode();
    }
}