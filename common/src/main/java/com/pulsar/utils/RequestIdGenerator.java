package com.pulsar.utils;

import java.util.concurrent.atomic.AtomicLong;

/**
 * <h3>请求 ID 生成器</h3>
 * 纯 AtomicLong 递增，参考 Dubbo Request.INVOKE_ID。
 * requestId 仅用于 JVM 内请求-响应匹配，不需要全局唯一或时间有序。
 */
public final class RequestIdGenerator {
    private static final AtomicLong COUNTER = new AtomicLong(0);

    private RequestIdGenerator() {}

    public static long nextId() {
        return COUNTER.getAndIncrement();
    }
}