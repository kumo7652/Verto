package com.pulsar.core.protocol.verto;

import com.pulsar.core.protocol.RemoteRequest;
import com.pulsar.core.protocol.RemoteResponse;

import com.pulsar.serializer.Serializer;
import com.pulsar.serializer.SerializerFactory;

import java.io.IOException;

/**
 * <h3>Verto 协议编解码器</h3>
 * 负责 RPC 负载（{@link RemoteRequest}/{@link RemoteResponse}）
 * 与字节数组之间的转换。传输层只认 byte[]，类型化编解码统一收敛在此处。
 */
public class VertoCodec {

    private final SerializerFactory serializerFactory = SerializerFactory.getInstance();

    public byte[] encodeRequest(RemoteRequest request, String serializerKey) throws IOException {
        return getSerializer(serializerKey).serialize(request);
    }

    public RemoteRequest decodeRequest(byte[] bytes, String serializerKey) throws IOException {
        return getSerializer(serializerKey).deserialize(bytes, RemoteRequest.class);
    }

    public byte[] encodeResponse(RemoteResponse response, String serializerKey) throws IOException {
        return getSerializer(serializerKey).serialize(response);
    }

    public RemoteResponse decodeResponse(byte[] bytes, String serializerKey) throws IOException {
        return getSerializer(serializerKey).deserialize(bytes, RemoteResponse.class);
    }

    /** 根据序列化器别名取协议头里的 serializerCode */
    public byte getSerializerCode(String serializerKey) {
        return serializerFactory.getCodeByName(serializerKey);
    }

    private Serializer getSerializer(String key) {
        byte code = serializerFactory.getCodeByName(key);
        return serializerFactory.getByCode(code);
    }
}
