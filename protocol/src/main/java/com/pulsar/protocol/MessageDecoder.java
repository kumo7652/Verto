package com.pulsar.protocol;

import com.pulsar.constant.ProtocolConstant;
import com.pulsar.exception.ProtocolException;
import com.pulsar.model.RpcRequest;
import com.pulsar.model.RpcResponse;
import com.pulsar.serializer.Serializer;
import com.pulsar.serializer.SerializerFactory;
import io.vertx.core.buffer.Buffer;

import java.io.IOException;

public class MessageDecoder {
    public static ProtocolMessage<?> decode(Buffer buffer) throws IOException {
        // 从buffer指定位置分别取数据
        byte magic = buffer.getByte(ProtocolConstant.MAGIC_OFFSET);

        if (magic != ProtocolConstant.MAGIC) {
            throw new ProtocolException("非法消息");
        }

        ProtocolMessage.Header header = ProtocolMessage.Header.builder()
                .magic(magic)
                .version(buffer.getByte(ProtocolConstant.VERSION_OFFSET))
                .serializerCode(buffer.getByte(ProtocolConstant.SERIALIZER_OFFSET))
                .type(buffer.getByte(ProtocolConstant.TYPE_OFFSET))
                .status(buffer.getByte(ProtocolConstant.STATUS_OFFSET))
                .requestId(buffer.getLong(ProtocolConstant.REQUEST_ID_OFFSET))
                .bodyLength(buffer.getInt(ProtocolConstant.BODY_LENGTH_OFFSET))
                .build();
        byte[] body = buffer.getBytes(
                ProtocolConstant.MESSAGE_HEADER_LENGTH,
                ProtocolConstant.MESSAGE_HEADER_LENGTH + header.getBodyLength()
        );

        // 根据协议头编号获取序列化器实例
        Serializer serializer = SerializerFactory.getInstance().getByCode(header.getSerializerCode());

        MessageTypeEnum messageTypeEnum = MessageTypeEnum.getEnumByKey(header.getType());
        if (messageTypeEnum == null) {
            throw new ProtocolException("无效的消息类型");
        }

        return switch (messageTypeEnum) {
            case REQUEST -> {
                RpcRequest request = serializer.deserialize(body, RpcRequest.class);
                yield new ProtocolMessage<>(header, request);
            }

            case RESPONSE -> {
                RpcResponse response = serializer.deserialize(body, RpcResponse.class);
                yield new ProtocolMessage<>(header, response);
            }
            default -> throw new ProtocolException("不支持的消息类型");
        };
    }
}
