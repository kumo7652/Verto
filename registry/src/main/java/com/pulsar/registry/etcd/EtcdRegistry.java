package com.pulsar.registry.etcd;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.pulsar.exception.RegistryException;
import com.pulsar.exception.RpcErrorCode;
import com.pulsar.extension.SpiExtension;
import com.pulsar.model.ServiceNode;
import com.pulsar.registry.Registry;
import com.pulsar.registry.ServiceListener;
import com.pulsar.registry.cache.DefaultServiceCache;
import com.pulsar.registry.cache.ServiceCache;
import com.pulsar.registry.config.RegistryConfig;
import com.pulsar.registry.event.ChangeType;
import com.pulsar.registry.event.ServiceChangeEvent;
import com.pulsar.utils.ThreadPoolBuilder;
import io.etcd.jetcd.*;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.lease.LeaseKeepAliveResponse;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.support.CloseableClient;
import io.grpc.stub.StreamObserver;
import io.etcd.jetcd.options.PutOption;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.watch.WatchEvent;
import io.etcd.jetcd.watch.WatchResponse;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 etcd 的注册中心实现
 *
 * <h2>etcd 数据模型</h2>
 * <pre>
 * Key:   /rpc/service/{serviceName}:{version}/{nodeId}
 * Value: ServiceNode JSON（serviceName, version, host, port, nodeId）
 * </pre>
 * 每个 Key 绑定一个 Lease（TTL=30s），续约流中断后 Key 随租约过期自动删除，
 * 实现"心跳停止即下线"的语义。
 *
 * <h2>核心流程</h2>
 * <ul>
 *   <li><b>注册</b>：Lease.grant → KV.put(lease) → Lease.keepAlive（双向流心跳）</li>
 *   <li><b>注销</b>：移除 LeaseContext + 关闭续约流 → KV.delete</li>
 *   <li><b>发现</b>：本地缓存优先 → 缓存未命中则 KV.get(prefix) → 写缓存 + 开启 Watch</li>
 *   <li><b>订阅</b>：Watch 监听前缀变更 → 增量更新缓存 → 通知 ServiceListener</li>
 * </ul>
 *
 * <h2>字段分类</h2>
 * <ul>
 *   <li><b>常量</b>：路径前缀、租约 TTL、重连退避参数等编译期常量</li>
 *   <li><b>etcd 客户端</b>：Client / KV / Lease / Watch 四个子客户端</li>
 *   <li><b>服务提供者字段</b>：节点 ID 计数器、租约上下文映射，服务于注册与续约</li>
 *   <li><b>服务消费者字段</b>：本地缓存、Watch 管理、监听器映射，服务于发现与订阅</li>
 *   <li><b>通用字段</b>：请求超时、重连线程池，注册与发现共用</li>
 * </ul>
 *
 * <h2>多节点设计</h2>
 * <p>虽然典型场景下单个服务提供者只注册一个 ServiceNode，但本实现使用 Map 结构
 * （{@link #nodeLeases}、{@link #nodeIdCounters}）支持同一实例注册多个节点，覆盖以下场景：
 * <ul>
 *   <li>同一进程暴露多种协议（如 gRPC + HTTP），对应不同 ServiceNode</li>
 *   <li>网关/聚合服务以多个 serviceName 注册</li>
 *   <li>同一服务多版本并行（v1.0 与 v2.0 共存）</li>
 * </ul>
 *
 * <h2>容错与重连</h2>
 * <p>续约流断开时自动触发指数退避重连（2s → 4s → 8s → ... → 30s），
 * 达到最大退避后最多重试 {@value #RECONNECT_MAX_ATTEMPTS} 次（约 5 分钟）。
 * 每次重连执行完整的 re-register（重新申请租约 + 写 KV + 重启心跳），
 * 由 {@link LeaseContext} 管理每个节点独立的退避状态和重连锁。
 *
 * <h2>线程安全</h2>
 * <ul>
 *   <li>{@link #nodeLeases}、{@link #serviceWatchers}、{@link #listeners} 使用 ConcurrentHashMap</li>
 *   <li>{@link LeaseContext#reconnecting} 使用 CAS 保证同一节点只有一个线程执行重连</li>
 *   <li>{@link WatchContext#reconnecting} 使用 CAS 保证同一服务 Watch 只有一个线程执行重连</li>
 *   <li>续约回调（onNext/onError）由 jetcd 内部 gRPC 线程触发，重连调度到 {@link #reconnectExecutor}</li>
 * </ul>
 *
 * @see Registry 注册中心接口
 * @see LeaseContext 节点租约上下文
 */
@Slf4j
@SpiExtension(name = "etcd")
public class EtcdRegistry implements Registry {
    // ==================== 常量 ====================

    /** 服务注册根路径，所有服务节点的 Key 均以此为前缀 */
    private static final String ETCD_ROOT_PATH = "/rpc/service/";

    /** 默认租约 TTL（秒），节点无心跳则在此时间后自动过期删除 */
    private static final long DEFAULT_LEASE_TTL = 30L;

    /** 心跳续约失败后，重连初始延迟（毫秒） */
    private static final long RECONNECT_INITIAL_DELAY_MS = 2000L;

    /** 指数退避最大延迟（毫秒） */
    private static final long RECONNECT_MAX_DELAY_MS = 30000L;

    /** 指数退避乘数 */
    private static final double RECONNECT_MULTIPLIER = 2.0;

    /** 达到最大退避后的最大重连次数（30s × 10 = 5min） */
    private static final int RECONNECT_MAX_ATTEMPTS = 10;

    // ==================== etcd 客户端 ====================

    /** etcd 客户端实例，所有子客户端的入口 */
    private volatile Client client;

    /** KV 客户端，用于读写服务注册数据 */
    private volatile KV kvClient;

    /** Lease 客户端，用于租约创建与心跳续约 */
    private volatile Lease leaseClient;

    /** Watch 客户端，用于监听服务节点变更事件 */
    private volatile Watch watchClient;

    // ==================== 服务提供者使用字段 ====================

    /**
     * 按 serviceName 递增的计数器，用于自动生成 nodeId
     * Key: serviceName，Value: 该服务下的自增序号
     * 生成的 nodeId 格式：{serviceName}-{三位编号}，如 order-service-001
     */
    private final Map<String, AtomicLong> nodeIdCounters = new ConcurrentHashMap<>();

    /** 节点租约上下文映射，Key 为 serviceNodeKey（如 order-service:1.0/order-001） */
    private final Map<String, LeaseContext> nodeLeases = new ConcurrentHashMap<>();

    // ==================== 服务消费者使用字段 ====================

    /**
     * 服务节点本地缓存
     * <p>使用 Caffeine + nodeIndex 双层缓存实现，支持增量更新（addNode/removeNode）
     * <p>缓存命中时直接返回，避免访问 etcd；空列表也会缓存以防止缓存穿透
     */
    private final ServiceCache serviceCache = new DefaultServiceCache();

    /**
     * Watch 上下文映射
     * <p>Key: serviceKey（如 order-service:1.0）
     * <p>Value: 该服务的 Watch 生命周期上下文（watcher、退避状态、重连锁）
     * <p>map 中存在条目即表示该服务正在监听或正在重连中，防止并发重复建 Watch
     *
     * @see WatchContext Watch 上下文
     */
    private final Map<String, WatchContext> serviceWatchers = new ConcurrentHashMap<>();

    /**
     * 服务变更监听器映射
     * <p>Key: serviceKey（如 order-service:1.0）
     * <p>Value: 该服务的所有监听器集合，Watch 事件触发时通知所有监听器
     */
    private final Map<String, Set<ServiceListener>> listeners = new ConcurrentHashMap<>();

    // ==================== 通用字段 ====================

    /** etcd 请求超时时间（毫秒），用于 Future.get 的超时控制 */
    private long requestTimeout;

    /**
     * 重连/重试调度线程池，核心线程数 2
     * <p>用途：
     * <ol>
     *   <li>心跳续约失败后的指数退避重连（服务提供者）</li>
     *   <li>Watch 断连后的重新订阅（服务消费者）</li>
     * </ol>
     */
    private final ScheduledExecutorService reconnectExecutor =
            (ScheduledExecutorService) ThreadPoolBuilder.forName("reconnect-executor")
                    .scheduled(2)
                    .build();

    // ==================== 生命周期 ====================

    /**
     * 注册中心初始化
     * @param registryConfig 注册中心配置
     */
    @Override
    public void init(RegistryConfig registryConfig) {
        long connectTimeout = registryConfig.getConnectTimeout();
        requestTimeout = registryConfig.getRequestTimeout();
        client = Client.builder()
                .endpoints(registryConfig.getRegistryAddress())
                .connectTimeout(Duration.ofMillis(connectTimeout))
                .build();

        kvClient = client.getKVClient();
        leaseClient = client.getLeaseClient();
        watchClient = client.getWatchClient();
    }

    @Override
    public void destroy() {
        // 关闭所有租约流并撤销所有租约
        nodeLeases.forEach((node, context) -> {
            try {
                log.warn("关闭节点[{}]续约流", node);
                context.closedIntentionally = true;
                context.keepAliveClient.close();
            } catch (Exception e) {
                log.error("关闭节点[{}]续约流失败", node, e);
            }
            leaseClient.revoke(context.leaseId);
        });
        nodeLeases.clear();

        // 关闭所有监听流
        serviceWatchers.forEach((service, context) -> {
            try {
                if (context.watcher != null) {
                    log.warn("关闭对服务[{}]的监听", service);
                    context.watcher.close();
                }
            } catch (Exception e) {
                log.error("关闭对服务[{}]的监听失败", service, e);
            }
        });
        serviceWatchers.clear();

        // 关闭 etcd 客户端
        try {
            client.close();
        } catch (Exception e) {
            log.error("关闭 etcd 客户端失败", e);
        }

        // 关闭重连线程池
        reconnectExecutor.shutdownNow();
    }

    // ==================== 服务注册 ====================

    /**
     * 注册服务节点到 etcd 注册中心
     *
     * <h3>执行流程</h3>
     * <pre>
     * 1. Lease.grant(TTL=30s)     → 获取租约 ID
     * 2. Lease.keepAlive(leaseId) → 启动心跳续约流（异步）
     * 3. KV.put(key, value, lease) → 写入带租约的 Key
     * </pre>
     *
     * <h3>etcd Key-Value 设计</h3>
     * <pre>
     * Key:   /rpc/service/{serviceName}:{version}/{serviceNodeId}
     * Value: ServiceNode JSON
     * </pre>
     *
     * <p>Key 组成：
     * <ul>
     *   <li>{@code /rpc/service/} - 固定根路径</li>
     *   <li>{@code serviceName:version} - 服务唯一标识，如 order-service:1.0</li>
     *   <li>{@code host:port} - 节点地址，如 192.168.1.10:8080</li>
     * </ul>
     *
     * <h3>租约机制</h3>
     * <ul>
     *   <li>TTL：30 秒，节点无心跳则自动过期删除</li>
     *   <li>心跳：etcd 在 TTL/3（约 10s）时主动发送续约请求</li>
     *   <li>续约失败：触发指数退避重连（2s → 4s → 8s → ... → 30s）</li>
     * </ul>
     *
     * <h3>容错策略</h3>
     * <ul>
     *   <li>租约创建失败：抛出 RegistryException，终止注册</li>
     *   <li>KV 写入失败：抛出 RegistryException，租约自动过期</li>
     *   <li>心跳断连：自动重连并重新注册，不影响服务可用性</li>
     * </ul>
     *
     * @param serviceNode 服务节点，包含 serviceName、version、host、port
     * @throws RegistryException 注册失败时抛出
     * @see #unregister(ServiceNode) 注销方法
     * @see #startKeepAlive(ServiceNode, LeaseContext) 心跳续约
     */
    @Override
    public void register(ServiceNode serviceNode) throws RegistryException {
        // 首次注册服务，设置nodeId
        assignNodeId(serviceNode);
        LeaseContext context = new LeaseContext(-1);
        nodeLeases.put(serviceNode.getServiceNodeKey(), context);

        // 将服务注册到注册到注册中心并更新租约上下文
        doRegister(serviceNode, context);
    }

    /**
     * 实际注册逻辑，不含 nodeId 分配，供首次注册和重连复用
     * <p>复用已有 LeaseContext 并更新 leaseId，保证续约流持有正确的引用
     */
    private void doRegister(ServiceNode serviceNode, LeaseContext context) throws RegistryException {
        boolean isReconnect = context.leaseId > 0;

        // 获取租约id
        long leaseId;
        try {
            leaseId = leaseClient.grant(DEFAULT_LEASE_TTL)
                    .get(requestTimeout, TimeUnit.MILLISECONDS).getID();
        } catch (Exception e) {
            log.error("register lease error", e);
            if (!isReconnect) {
                nodeLeases.remove(serviceNode.getServiceNodeKey());
            }
            throw new RegistryException(RpcErrorCode.REGISTER_FAILED,
                    "register lease failed: " + e.getMessage());
        }

        // 更新租约上下文
        context.leaseId = leaseId;

        // 设置key-value
        String serviceKey = ETCD_ROOT_PATH + serviceNode.getServiceNodeKey();
        ByteSequence key = ByteSequence.from(serviceKey, StandardCharsets.UTF_8);
        ByteSequence value = ByteSequence.from(
                JSONUtil.toJsonStr(serviceNode),
                StandardCharsets.UTF_8
        );

        // 存储key-value
        PutOption putOption = PutOption.builder().withLeaseId(leaseId).build();
        try {
            kvClient.put(key, value, putOption).get(requestTimeout, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("register put error", e);
            if (!isReconnect) {
                nodeLeases.remove(serviceNode.getServiceNodeKey());
            }
            throw new RegistryException(RpcErrorCode.REGISTER_FAILED,
                    "register put failed: " + e.getMessage());
        }

        /* ========== 重连时，执行到这里代表重新注册成功 ========== */

        // 关闭旧的租约续约流
        if (context.keepAliveClient != null) {
            context.closedIntentionally = true;
            context.keepAliveClient.close();
            context.closedIntentionally = false;
        }

        // 重置租约等待时间 - 重连时调用
        context.resetBackoff();

        // 开启新的续约流
        startKeepAlive(serviceNode, context);
    }

    /**
     * 从 etcd 注册中心注销服务节点
     *
     * <h3>Key 格式</h3>
     * <pre>
     * /rpc/service/{serviceName}:{version}/{serviceNodeId}
     * </pre>
     * 与 {@link #register(ServiceNode)} 使用的 Key 格式一致。
     *
     * <h3>注销流程</h3>
     * <ol>
     *   <li>根据 serviceNode 构建完整的 etcd key</li>
     *   <li>调用 KV.delete(key) 从 etcd 删除节点</li>
     *   <li>从本地 nodeLeases Map 中移除租约记录</li>
     * </ol>
     *
     * <h3>租约处理</h3>
     * <p>注销时只删除 Key，不主动撤销 Lease。原因：
     * <ul>
     *   <li>Lease 绑定到 keepAlive 流，流关闭后租约自然过期</li>
     *   <li>避免额外的 Lease.revoke 网络调用开销</li>
     *   <li>节点已删除，租约无关联 Key，会被 etcd 自动清理</li>
     * </ul>
     *
     * <h3>与 destroy 的区别</h3>
     * <ul>
     *   <li>unregister：注销单个服务节点，保留连接，继续服务其他已注册节点</li>
     *   <li>destroy：注销所有节点并关闭连接，用于应用关闭时的优雅下线</li>
     * </ul>
     *
     * <h3>容错策略</h3>
     * <ul>
     *   <li>删除失败：抛出 RegistryException，调用方可决定是否重试</li>
     *   <li>节点不存在：delete 操作幂等，不会抛出异常</li>
     * </ul>
     *
     * @param serviceNode 服务节点，需包含 serviceName、serviceVersion、serviceHost、servicePort
     * @throws RegistryException 注销失败时抛出
     * @see #register(ServiceNode) 注册方法
     * @see #destroy() 销毁方法
     */
    @Override
    public void unregister(ServiceNode serviceNode) throws RegistryException{
        // 获取对应节点key
        String serviceKey = ETCD_ROOT_PATH + serviceNode.getServiceNodeKey();
        ByteSequence key = ByteSequence.from(serviceKey, StandardCharsets.UTF_8);

        // 移除租约上下文并关闭续约流
        LeaseContext context = nodeLeases.remove(serviceNode.getServiceNodeKey());
        if (context != null && context.keepAliveClient != null) {
            context.closedIntentionally = true;
            try {
                context.keepAliveClient.close();
            } catch (Exception e) {
                log.warn("关闭节点[{}]续约流失败", serviceNode.getServiceNodeKey(), e);
            }
        }

        // 删除节点
        try {
            kvClient.delete(key).get(requestTimeout, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("unregister service node failed", e);
            throw new RegistryException(RpcErrorCode.UNREGISTER_FAILED,
                    "unregister service node failed: " + e.getMessage());
        }
    }

    // ==================== 服务发现 ====================

    /**
     * 从 etcd 注册中心发现指定服务的所有节点
     *
     * <h3>查询策略：本地缓存优先</h3>
     * <pre>
     * 1. 查询本地缓存 → 命中则直接返回（包括空列表，防止缓存穿透）
     * 2. 缓存未命中 → 从 etcd 前缀查询 → 写入缓存 → 开启 Watch 监听
     * </pre>
     *
     * <h3>etcd 查询方式</h3>
     * <p>使用前缀查询（GetOption.isPrefix=true），查询 Key 前缀为：
     * <pre>/rpc/service/{serviceKey}/</pre>
     * 例如 serviceKey 为 {@code order-service:1.0} 时，
     * 查询所有以 {@code /rpc/service/order-service:1.0/} 为前缀的 Key，
     * 返回该前缀下的所有服务节点。
     *
     * <h3>缓存与 Watch 联动</h3>
     * <ul>
     *   <li>首次发现后写入缓存，后续直接从缓存读取</li>
     *   <li>同时开启 Watch 监听，后续变更通过增量更新缓存</li>
     *   <li>空列表同样写入缓存，避免每次都查询 etcd（防缓存穿透）</li>
     * </ul>
     *
     * <h3>返回值说明</h3>
     * <p>返回 {@link ArrayList} 的浅拷贝，调用方修改不影响内部缓存。
     *
     * <h3>容错策略</h3>
     * <ul>
     *   <li>serviceKey 为空：抛出 RegistryException</li>
     *   <li>etcd 查询失败：抛出 RegistryException，调用方可决定是否重试</li>
     * </ul>
     *
     * @param serviceKey 服务唯一标识，格式为 {serviceName}:{version}，如 order-service:1.0
     * @return 该服务的所有节点列表（可能为空列表，不会返回 null）
     * @throws RegistryException serviceKey 为空或查询 etcd 失败时抛出
     * @see #subscribe(String, ServiceListener) 订阅服务变更通知
     * @see ServiceCache 本地缓存接口
     */
    @Override
    public List<ServiceNode> discover(String serviceKey) throws RegistryException {
        if (StrUtil.isBlank(serviceKey)) {
            throw new RegistryException(RpcErrorCode.DISCOVERY_FAILED,
                    "serviceKey is empty");
        }

        // 尝试从本地缓存读取（空列表也会缓存，防止缓存穿透）
        List<ServiceNode> serviceNodes = serviceCache.get(serviceKey);
        if (serviceNodes != null) {
            return new ArrayList<>(serviceNodes);
        }

        // 本地缓存中无数据（列表不存在），从注册中心拉取
        GetOption getOption = GetOption.builder().isPrefix(true).build();
        String servicePrefix = ETCD_ROOT_PATH + serviceKey + "/";
        ByteSequence key = ByteSequence.from(servicePrefix, StandardCharsets.UTF_8);
        try {
            GetResponse getResponse = kvClient.get(key, getOption)
                    .get(requestTimeout, TimeUnit.MILLISECONDS);
            serviceNodes = getResponse.getKvs().stream()
                    .map((keyValue -> {
                        ByteSequence value = keyValue.getValue();
                        return JSONUtil.toBean(
                                value.toString(StandardCharsets.UTF_8),
                                ServiceNode.class
                        );
                    }))
                    .toList();
        } catch (Exception e) {
            log.error("discover service node failed", e);
            throw new RegistryException(RpcErrorCode.DISCOVERY_FAILED, e.getMessage());
        }

        // 写入缓存并开启 Watch 监听
        serviceCache.put(serviceKey, serviceNodes);
        ensureWatching(serviceKey);

        // 返回服务列表
        return new ArrayList<>(serviceNodes);
    }

    @Override
    public void subscribe(String serviceKey, ServiceListener listener) {
        listeners.computeIfAbsent(serviceKey, k -> ConcurrentHashMap.newKeySet())
                .add(listener);
        ensureWatching(serviceKey);
    }

    /**
     * 获取所有已注册的服务名
     */
    @Override
    public Set<String> getServices() {
        return new HashSet<>(serviceWatchers.keySet());
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 启动租约心跳续约
     * <p>通过 gRPC 双向流保持心跳，确保租约不会过期。
     * 续约失败时委托给 {@link #scheduleReconnect} 处理重连。
     *
     * @param serviceNode 服务节点，用于重连时重新注册
     * @param context 租约上下文
     */
    private void startKeepAlive(ServiceNode serviceNode, LeaseContext context) {
        context.keepAliveClient = leaseClient.keepAlive(context.leaseId, new StreamObserver<>() {
            @Override
            public void onNext(LeaseKeepAliveResponse response) {
                log.debug("节点[{}]续约成功, TTL: {}s",
                        serviceNode.getServiceNodeKey(), response.getTTL());
            }

            @Override
            public void onError(Throwable t) {
                log.error("节点[{}]续约失败", serviceNode.getServiceNodeKey(), t);
                scheduleReconnect(serviceNode, context, new AtomicInteger(0));
            }

            // 由于keepAlive方法对于tcp连接错误没有没有包装，etcd实例挂掉之后会走这个回调
            @Override
            public void onCompleted() {
                if (context.closedIntentionally) {
                    log.info("节点[{}]续约流主动关闭", serviceNode.getServiceNodeKey());
                    return;
                }
                log.warn("节点[{}]续约流异常关闭，将尝试重连", serviceNode.getServiceNodeKey());
                scheduleReconnect(serviceNode, context, new AtomicInteger(0));
            }
        });
    }

    /**
     * 调度续约重连任务
     * <p>延迟调度一次重连尝试，失败后递归调度自身继续重试。
     *
     * @param serviceNode 服务节点
     * @param context 租约上下文
     * @param reconnectCount 已达最大退避的重试次数
     */
    private void scheduleReconnect(ServiceNode serviceNode, LeaseContext context, AtomicInteger reconnectCount) {
        reconnectExecutor.schedule(() -> {
            // 每次重连都检查节点是否已经下线
            if (!nodeLeases.containsKey(serviceNode.getServiceNodeKey())) {
                log.info("节点[{}]已注销，跳过续约重连", serviceNode.getServiceNodeKey());
                return;
            }

            // 另一线程正在重连，无需重复调度
            if (!context.tryEnterReconnect()) {
                return;
            }

            // 尝试重连 - 重新注册
            try {
                log.info("正在为节点[{}]进行故障恢复（重新注册）...",
                        serviceNode.getServiceNodeKey());
                doRegister(serviceNode, context);
                log.info("节点[{}]故障恢复成功！", serviceNode.getServiceNodeKey());
            } catch (Exception e) {
                log.error("节点[{}]故障恢复失败，将继续重试", serviceNode.getServiceNodeKey(), e);

                long delay = context.Backoff();
                log.warn("当前节点重试延迟：{}", delay);

                if (delay == RECONNECT_MAX_DELAY_MS
                        && reconnectCount.incrementAndGet() > RECONNECT_MAX_ATTEMPTS) {
                    nodeLeases.remove(serviceNode.getServiceNodeKey());
                    log.error("节点[{}]达到最大重连次数，放弃重连", serviceNode.getServiceNodeKey());
                    return;
                }

                context.exitReconnect();
                scheduleReconnect(serviceNode, context, reconnectCount);
                return;
            }
            context.exitReconnect();
        }, context.backoffMs.get(), TimeUnit.MILLISECONDS);
    }

    /**
     * 为服务节点分配唯一 nodeId
     * 格式：{serviceName}-{三位编号}，如 order-service-001
     * 按 serviceName 维度本地递增，线程安全
     */
    private void assignNodeId(ServiceNode serviceNode) {
        AtomicLong counter = nodeIdCounters.computeIfAbsent(
                serviceNode.getServiceName(), k -> new AtomicLong(0));
        long seq = counter.incrementAndGet();
        serviceNode.setNodeId(String.format("%s-%03d",
                serviceNode.getServiceName(), seq));
    }

    /**
     * 调度 Watch 重新订阅任务
     * <p>延迟调度一次重新订阅，失败后递归调度自身继续重试。
     * 达到最大退避后最多重试 {@value #RECONNECT_MAX_ATTEMPTS} 次，超过则放弃。
     *
     * @param serviceKey 服务标识
     * @param delay 本次调度延迟（毫秒）
     * @param retryCount 已达最大退避的重试次数
     */
    private void scheduleWatchReconnect(String serviceKey, long delay, AtomicInteger retryCount) {
        reconnectExecutor.schedule(() -> {
            WatchContext context = serviceWatchers.get(serviceKey);
            if (context == null) {
                return;
            }

            try {
                log.info("正在重新订阅 Watch[{}]", serviceKey);
                startWatch(serviceKey);
                context.exitReconnect();
                log.info("Watch[{}]重新订阅成功", serviceKey);
            } catch (Exception e) {
                log.error("Watch[{}]重新订阅失败", serviceKey, e);

                long nextDelay = context.backoff();
                if (nextDelay == RECONNECT_MAX_DELAY_MS
                        && retryCount.incrementAndGet() > RECONNECT_MAX_ATTEMPTS) {
                    log.error("Watch[{}]达到最大重试次数，放弃重新订阅", serviceKey);
                    serviceWatchers.remove(serviceKey);
                    context.exitReconnect();
                    return;
                }

                context.exitReconnect();
                scheduleWatchReconnect(serviceKey, nextDelay, retryCount);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * 确保对指定 serviceKey 开启了 Watch 监听
     * 使用 watchingKeys 防止重复建立 Watch
     */
    private void ensureWatching(String serviceKey) {
        if (serviceWatchers.putIfAbsent(serviceKey, new WatchContext()) == null) {
            startWatch(serviceKey);
        }
    }

    /**
     * 对指定 serviceKey 前缀建立 etcd Watch
     *
     * <p>监听 /rpc/service/{serviceKey}/ 前缀下的所有变更：
     * <ul>
     *   <li>PUT 事件 → 缓存增量添加节点 + 通知监听器</li>
     *   <li>DELETE 事件 → 缓存增量移除节点 + 通知监听器</li>
     * </ul>
     *
     * <p>Watch 断连后通过 reconnectExecutor 自动重新订阅
     */
    private void startWatch(String serviceKey) {
        WatchContext context = serviceWatchers.get(serviceKey);
        if (context == null) {
            return;
        }

        ByteSequence prefixKey = ByteSequence.from(
                ETCD_ROOT_PATH + serviceKey + "/", StandardCharsets.UTF_8);
        WatchOption watchOption = WatchOption.builder()
                .isPrefix(true)
                .withPrevKV(true)
                .build();

        context.watcher = watchClient.watch(prefixKey, watchOption, new Watch.Listener() {
            @Override
            public void onNext(WatchResponse response) {
                for (WatchEvent event : response.getEvents()) {
                    handleWatchEvent(serviceKey, event);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                log.error("Watch[{}]连接异常，将尝试重新订阅", serviceKey, throwable);
                context.watcher = null;

                if (!context.tryEnterReconnect()) {
                    return;
                }

                long usedDelay = context.backoff();
                scheduleWatchReconnect(serviceKey, usedDelay, new AtomicInteger(0));
            }

            @Override
            public void onCompleted() {
                log.info("Watch[{}]流关闭", serviceKey);
                context.watcher = null;
            }
        });

        context.resetBackoff();
        log.info("已开启 Watch 监听: {}", serviceKey);
    }

    /**
     * 处理单个 Watch 事件
     * PUT → 增量添加节点到缓存 + 通知监听器
     * DELETE → 增量移除节点 + 通知监听器（通过 prevKV 获取被删除的节点信息）
     */
    private void handleWatchEvent(String serviceKey, WatchEvent event) {
        WatchEvent.EventType eventType = event.getEventType();
        KeyValue keyValue = event.getKeyValue();

        switch (eventType) {
            case PUT -> {
                ServiceNode node = JSONUtil.toBean(
                        keyValue.getValue().toString(StandardCharsets.UTF_8),
                        ServiceNode.class
                );
                serviceCache.addNode(serviceKey, node);
                notifyListeners(serviceKey, ChangeType.NODE_ADDED,
                        List.of(node), Collections.emptyList(), Collections.emptyList());
            }
            case DELETE -> {
                KeyValue prevKV = event.getPrevKV();
                if (prevKV != null && prevKV.getValue() != null) {
                    ServiceNode node = JSONUtil.toBean(
                            prevKV.getValue().toString(StandardCharsets.UTF_8),
                            ServiceNode.class
                    );
                    serviceCache.removeNode(serviceKey, node);
                    notifyListeners(serviceKey, ChangeType.NODE_DELETED,
                            Collections.emptyList(), List.of(node), Collections.emptyList());
                } else {
                    // 无法获取被删除节点信息，整缓存失效兜底
                    serviceCache.invalidate(serviceKey);
                }
            }
            default -> log.warn("未处理的 Watch 事件类型: {}", eventType);
        }
    }

    /**
     * 通知指定服务的所有监听器
     */
    private void notifyListeners(String serviceKey, ChangeType type,
                                 List<ServiceNode> added, List<ServiceNode> deleted, List<ServiceNode> updated) {
        Set<ServiceListener> listenerSet = listeners.get(serviceKey);
        if (listenerSet == null || listenerSet.isEmpty()) {
            return;
        }

        ServiceChangeEvent changeEvent = ServiceChangeEvent.builder()
                .serviceKey(serviceKey)
                .type(type)
                .addedNodes(added)
                .deletedNodes(deleted)
                .updatedNodes(updated)
                .build();

        for (ServiceListener listener : listenerSet) {
            try {
                listener.onChange(changeEvent);
            } catch (Exception e) {
                log.error("监听器回调异常, serviceKey: {}", serviceKey, e);
            }
        }
    }

    // ==================== 租约上下文 ====================

    /**
     * 每个服务节点的租约上下文
     * <p>封装节点独立的 leaseId、退避参数和重连状态，避免全局共享导致的跨节点污染
     *
     * @see WatchContext 结构类似的租约上下文
     */
    private static class LeaseContext {
        /**当前有效的租约 ID，重注册成功后更新 */
        volatile long leaseId;

        /** 当前重连退避延迟（毫秒），初始 2000ms，每次失败翻倍，上限 30000ms，续约成功后重置 */
        final AtomicLong backoffMs = new AtomicLong(RECONNECT_INITIAL_DELAY_MS);

        /** 是否正在重连中，防止并发重注册 */
        final AtomicBoolean reconnecting = new AtomicBoolean(false);

        /** 是否为主动关闭（unregister/destroy），避免 onCompleted 时误触发重连 */
        volatile boolean closedIntentionally = false;

        /** keepAlive 返回的 CloseableClient，close 即停止续约流 */
        volatile CloseableClient keepAliveClient;

        LeaseContext(long leaseId) {
            this.leaseId = leaseId;
        }

        /** 重置退避为初始值 */
        void resetBackoff() {
            backoffMs.set(RECONNECT_INITIAL_DELAY_MS);
        }

        /** 退避翻倍，返回翻倍后的值 */
        long Backoff() {
            return backoffMs.updateAndGet(current ->
                    Math.min((long) (current * RECONNECT_MULTIPLIER), RECONNECT_MAX_DELAY_MS)
            );
        }

        /** 尝试进入重连状态，cas 保证只有一个线程执行重连 */
        boolean tryEnterReconnect() {
            return reconnecting.compareAndSet(false, true);
        }

        /** 退出重连状态 */
        void exitReconnect() {
            reconnecting.set(false);
        }
    }

    // ==================== 监听上下文 ====================

    /**
     * 每个服务的 Watch 生命周期上下文
     * <p>封装服务独立的 watcher、退避参数和重连状态，避免全局共享导致的跨服务污染
     *
     * @see LeaseContext 结构类似的租约上下文
     */
    private static class WatchContext {
        /** 当前活跃的 Watcher 对象，为 null 表示已断连或未建立 */
        volatile Watch.Watcher watcher;

        /** 当前重连退避延迟（毫秒），初始 {@link #RECONNECT_INITIAL_DELAY_MS}，每次失败翻倍，上限 {@link #RECONNECT_MAX_DELAY_MS} */
        final AtomicLong backoffMs = new AtomicLong(RECONNECT_INITIAL_DELAY_MS);

        /** 是否正在重连中，CAS 保证同一服务只有一个线程执行 Watch 重连 */
        final AtomicBoolean reconnecting = new AtomicBoolean(false);

        /** 重置退避为初始值 */
        void resetBackoff() {
            backoffMs.set(RECONNECT_INITIAL_DELAY_MS);
        }

        /** 退避翻倍，返回翻倍前的值（本次使用的延迟） */
        long backoff() {
            return backoffMs.getAndUpdate(current ->
                    Math.min((long) (current * RECONNECT_MULTIPLIER), RECONNECT_MAX_DELAY_MS)
            );
        }

        /** 尝试进入重连状态，CAS 保证只有一个线程执行重连 */
        boolean tryEnterReconnect() {
            return reconnecting.compareAndSet(false, true);
        }

        /** 退出重连状态 */
        void exitReconnect() {
            reconnecting.set(false);
        }
    }
}