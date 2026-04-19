package com.example.constant;

public interface RpcConstant {
    String DEFAULT_CONFIG_PREFIX = "rpc";
    String DEFAULT_SERVICE_VERSION = "1.0";
    String DEFAULT_SERIALIZER = "hessian";

    /**
     * 系统spi目录
     */
    String RPC_SYSTEM_SPI_DIR = "META-INF/rpc/system/";

    /**
     * 用户spi目录
     */
    String RPC_CUSTOM_SPI_DIR = "META-INF/rpc/custom/";
}
