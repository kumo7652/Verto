package com.pulsar.transport.netty.codec;

import com.pulsar.model.RpcRequest;
import com.pulsar.model.RpcResponse;
import com.pulsar.protocol.ProtocolException;
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
 * 帧边界检测 + 语义解码：从 TCP 字节流中检测帧边界（处理粘包/半包）→
 * magic 提前校验 → 解码固定头部(18B) → 反序列化 body。
 * <p>
 * 每 Channel 独立实例（非 Sharable），{@link ByteToMessageDecoder} 维护 cumulative buffer
 */
public class VertoPacketDecoder extends ByteToMessageDecoder {
    private static final int MAX_FRAME = VertoPacketSpec.HEADER_LENGTH_V2 + VertoPacketSpec.MAX_CONTENT_LENGTH;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() < VertoPacketSpec.HEADER_LENGTH_V2) {
            return;
        }

        // magic 提前校验
        in.markReaderIndex();
        if (in.readByte() != VertoPacketSpec.MAGIC) {
            ctx.close();
            return;
        }
        in.resetReaderIndex();

        // 读取 contentLength，计算完整帧长
        int contentLength = in.getInt(VertoPacketSpec.CONTENT_LENGTH_OFFSET);
        int frameLength = VertoPacketSpec.HEADER_LENGTH_V2 + contentLength;

        if (frameLength > MAX_FRAME) {
            ctx.close();
            return;
        }

        if (in.readableBytes() < frameLength) {
            return;
        }

        ByteBuf frame = in.readRetainedSlice(frameLength);
        try {
            VertoPacket<?> packet = decodeFrame(frame);
            out.add(packet);
        } finally {
            frame.release();
        }
    }

    private static VertoPacket<?> decodeFrame(ByteBuf buffer) throws Exception {
        byte magic = buffer.getByte(VertoPacketSpec.MAGIC_OFFSET);
        if (magic != VertoPacketSpec.MAGIC) {
            throw new ProtocolException("非法包: magic 不匹配");
        }

        byte version = buffer.getByte(VertoPacketSpec.VERSION_OFFSET);
        if (version != VertoPacketSpec.VERSION_V2) {
            throw new ProtocolException("不支持的协议版本: " + version + ", 仅支持 v2");
        }

        VertoPacket.Header header = VertoPacket.Header.builder()
                .magic(magic)
                .version(version)
                .flags(buffer.getByte(VertoPacketSpec.FLAGS_OFFSET))
                .serializerCode(buffer.getByte(VertoPacketSpec.SERIALIZER_OFFSET))
                .type(buffer.getByte(VertoPacketSpec.TYPE_OFFSET))
                .status(buffer.getByte(VertoPacketSpec.STATUS_OFFSET))
                .requestId(buffer.getLong(VertoPacketSpec.REQUEST_ID_OFFSET))
                .contentLength(buffer.getInt(VertoPacketSpec.CONTENT_LENGTH_OFFSET))
                .build();

        if (header.getContentLength() > VertoPacketSpec.MAX_CONTENT_LENGTH) {
            throw new ProtocolException("contentLength 超限: " + header.getContentLength()
                    + ", 上限 " + VertoPacketSpec.MAX_CONTENT_LENGTH);
        }

        // 心跳帧无 body
        if (isHeartbeat(header)) {
            VertoPacket<Void> packet = new VertoPacket<>();
            packet.setHeader(header);
            return packet;
        }

        // 解码 body
        int bodyLength = header.getContentLength();
        if (bodyLength == 0) {
            PacketType packetType = PacketType.fromValue(header.getType());
            if (packetType == null) {
                throw new ProtocolException("无效的帧类型: " + header.getType());
            }
            return createEmptyPacket(header, packetType);
        }

        byte[] bodyBytes = new byte[bodyLength];
        buffer.getBytes(VertoPacketSpec.HEADER_LENGTH_V2, bodyBytes);

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
                yield packet;
            }
            case RESPONSE -> {
                RpcResponse response = serializer.deserialize(bodyBytes, RpcResponse.class);
                VertoPacket<RpcResponse> packet = new VertoPacket<>();
                packet.setHeader(header);
                packet.setBody(response);
                yield packet;
            }
            case HEARTBEAT -> {
                VertoPacket<Void> packet = new VertoPacket<>();
                packet.setHeader(header);
                yield packet;
            }
        };
    }

    private static VertoPacket<?> createEmptyPacket(VertoPacket.Header header, PacketType packetType) throws ProtocolException {
        return switch (packetType) {
            case HEARTBEAT -> {
                VertoPacket<Void> packet = new VertoPacket<>();
                packet.setHeader(header);
                yield packet;
            }
            default -> throw new ProtocolException("非心跳包 body 长度为 0");
        };
    }

    private static boolean isHeartbeat(VertoPacket.Header header) {
        return (header.getFlags() & VertoPacketSpec.FLAG_HEARTBEAT_NO_BODY) != 0;
    }
}
