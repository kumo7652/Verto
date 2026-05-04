package com.pulsar.protocol.verto;

import com.pulsar.model.RpcResponse;
import com.pulsar.serializer.SerializerFactory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * <h3>Verto 私有协议数据包</h3>
 * 表示线上传输的一个完整数据单元，由固定头部 {@link Header} 和变长 body 组成。
 * 提供静态工厂方法构造请求、响应、心跳及错误包
 *
 * @see VertoPacketSpec
 * @see PacketType
 * @see PacketStatus
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class VertoPacket<T> {

    private Header header;
    private T body;
    private PacketAttachment attachment;


    /**
     * <h3>Verto 协议包头部</h3>
     * v2 固定头部共 20 字节，包含协议标识、类型、状态及变长区域长度等字段
     */
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
        private short attLength;
    }

    /**
     * <h3>判断包是否携带附件</h3>
     * 检查 flags 的第 0 位
     *
     * @return 携带附件返回 true
     */
    public boolean hasAttachment() {
        return (header.flags & VertoPacketSpec.FLAG_HAS_ATTACHMENT) != 0;
    }

    /**
     * <h3>判断是否为零负载心跳包</h3>
     * 检查 flags 的第 1 位，心跳包的 body 为 null，contentLength 可为 0
     *
     * @return 零负载心跳包返回 true
     */
    public boolean isHeartbeat() {
        return (header.flags & VertoPacketSpec.FLAG_HEARTBEAT_NO_BODY) != 0;
    }

    /**
     * <h3>判断是否为单向调用包</h3>
     * 检查 flags 的第 2 位，单向调用不期待响应
     *
     * @return 单向调用返回 true
     */
    public boolean isOneWay() {
        return (header.flags & VertoPacketSpec.FLAG_ONE_WAY) != 0;
    }

    /**
     * <h3>创建基础数据包</h3>
     * 构造带 v2 头部的数据包，自动填充 magic、version 和默认状态
     *
     * @param type          包类型
     * @param serializerKey 序列化器别名
     * @param requestId     请求 ID（Snowflake）
     * @param body          包体（RpcRequest 或 RpcResponse）
     * @return 完整的数据包对象
     */
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
            .attLength((short) 0)
            .build();

        VertoPacket<T> packet = new VertoPacket<>();
        packet.setHeader(header);
        packet.setBody(body);
        return packet;
    }

    /**
     * <h3>创建带附件的数据包</h3>
     * 在基础包上设置 FLAG_HAS_ATTACHMENT 标志并记录附件长度
     *
     * @param type          包类型
     * @param serializerKey 序列化器别名
     * @param requestId     请求 ID
     * @param body          包体
     * @param attachment    附件对象
     * @return 携带附件的数据包对象
     */
    public static <T> VertoPacket<T> createWithAttachment(PacketType type,
            String serializerKey, long requestId, T body, PacketAttachment attachment) {
        VertoPacket<T> packet = create(type, serializerKey, requestId, body);
        packet.setAttachment(attachment);
        byte[] attBytes = attachment.encode();
        packet.getHeader().setAttLength((short) attBytes.length);
        packet.getHeader().setFlags((byte) (packet.getHeader().getFlags() | VertoPacketSpec.FLAG_HAS_ATTACHMENT));
        return packet;
    }

    /**
     * <h3>创建心跳包</h3>
     * 零负载心跳包，整包仅 20 字节固定头部，body 为 null
     *
     * @param requestId 请求 ID
     * @return 心跳包对象
     */
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
            .attLength((short) 0)
            .build();

        VertoPacket<Void> packet = new VertoPacket<>();
        packet.setHeader(header);
        return packet;
    }

    /**
     * <h3>创建成功响应包</h3>
     *
     * @param requestId     请求 ID
     * @param data          响应数据
     * @param dataType      响应数据类型
     * @param serializerKey 序列化器别名
     * @return 成功的响应包对象
     */
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

    /**
     * <h3>创建错误响应包</h3>
     * 根据指定的状态码构造错误响应，替代 v1 的 badRequest() 和 error()
     *
     * @param requestId     请求 ID
     * @param status        错误状态码
     * @param message       错误信息
     * @param serializerKey 序列化器别名
     * @return 错误响应包对象
     */
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
