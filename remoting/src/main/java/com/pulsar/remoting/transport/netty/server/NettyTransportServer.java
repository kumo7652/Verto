package com.pulsar.remoting.transport.netty.server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pulsar.config.TransportConfig;
import com.pulsar.remoting.transport.ChannelPipelineConfigurer;
import com.pulsar.remoting.transport.netty.NettyEventLoopGroup;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * <h3>基于 Netty 的传输层服务端</h3>
 * 负责启动 TCP 监听、将 pipeline 装配委托给 {@link ChannelPipelineConfigurer}、以及优雅关闭。
 * 不再直接依赖任何帧格式（VertoPacket / 未来的 HTTP），实现传输与协议解耦。
 */
public class NettyTransportServer {

    private static final Logger log = LoggerFactory.getLogger(NettyTransportServer.class);
    private MultiThreadIoEventLoopGroup bossGroup;
    private MultiThreadIoEventLoopGroup workerGroup;

    /**
     * <h3>启动服务端</h3>
     *
     * @param config             传输层配置
     * @param pipelineConfigurer 协议提供的 pipeline 装配器（编解码器 + 业务 handler）
     */
    public void start(TransportConfig config, ChannelPipelineConfigurer pipelineConfigurer) {
        bossGroup = NettyEventLoopGroup.getBoss();
        workerGroup = NettyEventLoopGroup.getWorker();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        pipelineConfigurer.configure(ch.pipeline());
                    }
                });

        bootstrap.bind(config.getPort())
                .addListener(f -> {
                    if (f.isSuccess()) {
                        log.info("Transport server started on port {}", config.getPort());
                    } else {
                        log.error("Transport server start failed", f.cause());
                    }
                });
    }

    /**
     * <h3>优雅关闭服务端</h3>
     */
    public void stop() {
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        log.info("Transport server stopped");
    }
}
