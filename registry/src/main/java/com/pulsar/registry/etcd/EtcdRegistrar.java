package com.pulsar.registry.etcd;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.hutool.json.JSONUtil;
import com.pulsar.exception.RegistryException;
import com.pulsar.exception.RpcErrorCode;
import com.pulsar.model.ServiceNode;
import com.pulsar.utils.ThreadPoolBuilder;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.Lease;
import io.etcd.jetcd.lease.LeaseKeepAliveResponse;
import io.etcd.jetcd.options.PutOption;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

class EtcdRegistrar {

    private static final Logger log = LoggerFactory.getLogger(EtcdRegistrar.class);
    /**
     * 重连线程池
     */
    private static final String SCHEDULER_POOL_NAME = "etcd-registrar";
    /**
     * etcd客户端
     */
    private final KV kvClient;
    private final Lease leaseClient;
    private final ScheduledExecutorService scheduler;

    /**
     * 请求超时时间
     */
    private final long requestTimeout;

    /**
     * 节点对应租约上下文
     */
    private final Map<String, LeaseContext> nodeLeases = new ConcurrentHashMap<>();

    EtcdRegistrar(KV kvClient, Lease leaseClient, long requestTimeout) {
        this.kvClient = kvClient;
        this.leaseClient = leaseClient;
        this.requestTimeout = requestTimeout;
        this.scheduler = ThreadPoolBuilder
            .forName(SCHEDULER_POOL_NAME)
            .coreThreads(4)
            .buildScheduled();
    }

    /**
     * <h3>将节点注册到注册中心</h3>
     * 获取租约上下文并将节点信息写入
     *
     * @param serviceNode 节点信息
     * @throws RegistryException 写入节点失败
     */
    void register(ServiceNode serviceNode) throws RegistryException {
        LeaseContext context = nodeLeases.computeIfAbsent(
            serviceNode.getServiceNodeKey(),
            key -> new LeaseContext(-1)
        );
        writeNode(serviceNode, context);
    }

