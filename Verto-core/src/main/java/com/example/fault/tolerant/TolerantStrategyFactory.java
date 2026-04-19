package com.example.fault.tolerant;

import com.example.extension.ExtensionLoader;

public class TolerantStrategyFactory {
    static {
        ExtensionLoader.load(TolerantStrategy.class);
    }

    public static TolerantStrategy getTolerantStrategy(String key) {
        return ExtensionLoader.getInstance(TolerantStrategy.class, key);
    }
}
