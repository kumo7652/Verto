package com.pulsar.core.client;

import com.pulsar.constant.NetworkConstant;
import com.pulsar.exception.ServiceException;
import com.pulsar.loadbalancer.LoadBalancer;
import com.pulsar.model.LoadBalancerContext;
import com.pulsar.model.RemoteRequest;
import com.pulsar.model.RemoteResponse;
import com.pulsar.model.ServiceNode;
import com.pulsar.registry.Registry;
import com.pulsar.transport.netty.client.NettyTransportClient;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * <h3>客户端调用处理器</h3>
 * 实现 JDK {@link InvocationHandler}，将方法调用转为 RPC 远程调用。
 * 流程：服务发现 → 负载均衡 → 构建请求 → 发送 → 返回响应。
 */
@Slf4j
public class ClientInvocationHandler implements InvocationHandler {

    private final Registry registry;
    private final LoadBalancer loadBalancer;
    private final NettyTransportClient transportClient;
    private final String serializerKey;
    private final String serviceVersion;

    public ClientInvocationHandler(Registry registry, LoadBalancer loadBalancer,
                                   NettyTransportClient transportClient,
                                   String serializerKey, String serviceVersion) {
        this.registry = registry;
        this.loadBalancer = loadBalancer;
        this.transportClient = transportClient;
        this.serializerKey = serializerKey;
        this.serviceVersion = serviceVersion;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String serviceName = method.getDeclaringClass().getName();
        String serviceKey = serviceName + ":" + serviceVersion;

        List<ServiceNode> nodes = registry.discover(serviceKey);
        if (nodes == null || nodes.isEmpty()) {
            throw new ServiceException("无可用服务: " + serviceKey);
        }

        LoadBalancerContext context = LoadBalancerContext.builder()
                .serviceKey(serviceKey)
                .methodName(method.getName())
                .arguments(args)
                .build();

        Optional<ServiceNode> selected = loadBalancer.select(context, nodes);
        if (selected.isEmpty()) {
            throw new ServiceException("负载均衡未选中任何节点: " + serviceKey);
        }

        RemoteRequest request = RemoteRequest.builder()
                .serviceName(serviceName)
                .methodName(method.getName())
                .parameterTypes(method.getParameterTypes())
                .parameters(args)
                .serviceVersion(serviceVersion)
                .build();

        RemoteResponse response = transportClient
                .send(request, selected.get(), serializerKey)
                .get(NetworkConstant.DEFAULT_RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        return response.getData();
    }
}
