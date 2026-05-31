package com.pulsar.transport.netty.server;

import com.pulsar.model.RpcRequest;
import com.pulsar.model.RpcResponse;
import com.pulsar.protocol.verto.*;
import com.pulsar.transport.RequestHandler;
import com.pulsar.transport.config.TransportConfig;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/**
 * <h3>服务端业务处理器</h3>
 * 接收已解码的 {@link VertoPacket}，按包类型分发：
 * REQUEST → 调用 {@link RequestHandler} 处理 → 写回 RESPONSE；
 * HEARTBEAT → 写回心跳确认
 */
@Slf4j
@ChannelHandler.Sharable
public class NettyServerHandler extends SimpleChannelInboundHandler<VertoPacket<RpcRequest>> {

    private final RequestHandler requestHandler;
    private final TransportConfig config;

    public NettyServerHandler(RequestHandler requestHandler, TransportConfig config) {
        super(false);  // 不自动释放，由上游 ByteToMessageDecoder 管理
        this.requestHandler = requestHandler;
        this.config = config;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, VertoPacket<RpcRequest> requestPacket) {
        PacketType packetType = PacketType.fromValue(requestPacket.getHeader().getType());

        switch (Objects.requireNonNull(packetType)) {
            case REQUEST -> handleRequest(ctx, requestPacket);
            case HEARTBEAT -> handleHeartbeat(ctx, requestPacket);
            case RESPONSE -> log.warn("服务端收到 RESPONSE 包，忽略");
            default -> log.warn("未知包类型: {}", packetType);
        }
    }

    private void handleRequest(ChannelHandlerContext ctx, VertoPacket<RpcRequest> requestPacket) {
        long requestId = requestPacket.getHeader().getRequestId();
        try {
            VertoPacket<RpcResponse> responsePacket = requestHandler.handle(requestPacket);
            ctx.writeAndFlush(responsePacket);
        } catch (Exception e) {
            log.error("请求处理异常, requestId={}", requestId, e);
            VertoPacket<RpcResponse> errorPacket = VertoPacket.fail(
                    requestId,
                    PacketStatus.SERVER_ERROR,
                    e.getMessage(),
                    config.getSerializerKey()
            );
            ctx.writeAndFlush(errorPacket);
        }
    }

    private void handleHeartbeat(ChannelHandlerContext ctx, VertoPacket<RpcRequest> heartbeatPacket) {
        long requestId = heartbeatPacket.getHeader().getRequestId();
        VertoPacket<Void> ack = VertoPacket.heartbeat(requestId);
        ctx.writeAndFlush(ack);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("连接关闭, remote={}", ctx.channel().remoteAddress());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("连接异常, remote={}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }
}
