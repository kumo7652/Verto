package com.pulsar.transport.netty.client;

import com.pulsar.model.RpcResponse;
import com.pulsar.protocol.verto.PacketType;
import com.pulsar.protocol.verto.VertoPacket;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/**
 * <h3>客户端业务处理器（单例）</h3>
 * 接收已解码的 {@link VertoPacket}，按包类型分发：
 * RESPONSE → 交给 {@link ResponseDispatcher} 完成对应 Future；
 * HEARTBEAT → 记录心跳响应
 */
@Slf4j
@ChannelHandler.Sharable
public class NettyClientHandler extends SimpleChannelInboundHandler<VertoPacket<RpcResponse>> {
    private static final NettyClientHandler INSTANCE = new NettyClientHandler();

    private final ResponseDispatcher dispatcher = ResponseDispatcher.getInstance();
    private final ConnectionManager connectionManager = ConnectionManager.getInstance();

    private NettyClientHandler() {
        super(false);
    }

    public static NettyClientHandler getInstance() {
        return INSTANCE;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, VertoPacket<RpcResponse> packet) {
        PacketType packetType = PacketType.fromValue(packet.getHeader().getType());

        switch (Objects.requireNonNull(packetType)) {
            case RESPONSE -> dispatcher.dispatch(packet);
            case HEARTBEAT -> log.debug("收到心跳响应, remote={}", ctx.channel().remoteAddress());
            case REQUEST -> log.warn("客户端收到 REQUEST 包，忽略");
            default -> log.warn("未知的包类型: {}", packetType);
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
