package com.pulsar.protocol.verto;

import com.pulsar.model.RpcRequest;
import com.pulsar.model.RpcResponse;
import com.pulsar.protocol.ProtocolException;
import com.pulsar.serializer.Serializer;
import com.pulsar.serializer.SerializerFactory;
import io.vertx.core.buffer.Buffer;

/**
 * <h3>Verto v2 协议包解码器</h3>
 * 从 Vert.x {@link Buffer} 解码出 {@link VertoPacket}，仅支持 v2 协议。
 * 安全校验在反序列化之前执行，是最早的防御线：
 * <ol>
 *   <li>magic + version 校验——非法帧直接拒绝</li>
 *   <li>contentLength / attLength 上限检查——防止恶意声明导致 OOM</li>
 *   <li>attLength ≤ contentLength 一致性检查</li>
 * </ol>
 *
 * @see VertoPacketEncoder
 * @see VertoPacketSpec
 */
public class VertoPacketDecoder {

    private VertoPacketDecoder() {}

    /**
     * <h3>解码数据包</h3>
     * 解码 20B 固定头部 → 安全校验 → 解码 attachment → 反序列化 body。
     * 帧拆分由传输层完成，传入的 buffer 应包含完整的固定头部 + 变长区域
     *
     * @param buffer 完整帧数据
     * @return 解码后的数据包对象
     * @throws Exception 解码或反序列化失败时抛出
     */
    public static VertoPacket<?> decode(Buffer buffer) throws Exception {
        if (buffer == null || buffer.length() < VertoPacketSpec.V2_HEADER_LENGTH) {
            throw new ProtocolException("包长度不足, 至少需要 " + VertoPacketSpec.V2_HEADER_LENGTH + " 字节");
        }

        // 校验 magic
        byte magic = buffer.getByte(VertoPacketSpec.MAGIC_OFFSET);
        if (magic != VertoPacketSpec.MAGIC) {
            throw new ProtocolException("非法包: magic 不匹配");
        }

        // 校验 version
        byte version = buffer.getByte(VertoPacketSpec.VERSION_OFFSET);
        if (version != VertoPacketSpec.VERSION_V2) {
            throw new ProtocolException("不支持的协议版本: " + version + ", 仅支持 v2");
        }

        // 解码固定头部
        VertoPacket.Header header = VertoPacket.Header.builder()
            .magic(magic)
            .version(version)
            .flags(buffer.getByte(VertoPacketSpec.FLAGS_OFFSET))
            .serializerCode(buffer.getByte(VertoPacketSpec.SERIALIZER_OFFSET))
            .type(buffer.getByte(VertoPacketSpec.TYPE_OFFSET))
            .status(buffer.getByte(VertoPacketSpec.STATUS_OFFSET))
            .requestId(buffer.getLong(VertoPacketSpec.REQUEST_ID_OFFSET))
            .contentLength(buffer.getInt(VertoPacketSpec.CONTENT_LENGTH_OFFSET))
            .attLength(buffer.getShort(VertoPacketSpec.ATT_LENGTH_OFFSET))
            .build();

        // 安全校验
        if (header.getContentLength() > VertoPacketSpec.MAX_CONTENT_LENGTH) {
            throw new ProtocolException("contentLength 超限: " + header.getContentLength()
                + ", 上限 " + VertoPacketSpec.MAX_CONTENT_LENGTH);
        }
        if (header.getAttLength() > VertoPacketSpec.MAX_ATT_LENGTH) {
            throw new ProtocolException("attLength 超限: " + header.getAttLength()
                + ", 上限 " + VertoPacketSpec.MAX_ATT_LENGTH);
        }
        if (header.getAttLength() > header.getContentLength()) {
            throw new ProtocolException("attLength(" + header.getAttLength()
                + ") 大于 contentLength(" + header.getContentLength() + ")");
        }

        // 解码 attachment
        PacketAttachment attachment = null;
        int offset = VertoPacketSpec.V2_HEADER_LENGTH;
        if (header.getAttLength() > 0) {
            byte[] attBytes = buffer.getBytes(offset, offset + header.getAttLength());
            attachment = PacketAttachment.decode(attBytes);
            offset += header.getAttLength();
        }

        // 心跳帧无 body
        if (isHeartbeat(header)) {
            VertoPacket<Void> packet = new VertoPacket<>();
            packet.setHeader(header);
            packet.setAttachment(attachment);
            return packet;
        }

        // 解码 body
        int bodyLength = header.getContentLength() - header.getAttLength();
        if (bodyLength < 0) {
            throw new ProtocolException("body 长度为负: contentLength - attLength = " + bodyLength);
        }
        if (buffer.length() < offset + bodyLength) {
            throw new ProtocolException("body 长度不足: 需要 " + (offset + bodyLength)
                + " 字节, 实际 " + buffer.length() + " 字节");
        }

        byte[] bodyBytes = buffer.getBytes(offset, offset + bodyLength);

        Serializer serializer = SerializerFactory.getInstance()
            .getByCode(header.getSerializerCode());

        PacketType packetType = PacketType.fromValue(header.getType());
        if (packetType == null) {
            throw new ProtocolException("无效的帧类型: " + header.getType());
        }

        return switch (packetType) {
            case REQUEST -> {
                RpcRequest request = serializer.deserialize(bodyBytes, RpcRequest.class);
                VertoPacket<RpcRequest> packet = new VertoPacket<>();
                packet.setHeader(header);
                packet.setBody(request);
                packet.setAttachment(attachment);
                yield packet;
            }
            case RESPONSE -> {
                RpcResponse response = serializer.deserialize(bodyBytes, RpcResponse.class);
                VertoPacket<RpcResponse> packet = new VertoPacket<>();
                packet.setHeader(header);
                packet.setBody(response);
                packet.setAttachment(attachment);
                yield packet;
            }
            case HEARTBEAT -> {
                VertoPacket<Void> packet = new VertoPacket<>();
                packet.setHeader(header);
                packet.setAttachment(attachment);
                yield packet;
            }
        };
    }

    private static boolean isHeartbeat(VertoPacket.Header header) {
        return (header.getFlags() & VertoPacketSpec.FLAG_HEARTBEAT_NO_BODY) != 0;
    }
}
