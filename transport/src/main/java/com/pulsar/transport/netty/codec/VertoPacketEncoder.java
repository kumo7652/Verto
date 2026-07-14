package com.pulsar.transport.netty.codec;

import com.pulsar.protocol.verto.VertoPacket;
import com.pulsar.serializer.Serializer;
import com.pulsar.serializer.SerializerFactory;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * <h3>Verto 协议编码器（Outbound，Sharable）</h3>
 * 将 {@link VertoPacket} 编码为线上字节流，写入顺序：固定头部(18B) → body 变长区域。
 */
@ChannelHandler.Sharable
public class VertoPacketEncoder extends MessageToByteEncoder<VertoPacket<?>> {

    @Override
    protected void encode(ChannelHandlerContext ctx, VertoPacket<?> packet, ByteBuf out) throws Exception {
        if (packet == null || packet.getHeader() == null) {
            return;
        }

        VertoPacket.Header header = packet.getHeader();

        // 1. 序列化 body（心跳包无 body）
        byte[] bodyBytes;
        if (packet.isHeartbeat()) {
            bodyBytes = new byte[0];
        } else {
            Serializer serializer = SerializerFactory.getInstance()
                .getByCode(header.getSerializerCode());
            bodyBytes = serializer.serialize(packet.getBody());
        }

        header.setContentLength(bodyBytes.length);

        // 2. 写入固定头部 (18B)
        out.writeByte(header.getMagic())             // 0
            .writeByte(header.getVersion())          // 1
            .writeByte(header.getFlags())            // 2
            .writeByte(header.getSerializerCode())   // 3
            .writeByte(header.getType())             // 4
            .writeByte(header.getStatus())           // 5
            .writeLong(header.getRequestId())        // 6-13
            .writeInt(header.getContentLength());    // 14-17

        // 3. 写入 body
        if (bodyBytes.length > 0) {
            out.writeBytes(bodyBytes);
        }
    }
}
