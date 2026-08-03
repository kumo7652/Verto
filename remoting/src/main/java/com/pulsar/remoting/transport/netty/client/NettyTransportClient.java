package com.pulsar.remoting.transport.netty.client;

import com.pulsar.config.TransportConfig;
import com.pulsar.model.ServiceNode;
import com.pulsar.remoting.transport.ChannelPipelineConfigurer;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h3>基于 Netty 的传输层客户端</h3>
 * 只负责"连接 + 写对象 + pipeline 骨架"，不参与请求-响应配对（交换层职责）。
 * 编码责任由 pipeline 中的协议 codec 承担，因此可承载任意协议帧。
 */
public class NettyTransportClient {

    private static final Logger log = LoggerFactory.getLogger(NettyTransportClient.class);

    private final ConnectionManager connectionManager;
    private final ChannelPipelineConfigurer pipelineConfigurer;

    /**
     * 记录已初始化 pipeline 的 Channel
     */
    private final Set<Channel> initializedChannels = ConcurrentHashMap.newKeySet();

    public NettyTransportClient(TransportConfig config, ChannelPipelineConfigurer pipelineConfigurer) {
        this.pipelineConfigurer = pipelineConfigurer;
        connectionManager = ConnectionManager.getInstance();
    }

    /**
     * <h3>发送协议消息对象（不配对）</h3>
     * 获取/建立连接、装配 pipeline、写出消息，编码交给 pipeline codec。
     *
     * @param msg         协议消息对象（如 VertoPacket）
     * @param serviceNode 目标服务节点
     * @return 连接就绪后的 Channel（供交换层绑定请求）
     */
    public CompletableFuture<Channel> write(Object msg, ServiceNode serviceNode) {
        String host = serviceNode.getServiceHost();
        int port = serviceNode.getServicePort();
        CompletableFuture<Channel> connection = connectionManager.get(host, port);
        return connection.thenApply(channel -> {
            registerPipeline(channel);
            channel.writeAndFlush(msg);
            return channel;
        });
    }

    /**
     * <h3>关闭客户端连接</h3>
     */
    public void close() {
        connectionManager.close();
    }

    /**
     * <h3>为 Channel 设置 Pipeline（仅首次）</h3>
     */
    private void registerPipeline(Channel channel) {
        if (!initializedChannels.add(channel)) return;
        pipelineConfigurer.configure(channel.pipeline());
    }
}
