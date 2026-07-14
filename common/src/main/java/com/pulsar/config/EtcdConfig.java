package com.pulsar.config;

import lombok.Data;

/** Etcd 客户端可配置参数 */
@Data
public class EtcdConfig {
    /** 租约 TTL（秒） */
    private long leaseTtlSec = 30;
    /** 重连初始退避（ms） */
    private long reconnectInitialDelayMs = 2000;
    /** 重连最大退避（ms） */
    private long reconnectMaxDelayMs = 30000;
    /** 重连退避乘数 */
    private double reconnectMultiplier = 2.0;
    /** 最大重试次数 */
    private int reconnectMaxAttempts = 10;
    /** 健康检查间隔（ms） */
    private long healthCheckIntervalMs = 5000;
    /** 重新同步间隔（ms） */
    private long resyncIntervalMs = 60000;
    /** 探测超时（ms） */
    private long probeTimeoutMs = 3000;
    /** watch 分页大小 */
    private int watchPageSize = 500;
    /** etcd 键根路径 */
    private String rootPath = "/rpc/service/";
}
