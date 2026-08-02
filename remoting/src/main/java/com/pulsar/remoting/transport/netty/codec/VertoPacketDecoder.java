package com.pulsar.remoting.transport.netty.codec;

import com.pulsar.remoting.protocol.ProtocolException;
import com.pulsar.remoting.protocol.VertoPacket;
import com.pulsar.remoting.protocol.VertoPacketSpec;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * <h3>Verto 帧解码器（Inbound）</h3>
 * 帧边界检测 + 帧解码：从 TCP 流中拆分完整帧 → 读 18B header → 读 body bytes。
 * 不做反序列化——body 以 raw byte[] 原样传出，由调用方按需反序列化。
 */
public class VertoPacketDecoder extends ByteToMessageDecoder {
    private static final int MAX_FRAME = VertoPacketSpec.HEADER_LENGTH_V2 + VertoPacketSpec.MAX_CONTENT_LENGTH;

    private static VertoPacket decodeFrame(ByteBuf buffer) {
        byte magic = buffer.getByte(VertoPacketSpec.MAGIC_OFFSET);
        if (magic != VertoPacketSpec.MAGIC) {
            throw new ProtocolException("magic 不匹配");
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

        int bodyLength = header.getContentLength();
        if (bodyLength > VertoPacketSpec.MAX_CONTENT_LENGTH) {
            throw new ProtocolException("contentLength 超限: " + bodyLength);
        }

        // 读 body bytes（不反序列化）
        byte[] bodyBytes = new byte[bodyLength];
        buffer.getBytes(VertoPacketSpec.HEADER_LENGTH_V2, bodyBytes);

        return new VertoPacket(header, bodyBytes);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
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
            VertoPacket packet = decodeFrame(frame);
            out.add(packet);
        } finally {
            frame.release();
        }
    }
}
