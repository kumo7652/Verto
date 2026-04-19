package com.example.serializer;

import java.io.IOException;

public interface Serializer {

    /**
     * 序列化器编号，写入协议头
     */
    byte getCode();

    /**
     * 序列化器名称，用于 SPI key
     */
    String getName();

    /**
     * 序列化 - 将Java对象转为字节数组
     */
    <T> byte[] serialize(T object) throws IOException;

    /**
     * 反序列化 - 将字节数组转换为Java对象
     */
    <T> T deserialize(byte[] bytes, Class<T> clazz) throws IOException;
}
