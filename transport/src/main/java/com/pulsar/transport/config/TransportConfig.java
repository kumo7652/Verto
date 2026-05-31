package com.pulsar.transport.config;

import com.pulsar.constant.NetworkConstant;
import com.pulsar.constant.RpcConstant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * <h3>传输层配置</h3>
 * 集中管理传输层所有可配置参数，默认值复用 {@link NetworkConstant} 和 {@link RpcConstant}
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransportConfig {

    /** 服务端监听端口 */
    @Builder.Default
    private int port = 8628;

    /** 每个 endpoint 最大连接数 */
    @Builder.Default
    private int maxConnections = NetworkConstant.DEFAULT_MAX_CONNECTIONS;

    /** 获取连接超时（ms） */
    @Builder.Default
    private long acquireTimeoutMs = NetworkConstant.DEFAULT_ACQUIRE_TIMEOUT_MS;

    /** 响应超时（ms） */
    @Builder.Default
    private long responseTimeoutMs = NetworkConstant.DEFAULT_RESPONSE_TIMEOUT_MS;

    /** 心跳发送间隔（ms） */
    @Builder.Default
    private long heartbeatIntervalMs = 30_000;

    /** 心跳超时 — 连续未收到心跳响应则标记连接不健康（ms） */
    @Builder.Default
    private long heartbeatTimeoutMs = 90_000;

    /** 空闲连接回收超时（ms） */
    @Builder.Default
    private long idleTimeoutMs = 300_000;

    /** 默认序列化器别名 */
    @Builder.Default
    private String serializerKey = RpcConstant.DEFAULT_SERIALIZER;
}
