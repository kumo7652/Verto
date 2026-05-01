package com.pulsar.registry.etcd;

final class EtcdConstants {
    /** etcd键值根路径 */
    static final String ETCD_ROOT_PATH = "/rpc/service/";

    /** 租约默认TTL（秒） */
    static final long DEFAULT_LEASE_TTL = 30L;

    /** 探测请求超时时间（毫秒） */
    static final long PROBE_TIMEOUT_MS = 3_000L;

    /** 重连初始退避延迟（毫秒） */
    static final long RECONNECT_INITIAL_DELAY_MS = 2_000L;

    /** 重连最大退避延迟（毫秒） */
    static final long RECONNECT_MAX_DELAY_MS = 30_000L;

    /** 重连退避乘数 */
    static final double RECONNECT_MULTIPLIER = 2.0;

    /** 达到最大退避后的最大重试次数 */
    static final int RECONNECT_MAX_ATTEMPTS = 10;

    /** 健康检查间隔（毫秒） */
    static final long HEALTH_CHECK_INTERVAL_MS = 5_000L;

    /** 重新同步间隔（毫秒） */
    static final long RESYNC_INTERVAL_MS = 60_000L;

    private EtcdConstants() {}
}
