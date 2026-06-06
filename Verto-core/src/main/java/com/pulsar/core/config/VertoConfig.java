package com.pulsar.core.config;

import com.pulsar.registry.config.RegistryConfig;
import com.pulsar.utils.ConfigUtil;
import lombok.Data;

/**
 * <h3>Verto 集中配置</h3>
 * 合并应用配置、注册中心配置、元数据中心配置、传输层配置。
 * 支持 properties 文件加载和代码构建两种方式。
 */
@Data
public class VertoConfig {

    /** 应用名称 */
    private String applicationName = "verto-app";

    /** 服务版本 */
    private String version = "1.0";

    /** 服务端绑定地址 */
    private String serverHost = "localhost";

    /** 服务端端口 */
    private int serverPort = 8628;

    /** 序列化器（jdk / json / kryo / hessian） */
    private String serializer = "hessian";

    /** 负载均衡策略（random / round-robin / consistent-hash / least-active / ...） */
    private String loadBalancer = "consistent-hash";

    /** 注册中心配置 */
    private RegistryConfig registry = new RegistryConfig();

    /** 响应超时（ms） */
    private long responseTimeoutMs = 3000;

    /** 心跳间隔（ms） */
    private long heartbeatIntervalMs = 30000;

    /** 心跳超时（ms） */
    private long heartbeatTimeoutMs = 90000;

    /**
     * 从 application.properties 加载，使用 "verto" 前缀。
     * 示例: verto.applicationName=my-app
     */
    public static VertoConfig fromProperties() {
        return ConfigUtil.loadConfig(VertoConfig.class, "verto");
    }

    /**
     * 从指定路径的 properties 文件加载。
     */
    public static VertoConfig fromProperties(String path) {
        return ConfigUtil.loadConfig(VertoConfig.class, path);
    }
}
