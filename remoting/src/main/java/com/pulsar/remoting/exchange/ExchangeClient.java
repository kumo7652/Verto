package com.pulsar.remoting.exchange;

import com.pulsar.model.ActiveCounter;
import com.pulsar.model.ServiceNode;
import com.pulsar.remoting.transport.netty.client.NettyTransportClient;

import java.io.Closeable;
import java.util.concurrent.CompletableFuture;

/**
 * <h3>交换层客户端</h3>
 * 组合传输层写能力与响应配对，提供协议无关的请求-响应入口。
 * requestId 由协议层生成并传入，消息类型对交换层透明。
 */
public class ExchangeClient implements Closeable {

    private final NettyTransportClient transport;
    private final ResponseDispatcher dispatcher;

    public ExchangeClient(NettyTransportClient transport) {
        this.transport = transport;
        this.dispatcher = ResponseDispatcher.getInstance();
    }

    /**
     * <h3>发起请求并等待响应</h3>
     *
     * @param requestId 协议层生成的请求标识（帧内也会携带，供服务端回显）
     * @param msg       协议已构造的消息对象（编码由 pipeline codec 完成）
     * @param node      目标服务节点
     * @param timeoutMs 超时时间
     * @return 响应体 Future（byte[]）
     */
    public CompletableFuture<byte[]> request(long requestId, Object msg, ServiceNode node, long timeoutMs) {
        String endpoint = node.getServiceHost() + ":" + node.getServicePort();
        CompletableFuture<byte[]> future = dispatcher.register(requestId, timeoutMs, endpoint);
        transport.write(msg, node)
                .thenAccept(channel -> dispatcher.bindChannel(requestId, channel))
                .exceptionally(ex -> {
                    future.completeExceptionally(ex);
                    return null;
                });
        return future;
    }

    public ActiveCounter getActiveCounter() {
        return dispatcher.getActiveCounter();
    }

    @Override
    public void close() {
        transport.close();
        dispatcher.shutdown();
    }
}
