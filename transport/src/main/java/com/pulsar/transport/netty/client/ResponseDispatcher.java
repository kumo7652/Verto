package com.pulsar.transport.netty.client;

import com.pulsar.model.RpcResponse;
import com.pulsar.protocol.verto.VertoPacket;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.*;

/**
 * <h3>请求-响应匹配分发器（单例）</h3>
 * 根据 requestId 将响应匹配到对应的 CompletableFuture。
 */
@Slf4j
public class ResponseDispatcher {
    private static final ResponseDispatcher INSTANCE = new ResponseDispatcher();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<Long, CompletableFuture<RpcResponse>> requestWindow = new ConcurrentHashMap<>();

    private ResponseDispatcher() {}

    public static ResponseDispatcher getInstance() {
        return INSTANCE;
    }

    /**
     * <h3>注册请求，等待响应</h3>
     */
    public CompletableFuture<RpcResponse> register(long requestId, long timeoutMs) {
        CompletableFuture<RpcResponse> future = new CompletableFuture<>();
        requestWindow.put(requestId, future);

        scheduler.schedule(() -> {
            CompletableFuture<RpcResponse> f = requestWindow.get(requestId);
            if (f != null && !f.isDone()) {
                f.completeExceptionally(new TimeoutException("响应超时: requestId=" + requestId));
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);

        future.whenComplete((resp, ex) -> requestWindow.remove(requestId));
        return future;
    }

    /**
     * <h3>收到响应时分发</h3>
     */
    public void dispatch(VertoPacket<RpcResponse> packet) {
        long requestId = packet.getHeader().getRequestId();
        CompletableFuture<RpcResponse> future = requestWindow.get(requestId);
        if (future == null) {
            log.warn("收到未知 requestId 的响应: {}", requestId);
            return;
        }
        future.complete(packet.getBody());
    }

    /**
     * <h3>使所有在途请求失败</h3>
     */
    public void failAll(Throwable cause) {
        for (Map.Entry<Long, CompletableFuture<RpcResponse>> entry : requestWindow.entrySet()) {
            entry.getValue().completeExceptionally(cause);
        }
        requestWindow.clear();
    }

    public void shutdown() {
        scheduler.shutdown();
    }
}
