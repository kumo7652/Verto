package com.pulsar.remoting.transport.netty.server;

import com.pulsar.config.TransportConfig;
import com.pulsar.remoting.message.PacketStatus;
import com.pulsar.remoting.message.PacketType;
import com.pulsar.remoting.message.VertoPacket;
import com.pulsar.remoting.transport.RequestHandler;
import com.pulsar.serializer.SerializerFactory;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * <h3>服务端业务处理器</h3>
 * 接收已解码的 {@link VertoPacket}，按包类型分发：
 * REQUEST → 调用 {@link RequestHandler} 处理 → 写回 RESPONSE；
 * HEARTBEAT → 写回心跳确认
 */
@ChannelHandler.Sharable
public class NettyServerHandler extends SimpleChannelInboundHandler<VertoPacket> {

    private static final Logger log = LoggerFactory.getLogger(NettyServerHandler.class);

    private final RequestHandler requestHandler;
    private final TransportConfig config;
    private final ExecutorService businessPool;

    public NettyServerHandler(RequestHandler requestHandler, TransportConfig config, ExecutorService businessPool) {
        super(false);  // 不自动释放，由上游 ByteToMessageDecoder 管理
        this.requestHandler = requestHandler;
        this.config = config;
        this.businessPool = Objects.requireNonNull(businessPool, "businessPool must not be null");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, VertoPacket requestPacket) {
        PacketType packetType = PacketType.fromValue(requestPacket.getHeader().getType());

        switch (Objects.requireNonNull(packetType)) {
            case REQUEST -> handleRequest(ctx, requestPacket);
            case HEARTBEAT -> handleHeartbeat(ctx, requestPacket);
            case RESPONSE -> log.warn("服务端收到 RESPONSE 包，忽略");
            default -> log.warn("未知包类型: {}", packetType);
        }
    }

    private void handleRequest(ChannelHandlerContext ctx, VertoPacket requestPacket) {
        long requestId = requestPacket.getHeader().getRequestId();
        try {
            businessPool.execute(() -> {
                try {
                    VertoPacket responsePacket = requestHandler.handle(requestPacket);
                    ctx.writeAndFlush(responsePacket);
                } catch (Exception e) {
                    log.error("请求处理异常, requestId={}", requestId, e);
                    ctx.writeAndFlush(buildErrorResponse(requestId, e.getMessage()));
                }
            });
        } catch (RejectedExecutionException e) {
            log.error("Business Pool shutdown (requestId={}): {}", requestId, e.toString());
            ctx.writeAndFlush(buildErrorResponse(requestId, "服务正在关闭"));
        }
    }

    private VertoPacket buildErrorResponse(long requestId, String message) {
        byte code = SerializerFactory.getInstance().getCodeByName(config.getSerializerKey());
        VertoPacket.Header header = VertoPacket.responseHeader(requestId, code);
        header.setStatus((byte) PacketStatus.SERVER_ERROR.getValue());
        // 延迟解码后传输层不构造业务负载，错误响应 body 留空，由调用方按 header.status 识别
        return new VertoPacket(header, new byte[0]);
    }

    private void handleHeartbeat(ChannelHandlerContext ctx, VertoPacket heartbeatPacket) {
        long requestId = heartbeatPacket.getHeader().getRequestId();
        VertoPacket ack = VertoPacket.heartbeat(requestId);
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
