package com.pulsar.model;

import lombok.Builder;

import java.util.Map;


@Builder
@SuppressWarnings("ClassCanBeRecord")
public class LoadBalancerContext {
    private final String serviceKey;
    private final String methodName;
    private final Object[] arguments;
    private final Map<String, String> attributes;

    public LoadBalancerContext(String serviceKey, String methodName, Object[] arguments, Map<String, String> attributes) {
        this.serviceKey = serviceKey;
        this.methodName = methodName;
        this.arguments = arguments;
        this.attributes = attributes;
    }

    public String serviceKey() {
        return serviceKey;
    }

    public String methodName() {
        return methodName;
    }

    public Object[] arguments() {
        return arguments;
    }

    public Map<String, String> attributes() {
        return attributes;
    }
}
