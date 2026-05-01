package com.pulsar.registry;

import com.pulsar.exception.RegistryException;
import com.pulsar.model.ServiceNode;
import com.pulsar.registry.config.RegistryConfig;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface Registry {
    void init(RegistryConfig config);

    void destroy();

    void register(ServiceNode serviceNode) throws RegistryException;

    void unregister(ServiceNode serviceNode) throws RegistryException;

    List<ServiceNode> discover(String serviceKey);

    CompletableFuture<List<ServiceNode>> discoverAsync(String serviceKey);
}
