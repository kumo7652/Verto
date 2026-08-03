package com.pulsar.registry.etcd;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pulsar.exception.RegistryException;
import com.pulsar.extension.SpiExtension;
import com.pulsar.model.ServiceNode;
import com.pulsar.registry.Registry;
import com.pulsar.config.RegistryConfig;
import io.etcd.jetcd.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@SpiExtension(name = "etcd")
public class EtcdRegistry implements Registry {

    private static final Logger log = LoggerFactory.getLogger(EtcdRegistry.class);
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
                .keepaliveTime(Duration.ofMillis(30_000L))
                .keepaliveTimeout(Duration.ofMillis(5_000L))
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
