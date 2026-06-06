package com.pulsar.transport.netty.client;

import com.pulsar.model.ActiveCountProvider;
import com.pulsar.model.RemoteRequest;
import com.pulsar.utils.RequestIdGenerator;
import com.pulsar.model.RemoteResponse;
import com.pulsar.model.ServiceNode;
import com.pulsar.protocol.verto.PacketType;
import com.pulsar.protocol.verto.VertoPacket;
import com.pulsar.transport.config.TransportConfig;
import com.pulsar.transport.netty.codec.VertoPacketDecoder;
import com.pulsar.transport.netty.codec.VertoPacketEncoder;
import io.netty.channel.Channel;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * <h3>基于 Netty 的传输层客户端</h3>
 * 发送 RPC 请求，通过 {@link ResponseDispatcher} 进行请求-响应匹配。
 */
@Slf4j
public class NettyTransportClient {
    /**
     * 配置信息
     */
    private final TransportConfig config;

    /**
     * 编码器
     */
    private final VertoPacketEncoder encoderHandler;

    /**
     * 连接管理与响应分发
     */
    private final ResponseDispatcher dispatcher;
    private final ConnectionManager connectionManager;

    /**
     * 数据处理
     */
    private final NettyClientHandler clientHandler;

    /**
     * 记录已初始化 pipeline 的 Channel
     */
    private final Set<Channel> initializedChannels = ConcurrentHashMap.newKeySet();

    public NettyTransportClient(TransportConfig config) {
        this.config = config;

        encoderHandler = new VertoPacketEncoder();

        connectionManager = ConnectionManager.getInstance();
        dispatcher = ResponseDispatcher.getInstance();
        clientHandler = NettyClientHandler.getInstance();
    }

    /**
     * <h3>发送 RPC 请求</h3>
     *
     * @param request       RPC 请求
     * @param serviceNode   目标服务节点
     * @param serializer    序列化器别名
     * @return 异步响应 Future
     */
    public CompletableFuture<RemoteResponse> send(RemoteRequest request, ServiceNode serviceNode, String serializer) {
        long requestId = RequestIdGenerator.nextId();
        String host = serviceNode.getServiceHost();
        int port = serviceNode.getServicePort();
        String endpoint = host + ":" + port;
        CompletableFuture<RemoteResponse> future = dispatcher.register(requestId, config.getResponseTimeoutMs(), endpoint);

        CompletableFuture<Channel> connection = connectionManager.get(host, port);
        connection.thenAccept(channel -> {
            registerPipeline(channel);
            dispatcher.bindChannel(requestId, channel);

            try {
                VertoPacket<RemoteRequest> packet = VertoPacket.create(
                    PacketType.REQUEST,
                    serializer,
                    requestId,
                    request
                );
                channel.writeAndFlush(packet);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }).exceptionally(ex -> {
            future.completeExceptionally(ex);
            return null;
        });

        return future;
    }

    public ActiveCountProvider getActiveCountProvider() {
        return dispatcher.getActiveCountProvider();
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
                .addLast(new VertoPacketDecoder()) // 解码器不能share
                .addLast(encoderHandler)
                .addLast(
                    new IdleStateHandler(config.getHeartbeatTimeoutMs(),
                        0,
                        config.getHeartbeatIntervalMs(),
                        TimeUnit.MILLISECONDS)
                )
                .addLast(clientHandler);
        }
    }
}
