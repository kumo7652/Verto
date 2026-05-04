package com.pulsar.protocol.verto;

import com.pulsar.serializer.Serializer;
import com.pulsar.serializer.SerializerFactory;
import io.vertx.core.buffer.Buffer;

/**
 * <h3>Verto v2 协议包编码器</h3>
 * 将 {@link VertoPacket} 编码为线上字节流，写入顺序为：
 * 固定头部(20B) → attachment 变长区域 → body 变长区域。
 * 不涉及压缩（compress 已移除），不涉及帧拆分（传输层职责）
 *
 * @see VertoPacketDecoder
 * @see VertoPacketSpec
 */
public class VertoPacketEncoder {

    private VertoPacketEncoder() {}

    /**
     * <h3>编码数据包</h3>
     * 序列化 body → 编码 attachment → 计算 contentLength → 拼接 Buffer
     *
     * @param packet 待编码的数据包，header 和 body 均不可为 null（心跳包除外）
     * @return 编码后的 Vert.x Buffer
     * @throws Exception 序列化失败时抛出
     */
    public static Buffer encode(VertoPacket<?> packet) throws Exception {
        if (packet == null || packet.getHeader() == null) {
            return Buffer.buffer();
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

        // 4. 预分配 Buffer 并写入固定头部
        int bufferSize = VertoPacketSpec.V2_HEADER_LENGTH + contentLength;
        Buffer buffer = Buffer.buffer(bufferSize);
        buffer.appendByte(header.getMagic())            // 0
              .appendByte(header.getVersion())          // 1
              .appendByte(header.getFlags())            // 2
              .appendByte(header.getSerializerCode())   // 3
              .appendByte(header.getType())             // 4
              .appendByte(header.getStatus())           // 5
              .appendLong(header.getRequestId())        // 6-13
              .appendInt(contentLength)                 // 14-17
              .appendShort((short) attBytes.length);    // 18-19

        // 5. 写入变长区域：attachment + body
        if (attBytes.length > 0) {
            buffer.appendBytes(attBytes);
        }
        if (bodyBytes.length > 0) {
            buffer.appendBytes(bodyBytes);
        }

        return buffer;
    }
}
