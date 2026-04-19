package com.example.registry;

import com.example.registry.config.RegistryConfig;
import com.example.registry.spi.RegistryExtensionLoader;

public class RegistryFactory {
    public static Registry getRegistry(String key) {
        return RegistryExtensionLoader.getInstance(Registry.class, key);
    }
}
