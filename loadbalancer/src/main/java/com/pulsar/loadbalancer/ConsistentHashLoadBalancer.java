package com.pulsar.loadbalancer;

import com.pulsar.extension.SpiExtension;
import com.pulsar.model.ServiceNode;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@SpiExtension(name = "consistentHash")
public class ConsistentHashLoadBalancer implements LoadBalancer {
    private final TreeMap<Integer, ServiceNode> nodes = new TreeMap<>();
    private static final int VIRTUAL_NODE_COUNT = 100;

    @Override
    public ServiceNode select(Map<String, Object> requestParams, List<ServiceNode> serviceNodes) {
        if (serviceNodes.isEmpty()) {
            return null;
        }

        for (ServiceNode serviceNode : serviceNodes) {
            for (int i = 0; i < VIRTUAL_NODE_COUNT; i++) {
                int hash = getHash(serviceNode.getServiceAddress() + "#" + i);
                nodes.put(hash, serviceNode);
            }
        }

        int hash = getHash(requestParams);

        Map.Entry<Integer, ServiceNode> entry = nodes.ceilingEntry(hash);
        if (entry == null) {
            entry = nodes.firstEntry();
        }

        return entry.getValue();
    }

    private int getHash(Object key) {
        return key.hashCode();
    }
}