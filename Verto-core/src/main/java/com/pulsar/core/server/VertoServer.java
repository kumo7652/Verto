package com.pulsar.core.server;

import com.pulsar.core.VertoBootstrap;
import com.pulsar.core.config.VertoConfig;
import com.pulsar.model.ServiceNode;
import com.pulsar.registry.Registry;
import com.pulsar.registry.local.LocalRegistry;
import com.pulsar.transport.config.TransportConfig;
import com.pulsar.transport.netty.server.NettyTransportServer;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <h3>Verto 服务端</h3>
 * 管理服务注册、Netty 传输层启动、服务生命周期。
 *
 * <pre>{@code
 * bootstrap.server()
 *     .port(8628)
 *     .addService(HelloService.class, HelloServiceImpl.class)
 *     .build()
 *     .start();
 * }</pre>
 */
@Slf4j
public class VertoServer implements Closeable {

    private final VertoConfig config;
    private final Registry registry;
    private final List<ServiceRegistration> services;
    private ExecutorService businessPool;
    private NettyTransportServer transportServer;

    private VertoServer(Builder builder) {
        this.config = builder.bootstrap.config();
        this.registry = builder.bootstrap.registry();
        this.services = List.copyOf(builder.services);
    }

    /**
     * 注册服务到本地注册表 + 远程注册中心，然后启动 Netty 监听。
     */
    public VertoServer start() {
        for (ServiceRegistration reg : services) {
            String serviceName = reg.getServiceName();
            ServiceNode node = buildServiceNode(serviceName);

            LocalRegistry.register(serviceName, reg.getImplClass());
            registry.register(node);
            log.info("服务已注册: {}", serviceName);
        }

        businessPool = Executors.newFixedThreadPool(config.getBusinessThreads());
        ServerInvoker invoker = new ServerInvoker(config.getSerializer());
        ThreadPoolRequestHandler handler = new ThreadPoolRequestHandler(invoker, businessPool);
        transportServer = new NettyTransportServer();
        transportServer.start(buildTransportConfig(), handler);
        log.info("VertoServer 已启动, port={}", config.getServerPort());
        return this;
    }

    @Override
    public void close() {
        for (ServiceRegistration reg : services) {
            try {
                registry.unregister(buildServiceNode(reg.getServiceName()));
            } catch (Exception e) {
                log.warn("反注册失败: {}", reg.getServiceName(), e);
            }
        }
        if (transportServer != null) {
            transportServer.stop();
        }
        if (businessPool != null) {
            businessPool.shutdown();
            try {
                if (!businessPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    businessPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                businessPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private ServiceNode buildServiceNode(String serviceName) {
        return ServiceNode.builder()
                .serviceName(serviceName)
                .serviceHost(config.getServerHost())
                .servicePort(config.getServerPort())
                .serviceVersion(config.getVersion())
                .build();
    }

    private TransportConfig buildTransportConfig() {
        return TransportConfig.builder()
                .port(config.getServerPort())
                .serializerKey(config.getSerializer())
                .heartbeatIntervalMs(config.getHeartbeatIntervalMs())
                .heartbeatTimeoutMs(config.getHeartbeatTimeoutMs())
                .build();
    }

    public static class Builder {
        private final VertoBootstrap bootstrap;
        private final List<ServiceRegistration> services = new ArrayList<>();

        public Builder(VertoBootstrap bootstrap) {
            this.bootstrap = bootstrap;
        }

        public Builder addService(Class<?> interfaceClass, Class<?> implClass) {
            services.add(new ServiceRegistration(interfaceClass, implClass));
            return this;
        }

        public Builder addService(Class<?> interfaceClass, Object implInstance) {
            services.add(new ServiceRegistration(interfaceClass, implInstance));
            return this;
        }

        public Builder port(int port) {
            bootstrap.config().setServerPort(port);
            return this;
        }

        public VertoServer build() {
            return new VertoServer(this);
        }
    }
}
