package com.pulsar.core.client;

import com.pulsar.LoadBalancer;
import com.pulsar.annotation.VertoReference;
import com.pulsar.config.VertoConfig;
import com.pulsar.core.VertoBootstrap;
import com.pulsar.loadbalancer.LeastActiveLoadBalancer;
import com.pulsar.loadbalancer.LoadBalancerFactory;
import com.pulsar.registry.Registry;
import com.pulsar.remoting.transport.netty.client.NettyTransportClient;
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

        this.transportClient = new NettyTransportClient(config.getTransport());

        this.loadBalancer = LoadBalancerFactory.getLoadBalancer(config.getLoadBalancer());

        if (loadBalancer instanceof LeastActiveLoadBalancer leastActive) {
            leastActive.setActiveCounter(transportClient.getActiveCounter());
            log.info("ActiveCounter 已注入 LeastActiveLoadBalancer");
        }
    }

    public <T> T createProxy(Class<T> serviceInterface) {
        return createProxy(serviceInterface, serviceInterface.getAnnotation(VertoReference.class));
    }

    public <T> T createProxy(Class<T> serviceInterface, VertoReference ref) {
        String version = (ref != null && !ref.version().isEmpty()) ? ref.version() : config.getVersion();
        String serializer = (ref != null && !ref.serializer().isEmpty()) ? ref.serializer() : config.getTransport().getSerializerKey();
        long timeout = (ref != null && ref.timeout() > 0) ? ref.timeout() : config.getTransport().getResponseTimeoutMs();
        int retries = ref != null ? ref.retries() : 0;

        LoadBalancer lb = loadBalancer;
        if (ref != null && !ref.loadBalancer().isEmpty()) {
            lb = LoadBalancerFactory.getLoadBalancer(ref.loadBalancer());
        }

        ClientInvocationHandler handler = new ClientInvocationHandler(
            registry, lb, transportClient, serializer, version, timeout, retries
        );
        return ServiceProxyFactory.create(serviceInterface, handler);
    }

    @Override
    public void close() {
        transportClient.close();
    }

    @SuppressWarnings("ClassCanBeRecord")
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
