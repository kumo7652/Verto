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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <h3>Etcd 服务发现（Watch + 轮询混合模式）</h3>
 * <p>
 * 策略：
 * <ul>
 *   <li><b>WATCH 模式</b>：Watch 流实时推送变更</li>
 *   <li><b>POLL 模式</b>：Watch 断开后全量轮询拉取，成功后切回 WATCH</li>
 *   <li><b>切换触发</b>：onError / onCompleted → POLL；轮询成功 → WATCH</li>
 * </ul>
 * </p>
 */
@Slf4j
class EtcdWatcher {
    /** etcd客户端 */
    private final KV kvClient;
    private final Watch watchClient;

    /** 线程池 */
    private final ScheduledExecutorService scheduler;
    private final ExecutorService discoverExecutor;
    private static final String SCHEDULER_POOL_NAME = "watcher-scheduler";
    private static final String DISCOVER_POOL_NAME = "watcher-discover";

    /** 请求超时时间 */
    private final long requestTimeout;

    /** 服务节点本地缓存 */
    private final ServiceCache serviceCache = new DefaultServiceCache();

    /** 服务键对应监听上下文 */
    private final Map<String, WatchContext> contexts = new ConcurrentHashMap<>();

    private volatile boolean closed = false;

    EtcdWatcher(KV kvClient, Watch watchClient, long requestTimeout) {
        this.kvClient = kvClient;
        this.watchClient = watchClient;
        this.requestTimeout = requestTimeout;
        this.scheduler = ThreadPoolBuilder
                .forName(SCHEDULER_POOL_NAME)
                .coreThreads(2)
                .buildScheduled();
        this.discoverExecutor = ThreadPoolBuilder
                .forName(DISCOVER_POOL_NAME)
                .coreThreads(2)
                .maximumThreads(4)
                .queueSize(256)
                .build();
    }

    /**
     * <h3>发现服务节点</h3>
     * 优先从缓存获取，缓存不存在则全量拉取并进入 Watch 模式
     *
     * @param serviceKey 服务标识，如 order-service:1.0
     * @return 服务节点列表
     * @throws RegistryException 拉取服务失败
     */
    List<ServiceNode> discover(String serviceKey) throws RegistryException {
        if (StrUtil.isBlank(serviceKey)) {
            throw new RegistryException(RpcErrorCode.DISCOVERY_FAILED, "serviceKey is empty");
        }

        List<ServiceNode> cached = serviceCache.get(serviceKey);
        if (cached != null) {
            return new ArrayList<>(cached);
        }

        WatchContext context = contexts.computeIfAbsent(serviceKey, k -> new WatchContext());

        List<ServiceNode> doubleCheck = serviceCache.get(serviceKey);
        if (doubleCheck != null) {
            return new ArrayList<>(doubleCheck);
        }

        try {
            List<ServiceNode> nodes = fullSync(serviceKey);
            serviceCache.put(serviceKey, nodes);
            context.resetBackoff();
            switchToWatchMode(serviceKey, context);
            return new ArrayList<>(nodes);
        } catch (Exception e) {
            log.error("首次拉取服务[{}]失败", serviceKey, e);
            contexts.remove(serviceKey);
            throw new RegistryException(RpcErrorCode.DISCOVERY_FAILED,
                    "failed pulling services: " + e.getMessage());
        }
    }

    /**
     * <h3>异步发现服务节点</h3>
     *
     * @param serviceKey 服务标识
     * @return 异步结果
     */
    CompletableFuture<List<ServiceNode>> discoverAsync(String serviceKey) {
        return CompletableFuture.supplyAsync(() -> discover(serviceKey), discoverExecutor);
    }

    /**
     * <h3>销毁监听器</h3>
     * 关闭所有 Watch 流、取消重试调度并清理资源
     */
    void destroy() {
        closed = true;
        contexts.values().forEach(context -> {
            closeWatcher(context);
            stopRetry(context);
        });
        contexts.clear();
        serviceCache.invalidateAll();
        discoverExecutor.shutdown();
        ThreadPoolBuilder.shutdown(DISCOVER_POOL_NAME);
        ThreadPoolBuilder.shutdown(SCHEDULER_POOL_NAME);
    }

