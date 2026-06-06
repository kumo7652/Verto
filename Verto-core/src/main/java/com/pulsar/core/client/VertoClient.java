package com.pulsar.core.client;

import com.pulsar.core.VertoBootstrap;
import com.pulsar.core.config.VertoConfig;
import com.pulsar.loadbalancer.LeastActiveLoadBalancer;
import com.pulsar.loadbalancer.LoadBalancer;
import com.pulsar.loadbalancer.LoadBalancerFactory;
import com.pulsar.registry.Registry;
import com.pulsar.transport.config.TransportConfig;
import com.pulsar.transport.netty.client.NettyTransportClient;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;

/**
 * <h3>Verto 客户端</h3>
 * 管理 Netty 传输客户端和负载均衡器的生命周期，
 * 提供 {@link #createProxy} 创建服务代理。
 *
 * <pre>{@code
 * VertoClient client = bootstrap.client().build();
 * HelloService hello = client.createProxy(HelloService.class);
 * String result = hello.sayHello("world");
 * client.close();
 * }</pre>
 */
@Slf4j
public class VertoClient implements Closeable {

    private final VertoConfig config;
    private final Registry registry;
    private final NettyTransportClient transportClient;
    private final LoadBalancer loadBalancer;

    private VertoClient(Builder builder) {
        this.config = builder.bootstrap.config();
        this.registry = builder.bootstrap.registry();

        TransportConfig transportConfig = TransportConfig.builder()
                .serializerKey(config.getSerializer())
                .responseTimeoutMs(config.getResponseTimeoutMs())
                .build();
        this.transportClient = new NettyTransportClient(transportConfig);

        this.loadBalancer = LoadBalancerFactory.getLoadBalancer(config.getLoadBalancer());

        if (loadBalancer instanceof LeastActiveLoadBalancer leastActive) {
            leastActive.setActiveCountProvider(transportClient.getActiveCountProvider());
            log.info("ActiveCountProvider 已注入 LeastActiveLoadBalancer");
        }
    }

    public <T> T createProxy(Class<T> serviceInterface) {
        ClientInvocationHandler handler = new ClientInvocationHandler(
                registry, loadBalancer, transportClient,
                config.getSerializer(), config.getVersion()
        );
        return ServiceProxyFactory.create(serviceInterface, handler);
    }

    @Override
    public void close() {
        transportClient.close();
    }

    public static class Builder {
        private final VertoBootstrap bootstrap;

        public Builder(VertoBootstrap bootstrap) {
            this.bootstrap = bootstrap;
        }

        public VertoClient build() {
            return new VertoClient(this);
        }
    }
}
