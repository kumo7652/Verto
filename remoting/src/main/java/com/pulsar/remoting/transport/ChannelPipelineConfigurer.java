package com.pulsar.remoting.transport;

import io.netty.channel.ChannelPipeline;

/**
 * <h3>Channel Pipeline 装配器</h3>
 * 传输层只负责 Netty 骨架（绑定端口、接收连接、事件循环），
 * pipeline 中装哪些编解码器与业务 handler 由具体协议自决，
 * 实现传输层与帧格式（VertoPacket / 未来的 HTTP 等）的解耦。
 */
@FunctionalInterface
public interface ChannelPipelineConfigurer {

    /**
     * 为连接装配协议相关的 pipeline
     *
     * @param pipeline 目标 pipeline
     */
    void configure(ChannelPipeline pipeline);
}
