package com.example.protocol;

import com.example.serializer.Serializer;
import com.example.serializer.SerializerFactory;
import io.vertx.core.buffer.Buffer;

import java.io.IOException;

public class MessageEncoder {
    public static Buffer encode(ProtocolMessage<?> message) throws IOException {
        if (message == null || message.getHeader() == null) {
            return Buffer.buffer();
        }

        ProtocolMessage.Header header = message.getHeader();

        // 依次向缓冲区写入字节
        Buffer buffer = Buffer.buffer();
        buffer.appendByte(header.getMagic())            // 魔数
                .appendByte(header.getVersion())        // 版本号
                .appendByte(header.getSerializerCode()) // 序列化器
                .appendByte(header.getType())           // 类型
                .appendByte(header.getStatus())         // 状态
                .appendLong(header.getRequestId());     // 请求id

        // 根据协议头编号获取序列化器实例
        Serializer serializer = SerializerFactory.getInstance().getByCode(header.getSerializerCode());

        // 将消息体进行序列化
        byte[] messageBody = serializer.serialize(message.getBody());

        // 写入消息体长度与数据
        buffer.appendInt(messageBody.length);
        buffer.appendBytes(messageBody);

        return buffer;
    }
}
