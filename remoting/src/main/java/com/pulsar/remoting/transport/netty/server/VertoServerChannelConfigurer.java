package com.pulsar.remoting.transport.netty.server;

import com.pulsar.config.TransportConfig;
import com.pulsar.remoting.transport.ChannelPipelineConfigurer;
import com.pulsar.remoting.transport.RequestHandler;
import com.pulsar.remoting.message.codec.VertoPacketDecoder;
import com.pulsar.remoting.message.codec.VertoPacketEncoder;
import io.netty.channel.ChannelPipeline;

import java.util.concurrent.ExecutorService;

/**
 * <h3>Verto 协议服务端 pipeline 装配器</h3>
 * 装配 VertoPacket 帧编解码器与服务端业务处理器
 */
public class VertoServerChannelConfigurer implements ChannelPipelineConfigurer {

    private final VertoPacketEncoder encoderHandler = new VertoPacketEncoder();
    private final NettyServerHandler serverHandler;

    public VertoServerChannelConfigurer(RequestHandler requestHandler, TransportConfig config, ExecutorService businessPool) {
        this.serverHandler = new NettyServerHandler(requestHandler, config, businessPool);
    }

    @Override
    public void configure(ChannelPipeline pipeline) {
        pipeline.addLast(new VertoPacketDecoder())
                .addLast(encoderHandler)
                .addLast(serverHandler);
    }
}