    /**
     * <h3>切换到 Watch 模式</h3>
     * 停止轮询重试，建立 Watch 流实时监听变更
     *
     * @param serviceKey 服务标识
     * @param context    监听上下文
     */
    private void switchToWatchMode(String serviceKey, WatchContext context) {
        stopRetry(context);
        context.mode = WatchContext.Mode.WATCH;
        startWatch(serviceKey, context);
        log.info("Watch[{}]进入 Watch 模式", serviceKey);
    }

    /**
     * <h3>切换到轮询模式</h3>
     * Watch 流断开时调用，关闭 Watch 流并启动全量轮询重试
     *
     * @param serviceKey 服务标识
     * @param context    监听上下文
     */
    private void switchToPollMode(String serviceKey, WatchContext context) {
        if (context.mode == WatchContext.Mode.POLL) return;
        context.mode = WatchContext.Mode.POLL;
        closeWatcher(context);
        log.info("Watch[{}]降级为轮询模式", serviceKey);
        retrySync(serviceKey, context);
    }

    /**
     * <h3>建立 Watch 流</h3>
     * 监听指定服务前缀的变更事件，onError/onCompleted 时自动降级为轮询模式
     *
     * @param serviceKey 服务标识
     * @param context    监听上下文
     */
    private void startWatch(String serviceKey, WatchContext context) {
        ByteSequence prefixKey = buildPrefixKey(serviceKey);
        WatchOption option = WatchOption.builder()
                .isPrefix(true)
                .withPrevKV(true)
                .build();

        AtomicReference<Watch.Watcher> ref = new AtomicReference<>();
        Watch.Watcher watcher = watchClient.watch(prefixKey, option, new Watch.Listener() {
            @Override
            public void onNext(WatchResponse response) {
                if (context.mode != WatchContext.Mode.WATCH) return;
                for (WatchEvent event : response.getEvents()) {
                    handleEvent(serviceKey, event);
                }
            }

            @Override
            public void onError(Throwable t) {
                log.warn("Watch[{}]流异常: {}", serviceKey, t.getMessage());
                scheduler.execute(() -> switchToPollMode(serviceKey, context));
            }

            @Override
            public void onCompleted() {
                log.warn("Watch[{}]流被服务端关闭", serviceKey);
                scheduler.execute(() -> switchToPollMode(serviceKey, context));
            }
        });

        ref.set(watcher);
        context.watcher = ref;
    }

    /**
     * <h3>处理 Watch 事件</h3>
     * 根据 PUT/DELETE 事件类型对缓存进行增量更新
     *
     * @param serviceKey 服务标识
     * @param event      Watch 事件
     */
    private void handleEvent(String serviceKey, WatchEvent event) {
        switch (event.getEventType()) {
            case PUT -> {
                ServiceNode node = parseNode(event.getKeyValue().getValue());
                if (node == null) return;
                if (event.getPrevKV() != null && event.getPrevKV().getValue() != null
                        && !event.getPrevKV().getValue().isEmpty()) {
                    serviceCache.updateNode(serviceKey, node);
                } else {
                    serviceCache.addNode(serviceKey, node);
                }
            }
            case DELETE -> {
                KeyValue prevKV = event.getPrevKV();
                if (prevKV != null && prevKV.getValue() != null && !prevKV.getValue().isEmpty()) {
                    ServiceNode node = parseNode(prevKV.getValue());
                    if (node != null) {
                        serviceCache.removeNode(serviceKey, node);
                    }
                } else {
                    log.warn("Watch[{}]DELETE事件缺少prevKV，无法确定被删除的节点", serviceKey);
                }
            }
            default -> log.warn("未处理的 Watch 事件类型: {}", event.getEventType());
        }
    }

    // ==================== 轮询模式（降级） ====================

    /**
     * <h3>全量拉取重试</h3>
     * Watch 流断开后以指数退避间隔全量拉取服务列表，成功后切回 Watch 模式
     *
     * @param serviceKey 服务标识
     * @param context    监听上下文
     */
    private void retrySync(String serviceKey, WatchContext context) {
        if (closed || !contexts.containsKey(serviceKey)) return;
        if (context.mode != WatchContext.Mode.POLL) return;

        try {
            List<ServiceNode> nodes = fullSync(serviceKey);
            serviceCache.put(serviceKey, nodes);
            context.resetBackoff();
            switchToWatchMode(serviceKey, context);
        } catch (Exception e) {
            long delay = context.nextBackoff();
            log.warn("Watch[{}]轮询拉取失败，{}ms后重试: {}", serviceKey, delay, e.getMessage());
            context.retryFuture = scheduler.schedule(
                    () -> retrySync(serviceKey, context), delay, TimeUnit.MILLISECONDS);
        }
    }

