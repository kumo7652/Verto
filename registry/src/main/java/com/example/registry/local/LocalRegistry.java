package com.example.registry.local;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地注册中心，用于服务发现
 */
public class LocalRegistry {
    private static final Map<String, Class<?>> cache = new ConcurrentHashMap<>();

    public static void register(String serviceName, Class<?> clazz) {
        cache.put(serviceName, clazz);
    }

    public static Class<?> get(String serviceName) {
        return cache.get(serviceName);
    }

    public static void remove(String serviceName) {
        cache.remove(serviceName);
    }
}