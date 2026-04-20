package com.pulsar.loadbalancer;

import com.pulsar.registry.model.ServiceInstance;

import java.util.List;
import java.util.Map;

public interface LoadBalancer {
    ServiceInstance select(Map<String, Object> requestParams, List<ServiceInstance> serviceInstances);
}