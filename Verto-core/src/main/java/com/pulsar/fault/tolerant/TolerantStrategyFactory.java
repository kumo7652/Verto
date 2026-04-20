package com.pulsar.fault.tolerant;

import com.pulsar.extension.ExtensionLoader;

public class TolerantStrategyFactory {
    static {
        ExtensionLoader.load(TolerantStrategy.class);
    }

    public static TolerantStrategy getTolerantStrategy(String key) {
        return ExtensionLoader.getInstance(TolerantStrategy.class, key);
    }
}
