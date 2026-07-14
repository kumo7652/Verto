package com.pulsar.registry.local;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地注册表，key 为服务名，value 为服务实例。
 */
public class LocalRegistry {
    private static final Map<String, Object> cache = new ConcurrentHashMap<>();

    public static void register(String serviceName, Object instance) {
        cache.put(serviceName, instance);
    }

    public static Object get(String serviceName) {
        return cache.get(serviceName);
    }

    public static void remove(String serviceName) {
        cache.remove(serviceName);
    }
}
