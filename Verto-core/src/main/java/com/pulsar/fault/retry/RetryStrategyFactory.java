package com.pulsar.fault.retry;

import com.pulsar.extension.ExtensionLoader;

public class RetryStrategyFactory {
    static {
        ExtensionLoader.load(RetryStrategy.class);
    }

    public static RetryStrategy getRetryStrategy(String key) {
        return ExtensionLoader.getInstance(RetryStrategy.class, key);
    }
}
