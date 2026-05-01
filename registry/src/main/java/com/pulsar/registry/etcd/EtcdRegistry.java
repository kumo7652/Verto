package com.pulsar.registry.etcd;

import com.pulsar.exception.RegistryException;
import com.pulsar.extension.SpiExtension;
import com.pulsar.model.ServiceNode;
import com.pulsar.registry.Registry;
import com.pulsar.registry.config.RegistryConfig;
import io.etcd.jetcd.*;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@SpiExtension(name = "etcd")
public class EtcdRegistry implements Registry {
    private volatile Client client;
    private volatile EtcdRegistrar registrar;
    private volatile EtcdWatcher watcher;

    @Override
    public void init(RegistryConfig registryConfig) {
        long connectTimeout = registryConfig.getConnectTimeout();
        long requestTimeout = registryConfig.getRequestTimeout();

        client = Client.builder()
                .endpoints(registryConfig.getRegistryAddress())
                .connectTimeout(Duration.ofMillis(connectTimeout))
                .build();

        KV kvClient = client.getKVClient();
        Lease leaseClient = client.getLeaseClient();
        Watch watchClient = client.getWatchClient();

        registrar = new EtcdRegistrar(kvClient, leaseClient, requestTimeout);
        watcher = new EtcdWatcher(kvClient, watchClient, requestTimeout);
    }

    @Override
    public void destroy() {
        if (watcher != null) watcher.destroy();
        if (registrar != null) registrar.destroy();

        try {
            client.close();
        } catch (Exception e) {
            log.error("关闭 etcd 客户端失败", e);
        }
    }

    @Override
    public void register(ServiceNode serviceNode) throws RegistryException {
        registrar.register(serviceNode);
    }

    @Override
    public void unregister(ServiceNode serviceNode) throws RegistryException {
        registrar.unregister(serviceNode);
    }

    @Override
    public List<ServiceNode> discover(String serviceKey) {
        return watcher.discover(serviceKey);
    }

    @Override
    public CompletableFuture<List<ServiceNode>> discoverAsync(String serviceKey) {
        return watcher.discoverAsync(serviceKey);
    }
}
