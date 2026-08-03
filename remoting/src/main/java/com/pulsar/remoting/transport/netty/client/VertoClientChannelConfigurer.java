package com.pulsar.remoting.transport.netty.client;

import com.pulsar.config.TransportConfig;
import com.pulsar.remoting.transport.ChannelPipelineConfigurer;
import com.pulsar.remoting.message.codec.VertoPacketDecoder;
import com.pulsar.remoting.message.codec.VertoPacketEncoder;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.TimeUnit;

/**
 * <h3>Verto 协议客户端 pipeline 装配器</h3>
 * 装配 VertoPacket 帧编解码器、心跳超时与客户端业务处理器
 */
public class VertoClientChannelConfigurer implements ChannelPipelineConfigurer {

    private final VertoPacketEncoder encoderHandler = new VertoPacketEncoder();
    private final NettyClientHandler clientHandler = NettyClientHandler.getInstance();
    private final TransportConfig config;

    public VertoClientChannelConfigurer(TransportConfig config) {
        this.config = config;
    }

    @Override
    public void configure(ChannelPipeline pipeline) {
        pipeline.addLast(new VertoPacketDecoder())
                .addLast(encoderHandler)
                .addLast(new IdleStateHandler(
                        config.getHeartbeatTimeoutMs(),
                        0,
                        config.getHeartbeatIntervalMs(),
                        TimeUnit.MILLISECONDS))
                .addLast(clientHandler);
    }
}
