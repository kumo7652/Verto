package com.pulsar.metadata.config;

import lombok.Data;

/**
 * 元数据中心配置
 */
@Data
public class MetadataConfig {
    /**
     * 元数据中心类型
     */
    private String metadata = "redis";

    /**
     * 元数据中心地址
     */
    private String address = "redis://localhost:6379";

    /**
     * 密码
     */
    private String password;

    /**
     * 数据库
     */
    private int database = 0;

    /**
     * 超时时间（ms）
     */
    private long timeout = 10000L;
}