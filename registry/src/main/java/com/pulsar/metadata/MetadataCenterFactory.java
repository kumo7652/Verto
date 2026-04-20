package com.pulsar.metadata;

import com.pulsar.extension.ExtensionLoader;

public class MetadataCenterFactory {
    public static MetadataCenter getMetadataCenter(String key) {
        return ExtensionLoader.getInstance(MetadataCenter.class, key);
    }
}