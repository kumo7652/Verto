package com.pulsar.utils;

import java.util.concurrent.atomic.AtomicLong;

public final class RequestIdGenerator {
    private static final AtomicLong COUNTER = new AtomicLong(0);

    private RequestIdGenerator() {}

    public static long nextId() {
        long timestamp = System.currentTimeMillis();
        long seq = COUNTER.getAndIncrement() & 0xFFFF;
        return (timestamp << 16) | seq;
    }
}