    // ==================== 数据拉取 ====================

    /**
     * <h3>全量拉取服务节点</h3>
     * 通过 etcd KV 前缀查询获取指定服务的所有节点
     *
     * @param serviceKey 服务标识
     * @return 服务节点列表
     * @throws Exception 拉取超时或网络异常
     */
    private List<ServiceNode> fullSync(String serviceKey) throws Exception {
        ByteSequence prefixKey = buildPrefixKey(serviceKey);
        GetOption option = GetOption.builder().isPrefix(true).build();

        GetResponse response = kvClient.get(prefixKey, option)
                .get(requestTimeout, TimeUnit.MILLISECONDS);

        return response.getKvs().stream()
                .filter(kv -> kv.getValue() != null && !kv.getValue().isEmpty())
                .map(kv -> parseNode(kv.getValue()))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * <h3>解析服务节点</h3>
     * 将 etcd 中的字节值反序列化为 ServiceNode，解析失败时返回 null
     *
     * @param value etcd 键值
     * @return 服务节点，解析失败返回 null
     */
    private ServiceNode parseNode(ByteSequence value) {
        try {
            return JSONUtil.toBean(value.toString(StandardCharsets.UTF_8), ServiceNode.class);
        } catch (Exception e) {
            log.warn("解析服务节点失败", e);
            return null;
        }
    }

    // ==================== 辅助方法 ====================

    private ByteSequence buildPrefixKey(String serviceKey) {
        return ByteSequence.from(
                EtcdConstants.ETCD_ROOT_PATH + serviceKey + "/",
                StandardCharsets.UTF_8);
    }

    /**
     * <h3>关闭 Watch 流</h3>
     * 原子地取出并关闭 Watcher，防止重复关闭
     *
     * @param context 监听上下文
     */
    private void closeWatcher(WatchContext context) {
        AtomicReference<Watch.Watcher> ref = context.watcher;
        if (ref != null) {
            Watch.Watcher w = ref.getAndSet(null);
            if (w != null) {
                try { w.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * <h3>停止轮询重试</h3>
     * 取消待执行的重试调度
     *
     * @param context 监听上下文
     */
    private void stopRetry(WatchContext context) {
        ScheduledFuture<?> future = context.retryFuture;
        if (future != null) {
            future.cancel(false);
            context.retryFuture = null;
        }
    }

    // ==================== 内部类 ====================

    /**
     * <h3>服务监听上下文</h3>
     * 维护单个服务的监听模式、Watch 句柄和退避状态
     */
    private static class WatchContext {
        enum Mode { WATCH, POLL }

        /** 当前监听模式 */
        volatile Mode mode = Mode.WATCH;
        /** Watch 流句柄，用于关闭 */
        AtomicReference<Watch.Watcher> watcher;
        /** 退避延迟（毫秒），成功后重置 */
        final AtomicLong backoffMs = new AtomicLong(EtcdConstants.RECONNECT_INITIAL_DELAY_MS);
        /** 重试调度 Future，用于取消 */
        volatile ScheduledFuture<?> retryFuture;

        /**
         * <h3>重置退避延迟</h3>
         * 将退避延迟恢复为初始值，在拉取成功后调用
         */
        void resetBackoff() { backoffMs.set(EtcdConstants.RECONNECT_INITIAL_DELAY_MS); }

        /**
         * <h3>计算下一次退避延迟</h3>
         * 按乘数指数增长，上限为最大退避时间，返回增长前的旧值用于本次调度
         *
         * @return 本次调度应使用的退避延迟（毫秒）
         */
        long nextBackoff() {
            return backoffMs.getAndUpdate(current ->
                    Math.min((long) (current * EtcdConstants.RECONNECT_MULTIPLIER),
                            EtcdConstants.RECONNECT_MAX_DELAY_MS));
        }
    }
}
