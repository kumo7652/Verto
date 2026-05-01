package com.pulsar.registry.etcd;

import com.pulsar.exception.RegistryException;
import com.pulsar.exception.RpcErrorCode;
import com.pulsar.model.ServiceNode;
import com.pulsar.utils.ThreadPoolBuilder;
import cn.hutool.json.JSONUtil;
import io.etcd.jetcd.*;
import io.etcd.jetcd.lease.LeaseKeepAliveResponse;
import io.etcd.jetcd.options.PutOption;
import io.etcd.jetcd.support.CloseableClient;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
class EtcdRegistrar {
    /** etcd客户端 */
    private final KV kvClient;
    private final Lease leaseClient;

    /** 重连线程池 */
    private static final String SCHEDULER_POOL_NAME = "registrar-reconnect";
    private final ScheduledExecutorService scheduler;

    /** 请求超时时间 */
    private final long requestTimeout;

    /** 节点对应租约上下文 */
    private final Map<String, LeaseContext> nodeLeases = new ConcurrentHashMap<>();

    EtcdRegistrar(KV kvClient, Lease leaseClient, long requestTimeout) {
        this.kvClient = kvClient;
        this.leaseClient = leaseClient;
        this.requestTimeout = requestTimeout;
        this.scheduler = (ScheduledExecutorService) ThreadPoolBuilder
                .forName(SCHEDULER_POOL_NAME)
                .scheduled(1)
                .build();
    }

