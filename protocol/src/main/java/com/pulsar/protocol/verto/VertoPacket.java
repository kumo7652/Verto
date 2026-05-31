package com.pulsar.protocol.verto;

import com.pulsar.model.RpcResponse;
import com.pulsar.serializer.SerializerFactory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * <h3>Verto 私有协议数据包</h3>
 * 表示线上传输的一个完整数据单元，由固定头部 {@link Header}（18B）和变长 body 组成。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class VertoPacket<T> {

    private Header header;
    private T body;

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

    public boolean isHeartbeat() {
        return (header.flags & VertoPacketSpec.FLAG_HEARTBEAT_NO_BODY) != 0;
    }

    public boolean isOneWay() {
        return (header.flags & VertoPacketSpec.FLAG_ONE_WAY) != 0;
    }

    public static <T> VertoPacket<T> create(PacketType type, String serializerKey,
                                            long requestId, T body) {
        Header header = Header.builder()
            .magic(VertoPacketSpec.MAGIC)
            .version(VertoPacketSpec.VERSION_V2)
            .flags((byte) 0)
            .serializerCode(SerializerFactory.getInstance().getCodeByName(serializerKey))
            .type((byte) type.getValue())
            .status((byte) PacketStatus.SUCCESS.getValue())
            .requestId(requestId)
            .contentLength(0)
            .build();

        VertoPacket<T> packet = new VertoPacket<>();
        packet.setHeader(header);
        packet.setBody(body);
        return packet;
    }

    public static VertoPacket<Void> heartbeat(long requestId) {
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

        VertoPacket<Void> packet = new VertoPacket<>();
        packet.setHeader(header);
        return packet;
    }

    public static VertoPacket<RpcResponse> success(long requestId, Object data,
            Class<?> dataType, String serializerKey) {
        RpcResponse response = RpcResponse.builder()
                .data(data)
                .dataType(dataType)
                .message("success")
                .build();
        VertoPacket<RpcResponse> packet = create(PacketType.RESPONSE, serializerKey, requestId, response);
        packet.getHeader().setStatus((byte) PacketStatus.SUCCESS.getValue());
        return packet;
    }

    public static VertoPacket<RpcResponse> fail(long requestId, PacketStatus status,
            String message, String serializerKey) {
        RpcResponse response = RpcResponse.builder()
            .data(null)
            .errorCode(String.valueOf(status.getValue()))
            .errorMessage(message)
            .build();
        VertoPacket<RpcResponse> packet = create(PacketType.RESPONSE, serializerKey, requestId, response);
        packet.getHeader().setStatus((byte) status.getValue());
        return packet;
    }
}
