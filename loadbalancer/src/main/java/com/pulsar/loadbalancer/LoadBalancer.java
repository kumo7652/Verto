package com.pulsar.loadbalancer;

import com.pulsar.model.ServiceNode;

import java.util.List;
import java.util.Map;

public interface LoadBalancer {
    ServiceNode select(Map<String, Object> requestParams, List<ServiceNode> serviceNodes);
}