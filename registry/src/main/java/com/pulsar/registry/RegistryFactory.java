package com.pulsar.registry;

import com.pulsar.extension.ExtensionLoader;

public class RegistryFactory {
    public static Registry getRegistry(String key) {
        return ExtensionLoader.getInstance(Registry.class, key);
    }
}
