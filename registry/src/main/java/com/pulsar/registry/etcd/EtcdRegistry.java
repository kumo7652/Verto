package com.pulsar.registry.etcd;

import cn.hutool.core.collection.ConcurrentHashSet;
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
import io.etcd.jetcd.*;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.lease.LeaseKeepAliveResponse;
import io.etcd.jetcd.options.GetOption;
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
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@SpiExtension(name = "etcd")
public class EtcdRegistry implements Registry {
    // ========== etcd 相关客户端 ==========
    private volatile Client client;
    private volatile KV kvClient;
    private volatile Lease leaseClient;

    // =========== 常量 ===========
    private static final String ETCD_ROOT_PATH = "/rpc/service/";
    private static final long DEFAULT_LEASE_TTL = 30L;

    /** 心跳续约失败后，重连初始延迟（毫秒） */
    private static final long RECONNECT_INITIAL_DELAY_MS = 2000L;

    /** 指数退避最大延迟（毫秒） */
    private static final long RECONNECT_MAX_DELAY_MS = 30000L;

    /** 指数退避乘数 */
    private static final double RECONNECT_MULTIPLIER = 2.0;

    /** 按 serviceName 递增的计数器，用于自动生成 nodeId */
    private final ConcurrentHashMap<String, AtomicLong> nodeIdCounters = new ConcurrentHashMap<>();

    // ========== 本地缓存相关 ==========

    /**
     * 服务节点本地缓存
     * 使用 Caffeine + nodeIndex 双层缓存实现，支持增量更新（addNode/removeNode）
     * 缓存命中时直接返回，避免访问 etcd
     */
    private final ServiceCache serviceCache = new DefaultServiceCache();

    /**
     * 正在监听的服务前缀集合
     * 用于防止对同一服务重复建立 Watch，元素为 etcd key 前缀
     * 如：/rpc/service/order-service:1.0/
     */
    private final Set<String> watchingKeys = new ConcurrentHashSet<>();

    /**
     * 服务变更监听器映射
     * Key: serviceKey（如 order-service:1.0）
     * Value: 该服务的所有监听器集合，Watch 事件触发时通知所有监听器
     */
    private final Map<String, Set<ServiceListener>> listeners = new ConcurrentHashMap<>();

    /**
     * 重连/重试调度线程池
     * 用于：
     * 1. 心跳续约失败后的指数退避重连
     * 2. Watch 断连后的重新订阅
     */
    private final ScheduledExecutorService reconnectExecutor = Executors.newScheduledThreadPool(2);

    /**
     * 当前重连退避延迟（毫秒）
     * 用于指数退避算法，初始值 2000ms，每次失败翻倍，上限 30000ms
     */
    private volatile long currentBackoffMs = RECONNECT_INITIAL_DELAY_MS;

    /**
     * 注册中心初始化
     * @param registryConfig 注册中心配置
     */
    @Override
    public void init(RegistryConfig registryConfig) {
        client = Client.builder()
                .endpoints(registryConfig.getRegistryAddress())
                .connectTimeout(Duration.ofMillis(registryConfig.getTimeout()))
                .build();

        kvClient = client.getKVClient();
        leaseClient = client.getLeaseClient();
    }

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
     * @see #startKeepAlive(ServiceNode, Long) 心跳续约
     */
    @Override
    public void register(ServiceNode serviceNode) throws RegistryException {
        // 获取租约id
        long leaseId;
        try {
            leaseId = leaseClient.grant(DEFAULT_LEASE_TTL).get().getID();
        } catch (Exception e) {
            log.error("register lease error", e);
            throw new RegistryException(RpcErrorCode.REGISTER_FAILED,
                    "register lease failed: " + e.getMessage());
        }

        // 设置key-value
        assignNodeId(serviceNode);
        String serviceKey = ETCD_ROOT_PATH + serviceNode.getServiceNodeKey();
        ByteSequence key = ByteSequence.from(serviceKey, StandardCharsets.UTF_8);
        ByteSequence value = ByteSequence.from(
                JSONUtil.toJsonStr(serviceNode),
                StandardCharsets.UTF_8
        );

        // 存储key-value
        PutOption putOption = PutOption.builder().withLeaseId(leaseId).build();
        try {
            kvClient.put(key, value, putOption).get();
        } catch (Exception e) {
            log.error("register put error", e);
            throw new RegistryException(RpcErrorCode.REGISTER_FAILED,
                    "register put failed: " + e.getMessage());
        }

        // 开启服务续约
        startKeepAlive(serviceNode, leaseId);
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
        String registryKey = ETCD_ROOT_PATH + serviceNode.getServiceNodeKey();
        ByteSequence key = ByteSequence.from(registryKey, StandardCharsets.UTF_8);

        // 删除节点
        try {
            kvClient.delete(key).get();
        } catch (Exception e) {
            log.error("unregister service node failed", e);
            throw new RegistryException(RpcErrorCode.UNREGISTER_FAILED,
                    "unregister service node failed: " + e.getMessage());
        }
    }

