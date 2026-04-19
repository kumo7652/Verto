package com.example.fault.retry;

import com.example.extension.ExtensionLoader;

public class RetryStrategyFactory {
    static {
        ExtensionLoader.load(RetryStrategy.class);
    }

    public static RetryStrategy getRetryStrategy(String key) {
        return ExtensionLoader.getInstance(RetryStrategy.class, key);
    }
}
