package com.pulsar.config;

import lombok.Data;

/**
 * 传输层配置
 */
@Data
public class TransportConfig {
    /**
     * 监听端口
     */
    private int port = 8628;

    /**
     * 每个 endpoint 最大连接数
     */
    private int maxConnections = 6;

    /**
     * 获取连接超时（ms）
     */
    private long acquireTimeoutMs = 1000;

    /**
     * 响应超时（ms）
     */
    private long responseTimeoutMs = 3000;

    /**
     * 心跳间隔（ms）
     */
    private long heartbeatIntervalMs = 30000;

    /**
     * 心跳超时（ms）
     */
    private long heartbeatTimeoutMs = 90000;

    /**
     * 空闲超时（ms）
     */
    private long idleTimeoutMs = 300000;

    /**
     * 序列化器
     */
    private String serializerKey = "hessian";
}