    /**
     * <h3>将节点注册到注册中心</h3>
     * 获取租约上下文并将节点信息写入
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
     * @param serviceNode 节点信息
     * @throws RegistryException 删除节点失败
     */
    void unregister(ServiceNode serviceNode) throws RegistryException {
        String serviceKey = EtcdConstants.ETCD_ROOT_PATH + serviceNode.getServiceNodeKey();
        ByteSequence key = ByteSequence.from(serviceKey, StandardCharsets.UTF_8);

        LeaseContext context = nodeLeases.remove(serviceNode.getServiceNodeKey());
        if (context != null && context.keepAliveClient != null) {
            context.closedIntentionally = true;
            try {
                context.keepAliveClient.close();
            } catch (Exception e) {
                log.warn("关闭节点[{}]续约流失败", serviceNode.getServiceNodeKey(), e);
            }
        }

        try {
            kvClient.delete(key).get(requestTimeout, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("unregister service node failed", e);
            throw new RegistryException(RpcErrorCode.UNREGISTER_FAILED,
                    "unregister service node failed: " + e.getMessage());
        }
    }

    /**
     * <h3>销毁注册器</h3>
     * 关闭所有节点的续约流、撤销租约并清理资源
     */
    void destroy() {
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
        ThreadPoolBuilder.shutdown(SCHEDULER_POOL_NAME);
    }

    /**
     * <h3>将节点信息写入etcd</h3>
     * 申请租约、写入键值对并启动续约流，重连场景下会关闭旧续约流后重新建立
     * @param serviceNode 节点信息
     * @param context 该节点对应的租约上下文
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
        ByteSequence key = ByteSequence.from(serviceKey, StandardCharsets.UTF_8);
        ByteSequence value = ByteSequence.from(
                JSONUtil.toJsonPrettyStr(serviceNode), StandardCharsets.UTF_8);

        PutOption putOption = PutOption.builder().withLeaseId(leaseId).build();
        try {
            kvClient.put(key, value, putOption).get(requestTimeout, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("failed putting service info: ", e);

            // 第一次写入不进行重连
            if (!isReconnect) {
                nodeLeases.remove(serviceNode.getServiceNodeKey());
            }
            throw new RegistryException(RpcErrorCode.REGISTER_FAILED,
                    "failed putting service info: " + e.getMessage());
        }

        // 第一次开启或者重新开启续约流
        if (context.keepAliveClient != null) {
            context.closedIntentionally = true;
            context.keepAliveClient.close();
        }

        context.resetBackoff();
        startKeepAlive(serviceNode, context);

        // 新的续约流重建之后，将标志重置
        context.closedIntentionally = false;
    }

    /**
     * <h3>启动租约续约流</h3>
     * 注册续约回调，续约失败或流异常关闭时触发重连
     * @param serviceNode 节点信息
     * @param context 该节点对应的租约上下文
     */
    private void startKeepAlive(ServiceNode serviceNode, LeaseContext context) {
        context.keepAliveClient = leaseClient.keepAlive(context.leaseId, new StreamObserver<>() {
            @Override
            public void onNext(LeaseKeepAliveResponse response) {
                log.debug("节点[{}]续约成功, TTL: {}s", serviceNode.getServiceNodeKey(), response.getTTL());
            }

            @Override
            public void onError(Throwable t) {
                log.error("节点[{}]续约失败", serviceNode.getServiceNodeKey(), t);
                scheduleReconnect(serviceNode, context);
            }

            @Override
            public void onCompleted() {
                if (context.closedIntentionally) {
                    log.info("节点[{}]续约流主动关闭", serviceNode.getServiceNodeKey());
                    return;
                }

                log.warn("节点[{}]续约流异常关闭，将尝试重连", serviceNode.getServiceNodeKey());
                scheduleReconnect(serviceNode, context);
            }
        });
    }

    /**
     * <h3>启动续约重连状态机</h3>
     * 创建并启动一个重连任务，由状态机驱动退避和重试
     * @param serviceNode 节点信息
     * @param context 该节点对应的租约上下文
     */
    private void scheduleReconnect(ServiceNode serviceNode, LeaseContext context) {
        new ReconnectTask(serviceNode, context).start();
    }

    /**
     * <h3>续约重连状态机</h3>
     * 通过显式状态驱动重连流程，避免递归调度。
     */
    private class ReconnectTask implements Runnable {
        private final ServiceNode serviceNode;
        private final LeaseContext context;
        private int attempts = 0;

        private enum State { BACKOFF, RECONNECT, IDLE, ABANDON }
        private State state = State.BACKOFF;

        ReconnectTask(ServiceNode serviceNode, LeaseContext context) {
            this.serviceNode = serviceNode;
            this.context = context;
        }

        void start() {
            scheduleNext(context.backoff.get());
        }

        private void scheduleNext(long delayMs) {
            scheduler.schedule(this, delayMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public void run() {
            switch (state) {
                case BACKOFF -> onBackoff();
                case RECONNECT -> onReconnect();
                case ABANDON -> onAbandon();
                // IDLE: 状态机终止，不再调度
            }
        }

        private void onAbandon() {
            log.error("节点[{}]达到最大重连次数，放弃重连", serviceNode.getServiceNodeKey());
            nodeLeases.remove(serviceNode.getServiceNodeKey());
            context.exitReconnect();
            context.keepAliveClient.close();
        }

        private void onBackoff() {
            if (isCancelled()) return;
            state = State.RECONNECT;
            scheduleNext(0);
        }

        private void onReconnect() {
            if (isCancelled()) return;
            if (!context.tryReconnect()) return;

            String nodeKey = serviceNode.getServiceNodeKey();
            try {
                log.info("正在为节点[{}]进行故障恢复（重新注册）...", nodeKey);
                writeNode(serviceNode, context);

                log.info("节点[{}]故障恢复成功！", nodeKey);
                state = State.IDLE;
                context.exitReconnect();
            } catch (Exception e) {
                log.error("节点[{}]故障恢复失败，将继续重试", nodeKey, e);
                long delay = context.Backoff();

                if (needAbandon(delay)) {
                    state = State.ABANDON;
                    scheduleNext(0);
                } else {
                    state = State.BACKOFF;
                    context.exitReconnect();
                    scheduleNext(delay);
                }
            }
        }

        private boolean isCancelled() {
            if (!nodeLeases.containsKey(serviceNode.getServiceNodeKey())) {
                log.info("节点[{}]已注销，跳过续约重连", serviceNode.getServiceNodeKey());
                state = State.IDLE;
                return true;
            }
            return false;
        }

        private boolean needAbandon(long delay) {
            return delay == EtcdConstants.RECONNECT_MAX_DELAY_MS && ++attempts > EtcdConstants.RECONNECT_MAX_ATTEMPTS;
        }
    }

    private static class LeaseContext {
        volatile long leaseId;
        final AtomicLong backoff = new AtomicLong(EtcdConstants.RECONNECT_INITIAL_DELAY_MS);
        final AtomicBoolean reconnecting = new AtomicBoolean(false);
        volatile boolean closedIntentionally = false;
        volatile CloseableClient keepAliveClient;

        LeaseContext(long leaseId) { this.leaseId = leaseId; }

        void resetBackoff() { backoff.set(EtcdConstants.RECONNECT_INITIAL_DELAY_MS); }

        long Backoff() {
            return backoff.updateAndGet(current ->
                    Math.min((long) (current * EtcdConstants.RECONNECT_MULTIPLIER), EtcdConstants.RECONNECT_MAX_DELAY_MS));
        }

        boolean tryReconnect() { return reconnecting.compareAndSet(false, true); }
        void exitReconnect() { reconnecting.set(false); }
    }
}
