package com.example.metadata;

import com.example.metadata.spi.MetadataExtensionLoader;

public class MetadataCenterFactory {
    public static MetadataCenter getMetadataCenter(String key) {
        return MetadataExtensionLoader.getInstance(MetadataCenter.class, key);
    }
}