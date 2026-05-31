package com.pulsar.transport.netty.codec;

import com.pulsar.model.RpcRequest;
import com.pulsar.model.RpcResponse;
import com.pulsar.protocol.ProtocolException;
import com.pulsar.protocol.verto.PacketAttachment;
import com.pulsar.protocol.verto.PacketType;
import com.pulsar.protocol.verto.VertoPacket;
import com.pulsar.protocol.verto.VertoPacketSpec;
import com.pulsar.serializer.Serializer;
import com.pulsar.serializer.SerializerFactory;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * <h3>Verto 协议解码器（Inbound）</h3>
 * 合并帧拆分和语义解码：从 TCP 字节流中检测帧边界（处理粘包/半包）→
 * magic 提前校验（非法帧不等 body 即拒绝）→ 解码固定头部 → 校验 → 反序列化 body。
 * <p>
 * 每 Channel 独立实例（非 Sharable），因为 {@link ByteToMessageDecoder} 维护 cumulative buffer
 */
public class VertoPacketDecoder extends ByteToMessageDecoder {
    private static final int MAX_FRAME = VertoPacketSpec.HEADER_LENGTH_V2 + VertoPacketSpec.MAX_CONTENT_LENGTH;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        // 1. 至少需要 20B 固定头部
        if (in.readableBytes() < VertoPacketSpec.HEADER_LENGTH_V2) {
            return;
        }

        // 2. magic 提前校验（非法帧立即拒绝，不等 body）
        in.markReaderIndex();
        if (in.readByte() != VertoPacketSpec.MAGIC) {
            ctx.close();
            return;
        }
        in.resetReaderIndex();

        // 3. 读取 contentLength，计算完整帧长
        int contentLength = in.getInt(VertoPacketSpec.CONTENT_LENGTH_OFFSET);
        int frameLength = VertoPacketSpec.HEADER_LENGTH_V2 + contentLength;

        // 4. 超长帧保护
        if (frameLength > MAX_FRAME) {
            ctx.close();
            return;
        }

        // 5. 等待完整帧到达（半包时返回，框架自动累积）
        if (in.readableBytes() < frameLength) {
            return;
        }

        // 6. 提取完整帧 → 语义解码
        ByteBuf frame = in.readRetainedSlice(frameLength);
        try {
            VertoPacket<?> packet = decodeFrame(frame);
            out.add(packet);
        } finally {
            frame.release();
        }
    }

    /**
     * <h3>语义解码一个完整帧</h3>
     * 校验 magic/version → 解码固定头部 → 安全校验 → 解码 attachment → 反序列化 body
     */
    private static VertoPacket<?> decodeFrame(ByteBuf buffer) throws Exception {
        // 校验 magic（帧边界层已做，此处防御性再做一次）
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
        int offset = VertoPacketSpec.HEADER_LENGTH_V2;
        if (header.getAttLength() > 0) {
            byte[] attBytes = new byte[header.getAttLength()];
            buffer.getBytes(offset, attBytes);
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

        byte[] bodyBytes = new byte[bodyLength];
        buffer.getBytes(offset, bodyBytes);

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
