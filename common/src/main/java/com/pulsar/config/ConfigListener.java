package com.pulsar.config;

/** 配置变更回调 */
@FunctionalInterface
public interface ConfigListener {
    void onChange(VertoConfig newConfig);
}
