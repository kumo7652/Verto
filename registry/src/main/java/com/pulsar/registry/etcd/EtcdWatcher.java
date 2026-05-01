package com.pulsar.registry.etcd;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.pulsar.exception.RegistryException;
import com.pulsar.exception.RpcErrorCode;
import com.pulsar.model.ServiceNode;
import com.pulsar.registry.cache.DefaultServiceCache;
import com.pulsar.registry.cache.ServiceCache;
import com.pulsar.utils.ThreadPoolBuilder;
import io.etcd.jetcd.*;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.watch.WatchEvent;
import io.etcd.jetcd.watch.WatchResponse;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
class EtcdWatcher {
    private static final String SCHEDULER_POOL_NAME = "watcher-reconnect";
    private static final String DISCOVER_POOL_NAME = "watcher-discover";

    private final KV kvClient;
    private final Watch watchClient;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService discoverExecutor;
    private final long requestTimeout;
    private volatile boolean closed = false;

    private final ServiceCache serviceCache = new DefaultServiceCache();
    private final Map<String, WatchContext> watchContexts = new ConcurrentHashMap<>();

    EtcdWatcher(KV kvClient, Watch watchClient, long requestTimeout) {
        this.kvClient = kvClient;
        this.watchClient = watchClient;
        this.requestTimeout = requestTimeout;

        this.scheduler = (ScheduledExecutorService) ThreadPoolBuilder
                .forName(SCHEDULER_POOL_NAME)
                .scheduled(2)
                .build();
        this.discoverExecutor = ThreadPoolBuilder
                .forName(DISCOVER_POOL_NAME)
                .coreThreads(2)
                .maximumThreads(4)
                .queueSize(256)
                .build();
    }

    List<ServiceNode> discover(String serviceKey) throws RegistryException {
        if (StrUtil.isBlank(serviceKey)) {
            throw new RegistryException(RpcErrorCode.DISCOVERY_FAILED, "serviceKey is empty");
        }

        List<ServiceNode> nodes = serviceCache.get(serviceKey);
        if (nodes != null) {
            return new ArrayList<>(nodes);
        }

        WatchContext watchContext = watchContexts.computeIfAbsent(serviceKey,
                key -> new WatchContext());
        try {
            nodes = getServices(serviceKey);
            serviceCache.put(serviceKey, nodes);
            watchContext.resetBackoff();
        } catch (Exception e) {
            log.error("服务监听流首次拉取服务[{}]失败", serviceKey, e);
            throw new RegistryException(RpcErrorCode.DISCOVERY_FAILED, "failed pulling services: " + e.getMessage());
        }

        startWatching(serviceKey);
        return new ArrayList<>(nodes);
    }

    CompletableFuture<List<ServiceNode>> discoverAsync(String serviceKey) {
        return CompletableFuture.supplyAsync(() -> discover(serviceKey), discoverExecutor);
    }

    void destroy() {
        closed = true;
        watchContexts.forEach((serviceKey, watchContext) -> {
            closeWatcher(watchContext);
            cancelHealthCheck(watchContext);
            cancelResync(watchContext);
        });
        watchContexts.clear();
        serviceCache.invalidateAll();
        discoverExecutor.shutdown();
        ThreadPoolBuilder.shutdown(DISCOVER_POOL_NAME);
        ThreadPoolBuilder.shutdown(SCHEDULER_POOL_NAME);
    }

    private void startWatching(String serviceKey) {
        WatchContext watchContext = watchContexts.get(serviceKey);
        startWatch(serviceKey, watchContext);
        scheduleHealthCheck(serviceKey, watchContext);
        scheduleResync(serviceKey, watchContext);
    }

    // ==================== Watch 监听 ====================

    private void startWatch(String serviceKey, WatchContext context) {
        final long generation = context.generation;

        ByteSequence prefixKey = ByteSequence.from(
                EtcdConstants.ETCD_ROOT_PATH + serviceKey + "/", StandardCharsets.UTF_8);
        WatchOption watchOption = WatchOption.builder()
                .isPrefix(true)
                .withPrevKV(true)
                .build();

        AtomicReference<Watch.Watcher> watcherRef = new AtomicReference<>();

        Watch.Watcher watcher = watchClient.watch(prefixKey, watchOption, new Watch.Listener() {
            @Override
            public void onNext(WatchResponse response) {
                if (context.generation != generation) return;
                for (WatchEvent event : response.getEvents()) {
                    if (context.generation != generation) return;
                    handleWatchEvent(serviceKey, event);
                }
            }

            @Override
            public void onError(Throwable t) {
                if (context.generation != generation) return;
                closeWatcher(context);
                log.warn("Watch[{}]会话死亡: {}", serviceKey, t.getMessage());
            }

            @Override
            public void onCompleted() {
                if (context.generation != generation) return;
                closeWatcher(context);
                log.warn("Watch[{}]会话被服务端关闭", serviceKey);
            }
        });

        watcherRef.set(watcher);
        context.watcher = watcherRef;
    }

