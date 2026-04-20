package com.pulsar.serializer;

import com.alibaba.fastjson2.JSON;
import com.pulsar.extension.SpiExtension;

import java.nio.charset.StandardCharsets;

@SpiExtension(name = "json", code = 1)
public class JSONSerializer implements Serializer {

    @Override
    public <T> byte[] serialize(T object) {
        return JSON.toJSONString(object).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public <T> T deserialize(byte[] bytes, Class<T> clazz) {
        return JSON.parseObject(new String(bytes, StandardCharsets.UTF_8), clazz);
    }
}