package com.pulsar.remoting.transport.netty.server;

import com.pulsar.remoting.transport.RequestHandler;
import com.pulsar.config.TransportConfig;
import com.pulsar.remoting.transport.netty.NettyEventLoopGroup;
import com.pulsar.remoting.transport.netty.codec.VertoPacketDecoder;
import com.pulsar.remoting.transport.netty.codec.VertoPacketEncoder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutorService;

/**
 * <h3>基于 Netty 的传输层服务端</h3>
 * 负责启动 TCP 监听、组装 Pipeline（VertoPacketDecoder → VertoPacketEncoder → NettyServerHandler）、
 * 以及优雅关闭
 */
@Slf4j
public class NettyTransportServer {
    private final VertoPacketEncoder encoderHandler = new VertoPacketEncoder();
    private MultiThreadIoEventLoopGroup bossGroup;
    private MultiThreadIoEventLoopGroup workerGroup;

    /**
     * <h3>启动服务端</h3>
     *
     * @param config         传输层配置
     * @param requestHandler 请求处理回调
     * @param businessPool   业务线程池，用于派发 RPC 请求，避免阻塞 I/O 线程
     */
    public void start(TransportConfig config, RequestHandler requestHandler, ExecutorService businessPool) {
        bossGroup = NettyEventLoopGroup.getBoss();
        workerGroup = NettyEventLoopGroup.getWorker();

        NettyServerHandler serverHandler = new NettyServerHandler(requestHandler, config, businessPool);

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                          .addLast(new VertoPacketDecoder())
                          .addLast(encoderHandler)
                          .addLast(serverHandler);
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