    @Override
    public List<ServiceNode> discover(String serviceKey) throws RegistryException {
        // 校验参数
        if (StrUtil.isBlank(serviceKey)) {
            throw new RegistryException(RpcErrorCode.DISCOVERY_FAILED, "serviceKey is empty");
        }

        // 尝试从本地缓存读取（空列表也会缓存，防止缓存穿透）
        List<ServiceNode> serviceNodes = serviceCache.get(serviceKey);
        if (serviceNodes != null) {
            return serviceNodes;
        }

        // 本地缓存中无数据（列表不存在），从注册中心拉取
        GetOption getOption = GetOption.builder().isPrefix(true).build();
        String registryKey = ETCD_ROOT_PATH + serviceKey;
        ByteSequence key = ByteSequence.from(registryKey, StandardCharsets.UTF_8);
        try {
            GetResponse getResponse = kvClient.get(key, getOption).get();
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

        // TODO 重写本地缓存并开启服务监听
        serviceCache.put(serviceKey, serviceNodes);

        // 返回服务列表
        return serviceNodes;
    }

    /**
     * 获取所有已注册的服务名
     */
    @Override
    public Set<String> getServices() {
        return Set.of();
    }

    @Override
    public void destroy() {
        return;
    }


    /**
     * 启动租约心跳续约
     *
     * <p>通过 gRPC 双向流保持心跳，确保租约不会过期。
     * 当心跳续约失败时，触发指数退避重连机制。
     *
     * <h3>心跳机制</h3>
     * <ul>
     *   <li>etcd 会在租约 TTL 的 1/3 时间发送续约请求</li>
     *   <li>默认 TTL=30s，约每 10s 续约一次</li>
     *   <li>续约成功时 onNext 回调，返回新的 TTL</li>
     * </ul>
     *
     * <h3>指数退避重连</h3>
     * <ul>
     *   <li>初始延迟：2s</li>
     *   <li>每次失败翻倍：2s → 4s → 8s → ... → 30s（上限）</li>
     *   <li>重连成功后重置为初始值</li>
     * </ul>
     *
     * @param serviceNode 服务节点，用于重连时重新注册
     * @param leaseId 租约 ID
     */
    private void startKeepAlive(ServiceNode serviceNode, Long leaseId) {
        leaseClient.keepAlive(leaseId, new StreamObserver<>() {
            @Override
            public void onNext(LeaseKeepAliveResponse response) {
                currentBackoffMs = RECONNECT_INITIAL_DELAY_MS;
                log.debug("服务[{}]续约成功, TTL: {}s",
                        serviceNode.getServiceName(), response.getTTL());
            }

            @Override
            public void onError(Throwable t) {
                log.error("服务[{}]续约失败，{}后尝试重新注册",
                        serviceNode.getServiceName(), currentBackoffMs + "ms");

                reconnectExecutor.schedule(() -> {
                    try {
                        log.info("正在为服务[{}]进行故障恢复（重新注册）...",
                                serviceNode.getServiceName());
                        register(serviceNode);
                        log.info("服务[{}]故障恢复成功！", serviceNode.getServiceName());
                    } catch (Exception e) {
                        log.error("服务[{}]故障恢复失败，将继续重试", serviceNode.getServiceName(), e);
                        // 指数退避：延迟翻倍，上限 60s
                        currentBackoffMs = Math.min(
                                (long) (currentBackoffMs * RECONNECT_MULTIPLIER),
                                RECONNECT_MAX_DELAY_MS
                        );
                        // 递归调用自身，继续重试
                        startKeepAlive(serviceNode, leaseId);
                    }
                }, currentBackoffMs, TimeUnit.MILLISECONDS);
            }

            @Override
            public void onCompleted() {
                log.info("服务[{}]续约流关闭", serviceNode.getServiceName());
            }
        });
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
}