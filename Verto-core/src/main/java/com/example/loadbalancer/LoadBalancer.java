package com.example.loadbalancer;

import com.example.registry.model.ServiceInstance;

import java.util.List;
import java.util.Map;

public interface LoadBalancer {
    ServiceInstance select(Map<String, Object> requestParams, List<ServiceInstance> serviceInstances);
}