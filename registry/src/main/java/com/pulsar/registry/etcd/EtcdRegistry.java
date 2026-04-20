package com.pulsar.registry.etcd;

import cn.hutool.core.collection.ConcurrentHashSet;
import cn.hutool.json.JSONUtil;
import com.pulsar.extension.SpiExtension;
import com.pulsar.registry.Registry;
import com.pulsar.registry.cache.RegistryServiceCache;
import com.pulsar.registry.config.RegistryConfig;
import com.pulsar.registry.model.ServiceInstance;
import io.etcd.jetcd.*;
import io.etcd.jetcd.lease.LeaseKeepAliveResponse;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.PutOption;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.watch.WatchEvent;
import io.etcd.jetcd.watch.WatchResponse;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@SpiExtension(name = "etcd")
public class EtcdRegistry implements Registry {
    private Client client;
    private KV kvClient;
    private Lease leaseClient;

    private static final String ETCD_ROOT_PATH = "/rpc/";
    private static final long DEFAULT_LEASE_TTL = 30L;
    private static final int RECONNECT_DELAY_SECONDS = 2;
    private static final int WATCH_RECONNECT_DELAY_SECONDS = 5;

    private final Map<String, Long> registeredServiceNodeKeys = new ConcurrentHashMap<>();
    private final RegistryServiceCache serviceCache = new RegistryServiceCache();
    private final Set<String> watchingKeys = new ConcurrentHashSet<>();
    private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);

    @Override
    public void init(RegistryConfig registryConfig) {
        client = Client.builder()
                .endpoints(registryConfig.getRegistryAddress())
                .connectTimeout(Duration.ofMillis(registryConfig.getTimeout()))
                .build();

        kvClient = client.getKVClient();
        leaseClient = client.getLeaseClient();
    }

    @Override
    public void register(ServiceInstance serviceInstance) throws Exception {
        long leaseId = leaseClient.grant(DEFAULT_LEASE_TTL).get().getID();

        leaseClient.keepAlive(leaseId, new StreamObserver<>() {
            @Override
            public void onNext(LeaseKeepAliveResponse value) {
                log.info("服务[{}]续约成功, TTL: {}", serviceInstance.getServiceName(), value.getTTL());
            }

            @Override
            public void onError(Throwable t) {
                log.error("服务[{}]续约异常，即将尝试重新注册", serviceInstance.getServiceName(), t);
                executorService.schedule(() -> {
                    try {
                        log.info("正在尝试为服务[{}]进行故障恢复（重新注册）...", serviceInstance.getServiceName());
                        register(serviceInstance);
                        log.info("服务[{}]故障恢复成功！", serviceInstance.getServiceName());
                    } catch (Exception e) {
                        log.error("服务[{}]故障恢复失败", serviceInstance.getServiceName(), e);
                    }
                }, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
            }

            @Override
            public void onCompleted() {
                log.info("服务[{}]续约流关闭", serviceInstance.getServiceName());
            }
        });

        String registryKey = ETCD_ROOT_PATH + serviceInstance.getServiceNodeKey();
        ByteSequence key = ByteSequence.from(registryKey, StandardCharsets.UTF_8);
        ByteSequence value = ByteSequence.from(JSONUtil.toJsonStr(serviceInstance), StandardCharsets.UTF_8);

        PutOption putOption = PutOption.builder().withLeaseId(leaseId).build();
        kvClient.put(key, value, putOption).get();
        registeredServiceNodeKeys.put(registryKey, leaseId);
    }

    @Override
    public void unregister(ServiceInstance serviceInstance) throws ExecutionException, InterruptedException {
        String registryKey = ETCD_ROOT_PATH + serviceInstance.getServiceNodeKey();
        kvClient.delete(ByteSequence.from(registryKey, StandardCharsets.UTF_8)).get();
        registeredServiceNodeKeys.remove(registryKey);
    }

    @Override
    public List<ServiceInstance> serviceDiscovery(String serviceKey) {
        List<ServiceInstance> serviceFromCache = serviceCache.readCache(serviceKey);
        if (serviceFromCache != null && !serviceFromCache.isEmpty()) {
            return serviceFromCache;
        }

        String prefix = ETCD_ROOT_PATH + serviceKey + "/";
        try {
            GetOption getOption = GetOption.builder().isPrefix(true).build();
            List<KeyValue> keyValues = kvClient.get(
                    ByteSequence.from(prefix, StandardCharsets.UTF_8),
                    getOption
            ).get().getKvs();

            List<ServiceInstance> serviceInstances = keyValues.stream()
                    .map(keyValue -> {
                        String value = keyValue.getValue().toString(StandardCharsets.UTF_8);
                        return JSONUtil.toBean(value, ServiceInstance.class);
                    })
                    .toList();

            watch(prefix);
            serviceCache.writeCache(serviceKey, serviceInstances);
            return serviceInstances;
        } catch (Exception e) {
            throw new RuntimeException("获取服务列表失败", e);
        }
    }

    @Override
    public void destroy() {
        log.warn("当前节点下线");

        for (String key : registeredServiceNodeKeys.keySet()) {
            try {
                kvClient.delete(ByteSequence.from(key, StandardCharsets.UTF_8)).get();
            } catch (Exception e) {
                log.error("节点下线失败");
            }
        }

        if (kvClient != null) kvClient.close();
        if (leaseClient != null) leaseClient.close();
        if (client != null) client.close();
    }

    @Override
    public void watch(String servicePrefix) {
        Watch watchClient = client.getWatchClient();
        boolean newWatch = watchingKeys.add(servicePrefix);

        if (newWatch) {
            WatchOption watchOption = WatchOption.builder().isPrefix(true).build();

            Watch.Listener listener = new Watch.Listener() {
                @Override
                public void onNext(WatchResponse response) {
                    for (WatchEvent event : response.getEvents()) {
                        String key = event.getKeyValue().getValue().toString(StandardCharsets.UTF_8);

                        switch (event.getEventType()) {
                            case PUT:
                                log.info("注册中心发现新节点上线或更新: {}", key);
                                serviceCache.clearCache();
                                break;
                            case DELETE:
                                log.warn("注册中心检测到节点下线: {}", key);
                                serviceCache.clearCache();
                                break;
                            default:
                                break;
                        }
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    log.error("Watch 监控流异常中断, 路径: {}, 异常信息: {}",
                            servicePrefix, throwable.getMessage());
                    watchingKeys.remove(servicePrefix);
                    executorService.schedule(() -> watch(servicePrefix), WATCH_RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
                }

                @Override
                public void onCompleted() {
                    log.info("Watch 监控流正常关闭, 路径: {}", servicePrefix);
                }
            };

            ByteSequence prefix = ByteSequence.from(servicePrefix, StandardCharsets.UTF_8);
            watchClient.watch(prefix, watchOption, listener);
        }
    }
}