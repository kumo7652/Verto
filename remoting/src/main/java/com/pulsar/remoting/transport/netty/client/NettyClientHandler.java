package com.pulsar.remoting.transport.netty.client;

import com.pulsar.remoting.exchange.ResponseDispatcher;
import com.pulsar.remoting.message.PacketType;
import com.pulsar.remoting.message.VertoPacket;
import com.pulsar.utils.RequestIdGenerator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * <h3>客户端业务处理器（单例）</h3>
 * 接收已解码的 {@link VertoPacket}，按包类型分发：
 * RESPONSE → 交给 {@link ResponseDispatcher} 完成对应 Future；
 * HEARTBEAT → 记录心跳响应
 */
@ChannelHandler.Sharable
public class NettyClientHandler extends SimpleChannelInboundHandler<VertoPacket> {

    private static final Logger log = LoggerFactory.getLogger(NettyClientHandler.class);
    private static final NettyClientHandler INSTANCE = new NettyClientHandler();

    private final ResponseDispatcher dispatcher;
    private final ConnectionManager connectionManager;

    private NettyClientHandler() {
        super(false);

        dispatcher = ResponseDispatcher.getInstance();
        connectionManager = ConnectionManager.getInstance();
    }

    public static NettyClientHandler getInstance() {
        return INSTANCE;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, VertoPacket packet) {
        PacketType packetType = PacketType.fromValue(packet.getHeader().getType());

        switch (Objects.requireNonNull(packetType)) {
            case RESPONSE -> dispatcher.complete(packet.getHeader().getRequestId(), packet.getBodyBytes());
            case HEARTBEAT -> log.debug("收到心跳响应, remote={}", ctx.channel().remoteAddress());
            case REQUEST -> log.warn("客户端收到 REQUEST 包，忽略");
            default -> log.warn("未知的包类型: {}", packetType);
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent e) {
            switch (e.state()) {
                case READER_IDLE -> {
                    log.warn("读超时, remote={}", ctx.channel().remoteAddress());
                    dispatcher.closeChannel(ctx.channel(), new RuntimeException("读超时"));
                    connectionManager.remove(ctx.channel());
                    ctx.close();
                }
                case ALL_IDLE -> ctx.writeAndFlush(VertoPacket.heartbeat(RequestIdGenerator.nextId()));
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("连接关闭, remote={}", ctx.channel().remoteAddress());
        connectionManager.remove(ctx.channel());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("连接异常, remote={}", ctx.channel().remoteAddress(), cause);
        connectionManager.remove(ctx.channel());
        ctx.close();
    }
}
