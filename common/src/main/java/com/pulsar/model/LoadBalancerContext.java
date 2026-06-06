package com.pulsar.model;

import lombok.Builder;

import java.util.Map;

/**
 * @param serviceKey 目标服务标识
 * @param methodName 被调用的方法名
 * @param arguments  方法实参（一致性哈希从这里提取键，而非由调用方预先计算）
 * @param attributes 扩展属性（标签路由、灰度等）
 */
@Builder
@SuppressWarnings("all")
public class LoadBalancerContext {
    private final String serviceKey;
    private final String methodName;
    private final Object[] arguments;
    private final Map<String, String> attributes;

    public LoadBalancerContext(String serviceKey, String methodName,
                               Object[] arguments, Map<String, String> attributes) {
        this.serviceKey = serviceKey;
        this.methodName = methodName;
        this.arguments = arguments;
        this.attributes = attributes;
    }

    public String serviceKey() { return serviceKey; }
    public String methodName() { return methodName; }
    public Object[] arguments() { return arguments; }
    public Map<String, String> attributes() { return attributes; }
}
