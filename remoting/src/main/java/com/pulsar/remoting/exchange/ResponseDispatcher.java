package com.pulsar.remoting.exchange;

import com.pulsar.model.ActiveCounter;
import com.pulsar.utils.ThreadPoolBuilder;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <h3>请求-响应匹配分发器（单例）</h3>
 * 根据 requestId 将响应匹配到对应的 CompletableFuture。
 * 位于交换层：配对、超时、活跃计数均与具体协议无关。
 */
public class ResponseDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ResponseDispatcher.class);
    private static final ResponseDispatcher INSTANCE = new ResponseDispatcher();
    private static final String SCHEDULER_POOL_NAME = "response-dispatcher";

    private final ScheduledExecutorService scheduler;
    private final Map<Long, Channel> requestChannels;
    private final Map<Long, CompletableFuture<byte[]>> requestWindow;
    private final ConcurrentHashMap<String, AtomicInteger> activeCounts;

    private ResponseDispatcher() {
        requestWindow = new ConcurrentHashMap<>();
        requestChannels = new ConcurrentHashMap<>();
        activeCounts = new ConcurrentHashMap<>();
        scheduler = ThreadPoolBuilder
            .forName(SCHEDULER_POOL_NAME)
            .coreThreads(1)
            .buildScheduled();
    }

    public static ResponseDispatcher getInstance() {
        return INSTANCE;
    }

    /**
     * <h3>注册请求，等待响应</h3>
     */
    public CompletableFuture<byte[]> register(long requestId, long timeoutMs, String endpoint) {
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        requestWindow.put(requestId, future);

        if (endpoint != null) {
            activeCounts.computeIfAbsent(endpoint, k -> new AtomicInteger()).incrementAndGet();
        }

        scheduler.schedule(() -> {
            CompletableFuture<byte[]> f = requestWindow.get(requestId);
            if (f != null && !f.isDone()) {
                f.completeExceptionally(new TimeoutException("响应超时: requestId=" + requestId));
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);

        future.whenComplete((resp, ex) -> {
            requestWindow.remove(requestId);
            requestChannels.remove(requestId);
            if (endpoint != null) {
                AtomicInteger count = activeCounts.get(endpoint);
                if (count != null) {
                    count.decrementAndGet();
                }
            }
        });
        return future;
    }

    /**
     * 绑定请求到 Channel，用于连接断开时批量使对应请求失效。
     */
    public void bindChannel(long requestId, Channel channel) {
        requestChannels.put(requestId, channel);
    }

    /**
     * 使指定 Channel 上所有进行中请求立即失败。
     */
    public void closeChannel(Channel channel, Throwable cause) {
        Iterator<Map.Entry<Long, Channel>> it = requestChannels.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Channel> entry = it.next();
            if (entry.getValue() == channel) {
                CompletableFuture<byte[]> future = requestWindow.get(entry.getKey());
                if (future != null) {
                    future.completeExceptionally(cause);
                }
                it.remove();
            }
        }
    }

    /**
     * <h3>收到响应时按 requestId 完成对应 Future（协议无关）</h3>
     */
    public void complete(long requestId, byte[] body) {
        CompletableFuture<byte[]> future = requestWindow.get(requestId);
        if (future == null) {
            log.warn("收到未知 requestId 的响应: {}", requestId);
            return;
        }
        future.complete(body);
    }

    /**
     * <h3>使所有在途请求失败</h3>
     */
    public void failAll(Throwable cause) {
        for (Map.Entry<Long, CompletableFuture<byte[]>> entry : requestWindow.entrySet()) {
            entry.getValue().completeExceptionally(cause);
        }
        requestWindow.clear();
    }

    public void shutdown() {
        ThreadPoolBuilder.shutdown(SCHEDULER_POOL_NAME);
    }

    public ActiveCounter getActiveCounter() {
        return node -> {
            String key = node.getServiceHost() + ":" + node.getServicePort();
            AtomicInteger c = activeCounts.get(key);
            return c != null ? c.get() : 0;
        };
    }
}
