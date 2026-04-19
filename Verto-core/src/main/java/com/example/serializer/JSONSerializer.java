package com.example.serializer;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class JSONSerializer implements Serializer {

    @Override
    public byte getCode() {
        return 1;
    }

    @Override
    public String getName() {
        return "json";
    }

    @Override
    public <T> byte[] serialize(T object) {
        return JSON.toJSONString(object).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public <T> T deserialize(byte[] bytes, Class<T> clazz) {
        return JSON.parseObject(new String(bytes, StandardCharsets.UTF_8), clazz);
    }
}