    /**
     * <h3>将节点从注册中心注销</h3>
     * 关闭续约流并删除节点对应的键
     *
     * @param serviceNode 节点信息
     * @throws RegistryException 删除节点失败
     */
    void unregister(ServiceNode serviceNode) throws RegistryException {
        String serviceKey = EtcdConstants.ETCD_ROOT_PATH + serviceNode.getServiceNodeKey();
        ByteSequence key = ByteSequence.from(serviceKey, StandardCharsets.UTF_8);

        LeaseContext context = nodeLeases.remove(serviceNode.getServiceNodeKey());
        if (context != null && context.keepAliveFuture != null) {
            context.keepAliveFuture.cancel(false);
        }

        try {
            kvClient.delete(key).get(requestTimeout, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("unregister service node failed", e);
            throw new RegistryException(RpcErrorCode.UNREGISTER_FAILED, "unregister service node failed: " + e.getMessage());
        }
    }

    /**
     * <h3>销毁注册器</h3>
     * <p>
     * 关闭所有节点的续约流、撤销租约并清理资源
     */
    void destroy() {
        nodeLeases.forEach((node, context) -> {
            if (context.keepAliveFuture != null) {
                context.keepAliveFuture.cancel(false);
            }
            leaseClient.revoke(context.leaseId);
        });
        nodeLeases.clear();
        ThreadPoolBuilder.shutdown(SCHEDULER_POOL_NAME);
    }

    /**
     * <h3>将节点信息写入etcd</h3>
     * 申请租约、写入键值对并启动续约流，重连场景下会关闭旧续约流后重新建立
     *
     * @param serviceNode 节点信息
     * @param context     该节点对应的租约上下文
     * @throws RegistryException 申请租约或写入键值对失败
     */
    private void writeNode(ServiceNode serviceNode, LeaseContext context) throws RegistryException {
        // 是否是重连调用
        boolean isReconnect = context.leaseId > 0;

        // 申请租约
        long leaseId;
        try {
            leaseId = leaseClient.grant(EtcdConstants.DEFAULT_LEASE_TTL)
                .get(requestTimeout, TimeUnit.MILLISECONDS).getID();
        } catch (Exception e) {
            log.error("failed requesting lease: ", e);

            // 第一次写入不进行重连
            if (!isReconnect) {
                nodeLeases.remove(serviceNode.getServiceNodeKey());
            }
            throw new RegistryException(RpcErrorCode.REGISTER_FAILED,
                "failed requesting lease: " + e.getMessage());
        }
        context.leaseId = leaseId;

        // 写入键值对
        String serviceKey = EtcdConstants.ETCD_ROOT_PATH + serviceNode.getServiceNodeKey();
        String serviceNodeJson = JSONUtil.toJsonPrettyStr(serviceNode);
        ByteSequence key = ByteSequence.from(serviceKey, StandardCharsets.UTF_8);
        ByteSequence value = ByteSequence.from(serviceNodeJson, StandardCharsets.UTF_8);
        PutOption putOption = PutOption.builder().withLeaseId(leaseId).build();
        try {
            kvClient.put(key, value, putOption).get(requestTimeout, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("failed putting service info: ", e);

            // 第一次写入不进行重连
            if (!isReconnect) {
                nodeLeases.remove(serviceNode.getServiceNodeKey());
            }
            throw new RegistryException(RpcErrorCode.REGISTER_FAILED, "failed putting service info: " + e.getMessage());
        }

        // 启动链式续约调度（链已自然终止，无需 cancel）
        context.resetBackoff();
        startKeepAlive(serviceNode, context);
    }

    /**
     * <h3>启动租约续约流</h3>
     * 注册续约回调，续约失败或流异常关闭时触发重连
     *
     * @param serviceNode 节点信息
     * @param context     该节点对应的租约上下文
     */
    private void startKeepAlive(ServiceNode serviceNode, LeaseContext context) {
        context.keepAliveFuture = scheduler.schedule(
            () -> renewLease(serviceNode, context),
            EtcdConstants.DEFAULT_LEASE_TTL / 3,
            TimeUnit.SECONDS
        );
    }

    /**
     * <h3>执行单次租约续约</h3>
     * 调用keepAliveOnce续约，成功则重置退避并调度下一次续约，失败则触发退避重注册
     *
     * @param serviceNode 节点信息
     * @param context     该节点对应的租约上下文
     */
    private void renewLease(ServiceNode serviceNode, LeaseContext context) {
        String nodeKey = serviceNode.getServiceNodeKey();
        long leaseId = context.leaseId;

        // 节点已注销
        if (!nodeLeases.containsKey(nodeKey)) return;

        // 尝试续约
        try {
            LeaseKeepAliveResponse response = leaseClient.keepAliveOnce(leaseId)
                .get(requestTimeout, TimeUnit.MILLISECONDS);

            if (response.getTTL() > 0) {
                log.info("节点[{}]续约成功，TTL: {}s", nodeKey, response.getTTL());
                context.resetBackoff();
                startKeepAlive(serviceNode, context);
            } else {
                log.warn("节点[{}]租约已经不存在，尝试从新注册", nodeKey);
                reconnect(serviceNode, context);
            }
        } catch (Exception e) {
            // 续约失败：不管是网络不通还是 etcd 挂了，直接重注册
            log.warn("节点[{}]续约失败，将重新注册: ", nodeKey, e);
            reconnect(serviceNode, context);
        }
    }

    /**
     * <h3>退避后全量重注册</h3>
     * <p>
     * 由于链式调度已自然终止（未调用 startKeepAlive），
     * 无需额外取消调度。
     * </p>
     *
     * @param serviceNode 节点信息
     * @param context     该节点对应的租约上下文
     */
    private void reconnect(ServiceNode serviceNode, LeaseContext context) {
        long delay = context.backoff();
        String nodeKey = serviceNode.getServiceNodeKey();

        // 达到最大退避时间且超过最大重连次数 → 放弃
        if (delay == EtcdConstants.RECONNECT_MAX_DELAY_MS && context.isAborted()) {
            log.error("节点[{}]达到最大重连次数，放弃重连", nodeKey);
            nodeLeases.remove(nodeKey);
            return;
        }

        log.info("节点[{}]将在{}ms后重新注册", nodeKey, delay);
        scheduler.schedule(() -> {
            if (!nodeLeases.containsKey(nodeKey)) {
                return;
            }

            try {
                writeNode(serviceNode, context);
                context.resetBackoff();
                context.reconnectAttempts.set(0);
                log.info("节点[{}]重注册成功", nodeKey);
            } catch (Exception e) {
                log.error("节点[{}]重注册失败", nodeKey, e);
                reconnect(serviceNode, context);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * <h3>节点租约上下文</h3>
     * 维护单个节点的租约ID、续约调度句柄和退避状态
     */
    private static class LeaseContext {
        /**
         * 退避延迟（毫秒），成功后续约时重置
         */
        final AtomicLong backoff = new AtomicLong(EtcdConstants.RECONNECT_INITIAL_DELAY_MS);
        /**
         * 全量重注册已尝试次数（达上限后放弃）
         */
        final AtomicInteger reconnectAttempts = new AtomicInteger(0);
        /**
         * 当前租约 ID
         */
        volatile long leaseId;
        /**
         * 续约调度 Future，用于取消
         */
        volatile ScheduledFuture<?> keepAliveFuture;

        LeaseContext(long leaseId) {
            this.leaseId = leaseId;
        }

        /**
         * <h3>重置退避延迟</h3>
         * 将退避延迟恢复为初始值，在续约或重注册成功后调用
         */
        void resetBackoff() {
            backoff.set(EtcdConstants.RECONNECT_INITIAL_DELAY_MS);
        }

        /**
         * <h3>计算下一次退避延迟</h3>
         * 按乘数指数增长，上限为最大退避时间，返回增长前的旧值用于本次调度
         *
         * @return 本次调度应使用的退避延迟（毫秒）
         */
        long backoff() {
            return backoff.getAndUpdate(current -> {
                long delay = (long) (current * EtcdConstants.RECONNECT_MULTIPLIER);
                return Math.min(delay, EtcdConstants.RECONNECT_MAX_DELAY_MS);
            });
        }

        /**
         * <h3>判断是否应放弃重连</h3>
         * 递增重连次数并判断是否超过最大重连次数，仅在退避达到上限时调用
         *
         * @return 是否应放弃重连
         */
        boolean isAborted() {
            return reconnectAttempts.incrementAndGet() > EtcdConstants.RECONNECT_MAX_ATTEMPTS;
        }
    }
}
