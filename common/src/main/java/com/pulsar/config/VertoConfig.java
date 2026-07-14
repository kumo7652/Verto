package com.pulsar.config;

import com.pulsar.utils.ConfigUtil;
import lombok.Data;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <h3>Verto 集中配置</h3>
 * 聚合传输层、注册中心等子配置。支持 properties/yml 文件加载和代码构建。
 */
@Data
public class VertoConfig {
    private final List<ConfigListener> listeners = new CopyOnWriteArrayList<>();
    /**
     * 应用名称
     */
    private String applicationName = "verto-app";
    /**
     * 服务版本
     */
    private String version = "1.0";
    /**
     * 服务端绑定地址
     */
    private String serverHost = "localhost";
    /**
     * 负载均衡策略
     */
    private String loadBalancer = "consistent-hash";
    /**
     * 业务线程池大小
     */
    private int businessThreads = 16;
    /**
     * 传输层配置
     */
    private TransportConfig transport = new TransportConfig();

    // ========== 配置变更监听 ==========
    /**
     * 注册中心配置
     */
    private RegistryConfig registry = new RegistryConfig();

    public static VertoConfig fromProperties() {
        return ConfigUtil.loadConfig(VertoConfig.class, "verto");
    }

    public static VertoConfig fromProperties(String profile) {
        return ConfigUtil.loadConfig(VertoConfig.class, "verto", profile);
    }

    public void addListener(ConfigListener listener) {
        listeners.add(listener);
    }

    // ========== 便捷方法 ==========

    public void removeListener(ConfigListener listener) {
        listeners.remove(listener);
    }

    public synchronized void reload(VertoConfig other) {
        this.applicationName = other.applicationName;
        this.version = other.version;
        this.serverHost = other.serverHost;
        this.loadBalancer = other.loadBalancer;
        this.businessThreads = other.businessThreads;
        this.transport = other.transport;
        this.registry = other.registry;
        listeners.forEach(l -> l.onChange(this));
    }
}