    private void handleWatchEvent(String serviceKey, WatchEvent event) {
        switch (event.getEventType()) {
            case PUT -> {
                ServiceNode node = JSONUtil.toBean(
                        event.getKeyValue().getValue().toString(StandardCharsets.UTF_8),
                        ServiceNode.class);
                if (event.getPrevKV() != null && event.getPrevKV().getValue() != null) {
                    serviceCache.updateNode(serviceKey, node);
                } else {
                    serviceCache.addNode(serviceKey, node);
                }
            }
            case DELETE -> {
                KeyValue prevKV = event.getPrevKV();
                if (prevKV != null && prevKV.getValue() != null) {
                    ServiceNode node = JSONUtil.toBean(
                            prevKV.getValue().toString(StandardCharsets.UTF_8),
                            ServiceNode.class);
                    serviceCache.removeNode(serviceKey, node);
                } else {
                    log.warn("Watch[{}]DELETE事件缺少prevKV，无法确定被删除的节点", serviceKey);
                }
            }
            default -> log.warn("未处理的 Watch 事件类型: {}", event.getEventType());
        }
    }

    // ==================== 健康检查 ====================

    private void scheduleHealthCheck(String serviceKey, WatchContext watchContext) {
        watchContext.healthCheckFuture = scheduler.scheduleAtFixedRate(() -> healthCheck(serviceKey),
                EtcdConstants.HEALTH_CHECK_INTERVAL_MS, EtcdConstants.HEALTH_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void healthCheck(String serviceKey) {
        WatchContext context = watchContexts.get(serviceKey);
        if (context == null || closed) return;

        boolean sessionDead = context.sessionDead.get();
        boolean probeFailed = false;

        if (!sessionDead) {
            probeFailed = !probeEtcd(serviceKey);
        }

        if (sessionDead || probeFailed) {
            if (!context.tryEnterReconnect()) return;
            log.warn("Watch[{}]健康检查失败(sessionDead={}, probeFailed={})，触发重连",
                    serviceKey, sessionDead, probeFailed);
            closeWatcher(context);
            cancelHealthCheck(context);
            cancelResync(context);
            scheduleWatchRestart(serviceKey, context.backoff());
        }
    }

    private void cancelHealthCheck(WatchContext watchContext) {
        ScheduledFuture<?> future = watchContext.healthCheckFuture;
        if (future != null) {
            future.cancel(false);
            watchContext.healthCheckFuture = null;
        }
    }

    private boolean probeEtcd(String serviceKey) {
        try {
            ByteSequence probeKey = ByteSequence.from(
                    EtcdConstants.ETCD_ROOT_PATH + serviceKey + "/", StandardCharsets.UTF_8);
            kvClient.get(probeKey, GetOption.builder().isPrefix(true).withLimit(1).build())
                    .get(EtcdConstants.PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== Watch 重连 ====================

    private void scheduleWatchRestart(String serviceKey, long delay) {
        scheduler.schedule(() -> {
            if (closed) return;
            WatchContext watchContext = watchContexts.get(serviceKey);
            if (watchContext == null) return;
            restartWatch(serviceKey, watchContext);
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void restartWatch(String serviceKey, WatchContext watchContext) {
        watchContext.generation++;
        closeWatcher(watchContext);
        cancelHealthCheck(watchContext);
        cancelResync(watchContext);

        try {
            List<ServiceNode> nodes = getServices(serviceKey);
            serviceCache.put(serviceKey, nodes);
            watchContext.resetBackoff();
            watchContext.reconnectCount.set(0);
        } catch (Exception e) {
            log.error("Watch[{}]重连List失败", serviceKey, e);
            long delay = watchContext.backoff();
            if (delay >= EtcdConstants.RECONNECT_MAX_DELAY_MS
                    && watchContext.reconnectCount.incrementAndGet() > EtcdConstants.RECONNECT_MAX_ATTEMPTS) {
                log.error("Watch[{}]达到最大重连次数，放弃", serviceKey);
                watchContexts.remove(serviceKey);
                watchContext.exitReconnect();
                return;
            }
            watchContext.exitReconnect();
            scheduleWatchRestart(serviceKey, delay);
            return;
        }

        startWatch(serviceKey, watchContext);
        scheduleHealthCheck(serviceKey, watchContext);
        scheduleResync(serviceKey, watchContext);
        watchContext.exitReconnect();
        log.info("Watch[{}]重连成功", serviceKey);
    }

    // ==================== Resync 全量对账 ====================

    private void scheduleResync(String serviceKey, WatchContext watchContext) {
        watchContext.resyncFuture = scheduler.scheduleAtFixedRate(
                () -> resync(serviceKey),
                EtcdConstants.RESYNC_INTERVAL_MS, EtcdConstants.RESYNC_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void resync(String serviceKey) {
        WatchContext watchContext = watchContexts.get(serviceKey);
        if (watchContext == null || closed) return;

        try {
            List<ServiceNode> fresh = getServices(serviceKey);
            List<ServiceNode> cached = serviceCache.get(serviceKey);
            if (cached == null) cached = Collections.emptyList();

            Map<String, ServiceNode> freshMap = toMap(fresh);
            Map<String, ServiceNode> cachedMap = toMap(cached);

            List<ServiceNode> added = new ArrayList<>();
            List<ServiceNode> removed = new ArrayList<>();
            List<ServiceNode> updated = new ArrayList<>();

            for (Map.Entry<String, ServiceNode> entry : freshMap.entrySet()) {
                ServiceNode old = cachedMap.get(entry.getKey());
                if (old == null) {
                    added.add(entry.getValue());
                } else if (!old.equals(entry.getValue())) {
                    updated.add(entry.getValue());
                }
            }
            for (Map.Entry<String, ServiceNode> entry : cachedMap.entrySet()) {
                if (!freshMap.containsKey(entry.getKey())) {
                    removed.add(entry.getValue());
                }
            }

            if (added.isEmpty() && removed.isEmpty() && updated.isEmpty()) {
                return;
            }

            for (ServiceNode node : added) serviceCache.addNode(serviceKey, node);
            for (ServiceNode node : removed) serviceCache.removeNode(serviceKey, node);
            for (ServiceNode node : updated) serviceCache.updateNode(serviceKey, node);

            log.debug("Watch[{}]resync: +{} -{} ~{}", serviceKey, added.size(), removed.size(), updated.size());
        } catch (Exception e) {
            log.warn("Watch[{}]resync失败，触发重连", serviceKey, e);
            if (!watchContext.tryEnterReconnect()) return;
            closeWatcher(watchContext);
            cancelHealthCheck(watchContext);
            cancelResync(watchContext);
            scheduleWatchRestart(serviceKey, watchContext.backoff());
        }
    }

    // ==================== 辅助方法 ====================

    List<ServiceNode> getServices(String serviceKey) throws Exception {
        ByteSequence prefixKey = ByteSequence.from(
                EtcdConstants.ETCD_ROOT_PATH + serviceKey + "/", StandardCharsets.UTF_8);
        GetOption getOption = GetOption.builder().isPrefix(true).build();

        GetResponse response = kvClient.get(prefixKey, getOption)
                .get(requestTimeout, TimeUnit.MILLISECONDS);

        return response.getKvs().stream()
                .map(kv -> JSONUtil.toBean(
                        kv.getValue().toString(StandardCharsets.UTF_8),
                        ServiceNode.class))
                .toList();
    }

    private void closeWatcher(WatchContext context) {
        if (context.watcher == null) {
            return;
        }

        Watch.Watcher watcher = context.watcher.getAndSet(null);
        if (watcher != null) {
            try {
                watcher.close();
            } catch (Exception ignored) {}
        }
        context.watcher = null;
        context.sessionDead.set(false);
    }

    private void cancelResync(WatchContext watchContext) {
        ScheduledFuture<?> future = watchContext.resyncFuture;
        if (future != null) {
            future.cancel(false);
            watchContext.resyncFuture = null;
        }
    }

    private Map<String, ServiceNode> toMap(List<ServiceNode> nodes) {
        Map<String, ServiceNode> map = new HashMap<>();
        for (ServiceNode node : nodes) {
            map.put(node.getServiceNodeKey(), node);
        }
        return map;
    }

    private static class WatchContext {
        volatile AtomicReference<Watch.Watcher> watcher;
        final AtomicBoolean sessionDead = new AtomicBoolean(false);
        volatile long generation = 0;
        final AtomicLong backoffMs = new AtomicLong(EtcdConstants.RECONNECT_INITIAL_DELAY_MS);
        final AtomicBoolean reconnecting = new AtomicBoolean(false);
        final AtomicInteger reconnectCount = new AtomicInteger(0);
        volatile ScheduledFuture<?> healthCheckFuture;
        volatile ScheduledFuture<?> resyncFuture;

        void resetBackoff() { backoffMs.set(EtcdConstants.RECONNECT_INITIAL_DELAY_MS); }

        long backoff() {
            return backoffMs.getAndUpdate(current ->
                    Math.min((long) (current * EtcdConstants.RECONNECT_MULTIPLIER), EtcdConstants.RECONNECT_MAX_DELAY_MS));
        }

        boolean tryEnterReconnect() { return reconnecting.compareAndSet(false, true); }
        void exitReconnect() { reconnecting.set(false); }
    }
}
