package com.pulsar.remoting.message;

import lombok.*;

/**
 * <h3>Verto 协议数据包</h3>
 * 一个可在线路上传输的完整数据单元，由 18B 固定头部和变长 body 组成。
 * body 在不同 pipeline 阶段承载不同类型：Decoder 产出 raw byte[]，
 * RpcCodec 替换为 typed Request/Response，Encoder 消费 byte[]。
 */
@Getter
@Setter
public class VertoPacket {

    private Header header;
    private Object body;   // byte[] | Request | Response | null

    public VertoPacket() {
    }

    public VertoPacket(Header header) {
        this(header, null);
    }

    public VertoPacket(Header header, Object body) {
        this.header = header;
        this.body = body;
    }

    public static Header requestHeader(long requestId, byte serializerCode) {
        return Header.builder()
            .magic(VertoPacketSpec.MAGIC)
            .version(VertoPacketSpec.VERSION_V2)
            .flags((byte) 0)
            .serializerCode(serializerCode)
            .type((byte) PacketType.REQUEST.getValue())
            .status((byte) PacketStatus.SUCCESS.getValue())
            .requestId(requestId)
            .contentLength(0)
            .build();
    }

    public static Header responseHeader(long requestId, byte serializerCode) {
        return Header.builder()
            .magic(VertoPacketSpec.MAGIC)
            .version(VertoPacketSpec.VERSION_V2)
            .flags((byte) 0)
            .serializerCode(serializerCode)
            .type((byte) PacketType.RESPONSE.getValue())
            .status((byte) PacketStatus.SUCCESS.getValue())
            .requestId(requestId)
            .contentLength(0)
            .build();
    }

    /**
     * 创建心跳包
     */
    public static VertoPacket heartbeat(long requestId) {
        Header header = Header.builder()
            .magic(VertoPacketSpec.MAGIC)
            .version(VertoPacketSpec.VERSION_V2)
            .flags(VertoPacketSpec.FLAG_HEARTBEAT_NO_BODY)
            .serializerCode((byte) 0)
            .type((byte) PacketType.HEARTBEAT.getValue())
            .status((byte) PacketStatus.SUCCESS.getValue())
            .requestId(requestId)
            .contentLength(0)
            .build();
        return new VertoPacket(header);
    }

    /**
     * body as raw bytes（Decoder 产出 / Encoder 消费）
     */
    public byte[] getBodyBytes() {
        return (byte[]) body;
    }

    /**
     * body as typed object（RpcCodec 产出 / Handler 消费）
     */
    @SuppressWarnings("unchecked")
    public <T> T getBody() {
        return (T) body;
    }

    // ─── Header 工厂（不依赖 serializer，调用方传入已解析的 code）───

    public boolean isHeartbeat() {
        return header != null && (header.flags & VertoPacketSpec.FLAG_HEARTBEAT_NO_BODY) != 0;
    }

    public boolean isOneWay() {
        return header != null && (header.flags & VertoPacketSpec.FLAG_ONE_WAY) != 0;
    }

    /**
     * body 是否是 raw bytes（未经 RpcCodec 解码）
     */
    public boolean isRaw() {
        return body instanceof byte[];
    }

    // ─── Header (18B 线格式) ───

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Header {
        private byte magic;
        private byte version;
        private byte flags;
        private byte serializerCode;
        private byte type;
        private byte status;
        private long requestId;
        private int contentLength;
    }
}
