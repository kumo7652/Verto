package com.pulsar.spring;

import com.pulsar.config.EtcdConfig;
import com.pulsar.config.RegistryConfig;
import com.pulsar.config.TransportConfig;
import com.pulsar.config.VertoConfig;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <h3>Verto Spring Boot 配置属性</h3>
 * 将 {@code verto.*} 前缀的 YAML/properties 配置映射到 Spring Boot 类型安全绑定。
 * 提供 {@link #toVertoConfig()} 转换为框架层的 {@link VertoConfig}。
 *
 * <pre>{@code
 * verto:
 *   application-name: my-app
 *   version: "2.0"
 *   transport:
 *     port: 8629
 * }</pre>
 */
@Data
@ConfigurationProperties(prefix = "verto")
public class VertoProperties {

    /** 应用名称，默认 "verto-app" */
    private String applicationName = "verto-app";

    /** 服务版本，默认 "1.0" */
    private String version = "1.0";

    /** 服务端绑定地址，默认 "localhost" */
    private String serverHost = "localhost";

    /** 负载均衡策略，默认 "consistent-hash" */
    private String loadBalancer = "consistent-hash";

    /** 业务线程池大小，默认 16 */
    private int businessThreads = 16;

    /** 传输层配置 */
    private Transport transport = new Transport();

    /** 注册中心配置 */
    private Registry registry = new Registry();

    // ========== 嵌套配置类 ==========

    @Data
    public static class Transport {
        /** 监听端口，默认 8628 */
        private int port = 8628;
        /** 每个 endpoint 最大连接数，默认 6 */
        private int maxConnections = 6;
        /** 获取连接超时(ms)，默认 1000 */
        private long acquireTimeoutMs = 1000;
        /** 响应超时(ms)，默认 3000 */
        private long responseTimeoutMs = 3000;
        /** 心跳间隔(ms)，默认 30000 */
        private long heartbeatIntervalMs = 30000;
        /** 心跳超时(ms)，默认 90000 */
        private long heartbeatTimeoutMs = 90000;
        /** 空闲超时(ms)，默认 300000 */
        private long idleTimeoutMs = 300000;
        /** 序列化器，默认 "hessian" */
        private String serializerKey = "hessian";
    }

    @Data
    public static class Registry {
        /** 注册中心类型，默认 "etcd" */
        private String registry = "etcd";
        /** 注册中心地址，默认 "http://localhost:2379" */
        private String registryAddress = "http://localhost:2379";
        /** 用户名 */
        private String username;
        /** 密码 */
        private String password;
        /** 连接超时(ms)，默认 5000 */
        private long connectTimeout = 5000;
        /** 请求超时(ms)，默认 5000 */
        private long requestTimeout = 5000;
        /** Etcd 客户端配置 */
        private Etcd etcd = new Etcd();
    }

    @Data
    public static class Etcd {
        /** 租约 TTL(秒)，默认 30 */
        private long leaseTtlSec = 30;
        /** 重连初始退避(ms)，默认 2000 */
        private long reconnectInitialDelayMs = 2000;
        /** 重连最大退避(ms)，默认 30000 */
        private long reconnectMaxDelayMs = 30000;
        /** 重连退避乘数，默认 2.0 */
        private double reconnectMultiplier = 2.0;
        /** 最大重试次数，默认 10 */
        private int reconnectMaxAttempts = 10;
        /** 健康检查间隔(ms)，默认 5000 */
        private long healthCheckIntervalMs = 5000;
        /** 重新同步间隔(ms)，默认 60000 */
        private long resyncIntervalMs = 60000;
        /** 探测超时(ms)，默认 3000 */
        private long probeTimeoutMs = 3000;
        /** watch 分页大小，默认 500 */
        private int watchPageSize = 500;
        /** etcd 键根路径，默认 "/rpc/service/" */
        private String rootPath = "/rpc/service/";
    }

    // ========== 转换方法 ==========

    /**
     * 将 Spring Boot 配置转换为框架层的 {@link VertoConfig}。
     */
    public VertoConfig toVertoConfig() {
        VertoConfig config = new VertoConfig();
        config.setApplicationName(applicationName);
        config.setVersion(version);
        config.setServerHost(serverHost);
        config.setLoadBalancer(loadBalancer);
        config.setBusinessThreads(businessThreads);

        // 传输层
        TransportConfig tc = new TransportConfig();
        tc.setPort(transport.port);
        tc.setMaxConnections(transport.maxConnections);
        tc.setAcquireTimeoutMs(transport.acquireTimeoutMs);
        tc.setResponseTimeoutMs(transport.responseTimeoutMs);
        tc.setHeartbeatIntervalMs(transport.heartbeatIntervalMs);
        tc.setHeartbeatTimeoutMs(transport.heartbeatTimeoutMs);
        tc.setIdleTimeoutMs(transport.idleTimeoutMs);
        tc.setSerializerKey(transport.serializerKey);
        config.setTransport(tc);

        // 注册中心
        RegistryConfig rc = new RegistryConfig();
        rc.setRegistry(registry.registry);
        rc.setRegistryAddress(registry.registryAddress);
        rc.setUsername(registry.username);
        rc.setPassword(registry.password);
        rc.setConnectTimeout(registry.connectTimeout);
        rc.setRequestTimeout(registry.requestTimeout);

        EtcdConfig ec = new EtcdConfig();
        ec.setLeaseTtlSec(registry.etcd.leaseTtlSec);
        ec.setReconnectInitialDelayMs(registry.etcd.reconnectInitialDelayMs);
        ec.setReconnectMaxDelayMs(registry.etcd.reconnectMaxDelayMs);
        ec.setReconnectMultiplier(registry.etcd.reconnectMultiplier);
        ec.setReconnectMaxAttempts(registry.etcd.reconnectMaxAttempts);
        ec.setHealthCheckIntervalMs(registry.etcd.healthCheckIntervalMs);
        ec.setResyncIntervalMs(registry.etcd.resyncIntervalMs);
        ec.setProbeTimeoutMs(registry.etcd.probeTimeoutMs);
        ec.setWatchPageSize(registry.etcd.watchPageSize);
        ec.setRootPath(registry.etcd.rootPath);
        rc.setEtcd(ec);

        config.setRegistry(rc);
        return config;
    }
}
