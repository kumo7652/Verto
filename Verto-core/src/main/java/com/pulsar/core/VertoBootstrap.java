package com.pulsar.core;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pulsar.config.VertoConfig;
import com.pulsar.core.consumer.VertoClient;
import com.pulsar.core.provider.VertoServer;
import com.pulsar.registry.Registry;
import com.pulsar.registry.RegistryFactory;

import java.io.Closeable;

/**
 * <h3>Verto 统一入口</h3>
 * 管理共享组件（Registry）生命周期，
 * 提供 {@link VertoServer} 和 {@link VertoClient} 的构建入口。
 *
 * <pre>{

    private static final Logger log = LoggerFactory.getLogger(VertoBootstrap.class);@code
 * VertoConfig config = VertoConfig.fromProperties();
 * VertoBootstrap bootstrap = VertoBootstrap.create(config);
 *
 * // Provider
 * bootstrap.server().addService(...).build().start();
 *
 * // Consumer
 * VertoClient client = bootstrap.client().build();
 * HelloService service = client.createProxy(HelloService.class);
 * }</pre>
 */
public class VertoBootstrap implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(VertoBootstrap.class);

    private final VertoConfig config;
    private final Registry registry;

    private VertoBootstrap(VertoConfig config) {
        this.config = config;
        this.registry = RegistryFactory.getRegistry(config.getRegistry().getRegistry());
    }

    /**
     * 创建并初始化 Bootstrap，连接注册中心。
     */
    public static VertoBootstrap create(VertoConfig config) {
        VertoBootstrap bootstrap = new VertoBootstrap(config);
        bootstrap.registry.init(config.getRegistry());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Verto 关闭中...");
            bootstrap.close();
        }));
        log.info("Verto 初始化完成, app={}", config.getApplicationName());
        return bootstrap;
    }

    public VertoServer.Builder server() {
        return new VertoServer.Builder(this);
    }

    public VertoClient.Builder client() {
        return new VertoClient.Builder(this);
    }

    public VertoConfig config() {
        return config;
    }

    public Registry registry() {
        return registry;
    }

    @Override
    public void close() {
        log.info("销毁注册中心...");
        registry.destroy();
    }
}
