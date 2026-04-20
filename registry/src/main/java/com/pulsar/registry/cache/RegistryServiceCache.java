package com.pulsar.registry.cache;

import com.pulsar.registry.model.ServiceInstance;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RegistryServiceCache {
    private final Map<String, List<ServiceInstance>> serviceCache = new ConcurrentHashMap<>();

    public void writeCache(String serviceKeyPrefix, List<ServiceInstance> serviceInstances) {
        serviceCache.put(serviceKeyPrefix, serviceInstances);
    }

    public List<ServiceInstance> readCache(String serviceKeyPrefix) {
        return serviceCache.get(serviceKeyPrefix);
    }

    public void clearCache() {
        serviceCache.clear();
    }
}