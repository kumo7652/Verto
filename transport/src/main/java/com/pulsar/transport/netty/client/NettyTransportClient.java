package com.pulsar.transport.netty.client;

import cn.hutool.core.util.IdUtil;
import com.pulsar.model.RpcRequest;
import com.pulsar.model.RpcResponse;
import com.pulsar.model.ServiceNode;
import com.pulsar.protocol.verto.PacketType;
import com.pulsar.protocol.verto.VertoPacket;
import com.pulsar.transport.config.TransportConfig;
import com.pulsar.transport.netty.codec.VertoPacketDecoder;
import com.pulsar.transport.netty.codec.VertoPacketEncoder;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h3>基于 Netty 的传输层客户端</h3>
 * 发送 RPC 请求，通过 {@link ResponseDispatcher} 进行请求-响应匹配。
 */
@Slf4j
public class NettyTransportClient {
    private final ConnectionManager connectionManager = ConnectionManager.getInstance();
    private final ResponseDispatcher dispatcher = ResponseDispatcher.getInstance();
    private final VertoPacketEncoder encoderHandler = new VertoPacketEncoder();
    private final NettyClientHandler clientHandler = NettyClientHandler.getInstance();
    private final TransportConfig config;

    /** 记录已初始化 pipeline 的 Channel */
    private final Set<Channel> initializedChannels = ConcurrentHashMap.newKeySet();

    public NettyTransportClient(TransportConfig config) {
        this.config = config;
    }

    /**
     * <h3>发送 RPC 请求</h3>
     *
     * @param request       RPC 请求
     * @param serviceNode   目标服务节点
     * @param serializerKey 序列化器别名
     * @return 异步响应 Future
     */
    public CompletableFuture<RpcResponse> send(RpcRequest request, ServiceNode serviceNode, String serializerKey) {
        long requestId = IdUtil.getSnowflakeNextId();
        CompletableFuture<RpcResponse> future = dispatcher.register(requestId, config.getResponseTimeoutMs());
        String host = serviceNode.getServiceHost();
        int port = serviceNode.getServicePort();

        connectionManager.get(host, port)
                .thenAccept(channel -> {
                    registerPipeline(channel);

                    try {
                        VertoPacket<RpcRequest> packet = VertoPacket.create(
                                PacketType.REQUEST,
                                serializerKey,
                                requestId,
                                request
                        );
                        channel.writeAndFlush(packet);
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                })
                .exceptionally(ex -> {
                    future.completeExceptionally(ex);
                    return null;
                });

        return future;
    }

    /**
     * <h3>关闭客户端及连接</h3>
     */
    public void close() {
        connectionManager.close();
        dispatcher.failAll(new RuntimeException("Client closed"));
        dispatcher.shutdown();
    }

    /**
     * <h3>为 Channel 设置 Pipeline（仅首次）</h3>
     */
    private void registerPipeline(Channel channel) {
        if (!initializedChannels.add(channel)) return;

        if (channel.pipeline().get(VertoPacketDecoder.class) == null) {
            channel.pipeline()
                    .addLast(new VertoPacketDecoder())
                    .addLast(encoderHandler)
                    .addLast(clientHandler);
        }
    }
}
