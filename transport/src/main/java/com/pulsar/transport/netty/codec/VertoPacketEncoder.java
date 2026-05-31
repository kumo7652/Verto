package com.pulsar.transport.netty.codec;

import com.pulsar.protocol.verto.VertoPacket;
import com.pulsar.serializer.Serializer;
import com.pulsar.serializer.SerializerFactory;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * <h3>Verto 协议编码器（Outbound）</h3>
 * 将 {@link VertoPacket} 编码为线上字节流，写入顺序：
 * 固定头部(20B) → attachment 变长区域 → body 变长区域。
 * 标注 {@link Sharable}，可在多个 Channel 间安全共享
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

        // 2. 编码 attachment
        byte[] attBytes;
        if (packet.hasAttachment() && packet.getAttachment() != null) {
            attBytes = packet.getAttachment().encode();
        } else {
            attBytes = new byte[0];
        }

        // 3. 计算并填充 contentLength
        int contentLength = attBytes.length + bodyBytes.length;
        header.setContentLength(contentLength);
        header.setAttLength((short) attBytes.length);

        // 4. 写入固定头部 (20B)
        out.writeByte(header.getMagic())            // 0
           .writeByte(header.getVersion())          // 1
           .writeByte(header.getFlags())            // 2
           .writeByte(header.getSerializerCode())   // 3
           .writeByte(header.getType())             // 4
           .writeByte(header.getStatus())           // 5
           .writeLong(header.getRequestId())        // 6-13
           .writeInt(contentLength)                 // 14-17
           .writeShort((short) attBytes.length);    // 18-19

        // 5. 写入变长区域：attachment + body
        if (attBytes.length > 0) {
            out.writeBytes(attBytes);
        }
        if (bodyBytes.length > 0) {
            out.writeBytes(bodyBytes);
        }
    }
}
