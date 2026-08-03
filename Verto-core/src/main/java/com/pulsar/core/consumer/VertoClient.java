package com.pulsar.core.consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pulsar.LoadBalancer;
import com.pulsar.annotation.VertoReference;
import com.pulsar.config.VertoConfig;
import com.pulsar.core.VertoBootstrap;
import com.pulsar.core.protocol.Caller;
import com.pulsar.core.protocol.verto.VertoProtocol;
import com.pulsar.loadbalancer.LeastActiveLoadBalancer;
import com.pulsar.loadbalancer.LoadBalancerFactory;
import com.pulsar.registry.Registry;
import com.pulsar.remoting.exchange.ExchangeClient;
import com.pulsar.remoting.transport.netty.client.NettyTransportClient;
import com.pulsar.remoting.transport.netty.client.VertoClientChannelConfigurer;

import java.io.Closeable;

/**
 * <h3>Verto 客户端</h3>
 * 管理 Netty 传输客户端和负载均衡器的生命周期，
 * 提供 {@link #createProxy} 创建服务代理。
 *
 * <pre>{

    private static final Logger log = LoggerFactory.getLogger(VertoClient.class);@code
 * VertoClient client = bootstrap.client().build();
 * HelloService hello = client.createProxy(HelloService.class);
 * String result = hello.sayHello("world");
 * client.close();
 * }</pre>
 */
public class VertoClient implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(VertoClient.class);

    private final VertoConfig config;
    private final Registry registry;
    private final NettyTransportClient transportClient;
    private final ExchangeClient exchangeClient;
    private final LoadBalancer loadBalancer;
    private final VertoProtocol protocol;

    private VertoClient(Builder builder) {
        this.config = builder.bootstrap.config();
        this.registry = builder.bootstrap.registry();

        this.transportClient = new NettyTransportClient(config.getTransport(), new VertoClientChannelConfigurer(config.getTransport()));
        this.exchangeClient = new ExchangeClient(transportClient);
        this.protocol = new VertoProtocol(config.getTransport().getSerializerKey());

        this.loadBalancer = LoadBalancerFactory.getLoadBalancer(config.getLoadBalancer());

        if (loadBalancer instanceof LeastActiveLoadBalancer leastActive) {
            leastActive.setActiveCounter(exchangeClient.getActiveCounter());
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

        Caller invoker = protocol.refer(exchangeClient, serializer, timeout);
        ClientInvocationHandler handler = new ClientInvocationHandler(
            registry, lb, invoker, version, retries
        );
        return ServiceProxyFactory.create(serviceInterface, handler);
    }

    @Override
    public void close() {
        exchangeClient.close();
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
