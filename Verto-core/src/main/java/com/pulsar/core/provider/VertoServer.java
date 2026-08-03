package com.pulsar.core.provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pulsar.config.VertoConfig;
import com.pulsar.core.VertoBootstrap;
import com.pulsar.model.ServiceNode;
import com.pulsar.registry.Registry;
import com.pulsar.core.protocol.verto.VertoProtocol;
import com.pulsar.remoting.transport.netty.server.NettyTransportServer;
import com.pulsar.utils.ThreadPoolBuilder;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * <h3>Verto 服务端</h3>
 * 管理服务注册、Netty 传输层启动、服务生命周期。
 *
 * <pre>{

    private static final Logger log = LoggerFactory.getLogger(VertoServer.class);@code
 * bootstrap.server()
 *     .port(8628)
 *     .addService(HelloService.class, HelloServiceImpl.class)
 *     .build()
 *     .start();
 * }</pre>
 */
public class VertoServer implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(VertoServer.class);

    private final VertoConfig config;
    private final Registry registry;
    private final List<ServiceRegistration> services;
    private NettyTransportServer transportServer;
    private VertoProtocol protocol;

    private VertoServer(Builder builder) {
        this.config = builder.bootstrap.config();
        this.registry = builder.bootstrap.registry();
        this.services = List.copyOf(builder.services);
    }

    /**
     * 注册服务到本地注册表 + 远程注册中心，然后启动 Netty 监听。
     */
    public VertoServer start() {
        protocol = new VertoProtocol(config.getTransport().getSerializerKey());
        for (ServiceRegistration reg : services) {
            String serviceName = reg.getServiceName();
            protocol.export(serviceName, reg.getImplInstance());
            registry.register(buildServiceNode(reg));
            log.info("服务已导出: {}", serviceName);
        }

        ExecutorService businessPool = ThreadPoolBuilder
            .forName("verto-business")
            .coreThreads(config.getBusinessThreads())
            .maximumThreads(config.getBusinessThreads())
            .build();

        ServerInvoker invoker = new ServerInvoker(protocol);
        transportServer = new NettyTransportServer();
        transportServer.start(config.getTransport(), invoker, businessPool);
        log.info("VertoServer 已启动, port={}, threads={}", config.getTransport().getPort(), config.getBusinessThreads());
        return this;
    }

    @Override
    public void close() {
        for (ServiceRegistration reg : services) {
            try {
                registry.unregister(buildServiceNode(reg));
            } catch (Exception e) {
                log.warn("服务注销失败: {}", reg.getServiceName(), e);
            }
        }
        if (transportServer != null) {
            transportServer.stop();
        }
        ThreadPoolBuilder.shutdown("verto-business");
    }

    private ServiceNode buildServiceNode(ServiceRegistration reg) {
        String version = reg.getVersion() != null ? reg.getVersion() : config.getVersion();
        return ServiceNode.builder()
            .serviceName(reg.getServiceName())
            .serviceHost(config.getServerHost())
            .servicePort(config.getTransport().getPort())
            .serviceVersion(version)
            .serviceGroup(reg.getGroup())
            .weight(reg.getWeight())
            .build();
    }

    public static class Builder {
        private final VertoBootstrap bootstrap;
        private final List<ServiceRegistration> services = new ArrayList<>();

        public Builder(VertoBootstrap bootstrap) {
            this.bootstrap = bootstrap;
        }

        public Builder addService(Class<?> interfaceClass, Class<?> implClass) {
            try {
                services.add(new ServiceRegistration(interfaceClass, implClass));
            } catch (Exception e) {
                throw new RuntimeException("添加服务失败");
            }
            return this;
        }

        public Builder addService(Class<?> interfaceClass, Object implInstance) {
            services.add(new ServiceRegistration(interfaceClass, implInstance));
            return this;
        }

        public Builder port(int port) {
            bootstrap.config().getTransport().setPort(port);
            return this;
        }

        public VertoServer build() {
            return new VertoServer(this);
        }
    }
}
