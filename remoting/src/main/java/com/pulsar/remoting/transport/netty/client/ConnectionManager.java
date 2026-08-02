package com.pulsar.remoting.transport.netty.client;

import com.pulsar.remoting.transport.netty.NettyEventLoopGroup;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * <h3>连接管理器（单例）</h3>
 * 每个 endpoint (host:port) 只维持一条 TCP 长连接。
 * 所有并发请求共享同一条 Channel，通过 requestId 区分各自的响应。
 */
@Slf4j
public class ConnectionManager {
    private static final ConnectionManager INSTANCE = new ConnectionManager();

    private final Bootstrap bootstrap;

    /** endpoint → 单一长连接 */
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

    /** 按 endpoint 加锁，避免并发建连时重复创建 */
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    private ConnectionManager() {
        this.bootstrap = new Bootstrap()
                .group(NettyEventLoopGroup.getWorker())
                .channel(NioSocketChannel.class);
    }

    public static ConnectionManager getInstance() {
        return INSTANCE;
    }

    /**
     * <h3>获取到目标端点的连接</h3>
     * 有则复用，无则新建。每个 endpoint 最多一条连接。
     */
    public CompletableFuture<Channel> get(String host, int port) {
        String endpoint = host + ":" + port;
        Channel channel = channels.get(endpoint);
        if (channel != null && channel.isActive()) {
            return CompletableFuture.completedFuture(channel);
        }

        // 死连接清理
        if (channel != null) {
            channels.remove(endpoint, channel);
            channel.close();
        }

        // 按 endpoint 加锁，避免重复建连
        ReentrantLock lock = locks.computeIfAbsent(endpoint, k -> new ReentrantLock());
        lock.lock();
        try {
            channel = channels.get(endpoint);
            if (channel != null && channel.isActive()) {
                return CompletableFuture.completedFuture(channel);
            }
            return doConnect(host, port, endpoint);
        } finally {
            lock.unlock();
        }
    }

    /**
     * <h3>移除失效连接</h3>
     * 连接断开或异常时调用，下次 {@link #get} 会自动重建。
     */
    public void remove(Channel channel) {
        String endpoint = getEndpoint(channel);
        if (endpoint != null) {
            channels.remove(endpoint, channel);
        }
        channel.close();
    }

    /**
     * <h3>关闭所有连接</h3>
     */
    public void close() {
        channels.forEach((endpoint, ch) -> {
            channels.remove(endpoint, ch);
            ch.close();
        });
        channels.clear();
        locks.clear();
    }

    private CompletableFuture<Channel> doConnect(String host, int port, String endpoint) {
        CompletableFuture<Channel> future = new CompletableFuture<>();
        bootstrap.connect(host, port).addListener((ChannelFutureListener) f -> {
            if (f.isSuccess()) {
                Channel ch = f.channel();
                Channel old = channels.put(endpoint, ch);
                if (old != null) {
                    old.close();
                }
                future.complete(ch);
                log.debug("连接建立成功, endpoint={}", endpoint);
            } else {
                channels.remove(endpoint);
                future.completeExceptionally(f.cause());
                log.warn("连接建立失败, endpoint={}", endpoint, f.cause());
            }
        });
        return future;
    }

    private String getEndpoint(Channel channel) {
        if (channel.remoteAddress() == null) return null;
        return channel.remoteAddress().toString().replaceFirst("^/", "");
    }
}
