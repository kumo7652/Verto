package com.pulsar.constant;

public final class RpcConstant {
    public static final String DEFAULT_CONFIG_PREFIX = "rpc";
    public static final String DEFAULT_SERVICE_VERSION = "1.0";
    public static final String DEFAULT_SERIALIZER = "hessian";

    /**
     * SPI 目录（imports 风格，ClassLoader 自动聚合所有模块）
     */
    public static final String RPC_SPI_DIR = "META-INF/rpc/";
}
