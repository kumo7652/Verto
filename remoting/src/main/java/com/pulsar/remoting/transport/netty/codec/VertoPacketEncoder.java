package com.pulsar.remoting.transport.netty.codec;

import com.pulsar.remoting.protocol.VertoPacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * <h3>Verto 帧编码器（Outbound, Sharable）</h3>
 * 将 {@link VertoPacket} 编码为线上字节流。只负责帧编码（写 header + 搬运 body bytes），
 * body 的序列化由调用方提前完成，到达此处时 body 必须是 byte[]。
 */
@ChannelHandler.Sharable
public class VertoPacketEncoder extends MessageToByteEncoder<VertoPacket> {

    @Override
    protected void encode(ChannelHandlerContext ctx, VertoPacket packet, ByteBuf out) {
        if (packet == null || packet.getHeader() == null) {
            return;
        }

        VertoPacket.Header header = packet.getHeader();
        byte[] bodyBytes = packet.getBodyBytes();
        if (bodyBytes == null) {
            bodyBytes = new byte[0];
        }

        header.setContentLength(bodyBytes.length);

        // 写入固定头部 (18B)
        out.writeByte(header.getMagic())
           .writeByte(header.getVersion())
           .writeByte(header.getFlags())
           .writeByte(header.getSerializerCode())
           .writeByte(header.getType())
           .writeByte(header.getStatus())
           .writeLong(header.getRequestId())
           .writeInt(bodyBytes.length);

        // 写入 body
        if (bodyBytes.length > 0) {
            out.writeBytes(bodyBytes);
        }
    }
}
