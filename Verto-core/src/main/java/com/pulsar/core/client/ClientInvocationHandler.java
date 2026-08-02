package com.pulsar.core.client;

import com.pulsar.LoadBalancer;
import com.pulsar.core.protocol.RemoteRequest;
import com.pulsar.core.protocol.RemoteResponse;
import com.pulsar.exception.RpcException;
import com.pulsar.exception.ServiceException;
import com.pulsar.model.LoadBalancerContext;
import com.pulsar.model.ServiceNode;
import com.pulsar.registry.Registry;
import com.pulsar.remoting.transport.netty.client.NettyTransportClient;
import com.pulsar.serializer.Serializer;
import com.pulsar.serializer.SerializerFactory;
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
@SuppressWarnings("ClassCanBeRecord")
public class ClientInvocationHandler implements InvocationHandler {

    private final Registry registry;
    private final LoadBalancer loadBalancer;
    private final NettyTransportClient transportClient;
    private final String serializerKey;
    private final String serviceVersion;
    private final long timeoutMs;
    private final int retries;

    public ClientInvocationHandler(Registry registry, LoadBalancer loadBalancer,
                                   NettyTransportClient transportClient,
                                   String serializerKey, String serviceVersion,
                                   long timeoutMs, int retries) {
        this.registry = registry;
        this.loadBalancer = loadBalancer;
        this.transportClient = transportClient;
        this.serializerKey = serializerKey;
        this.serviceVersion = serviceVersion;
        this.timeoutMs = timeoutMs;
        this.retries = retries;
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

        // 延迟解码：调用层负责序列化请求、反序列化响应，传输层只认 byte[]
        byte code = SerializerFactory.getInstance().getCodeByName(serializerKey);
        Serializer serializer = SerializerFactory.getInstance().getByCode(code);
        byte[] requestBytes = serializer.serialize(request);

        byte[] responseBytes = transportClient
            .send(requestBytes, selected.get(), serializerKey)
            .get(timeoutMs, TimeUnit.MILLISECONDS);

        if (responseBytes == null || responseBytes.length == 0) {
            throw new RpcException("服务端异常（空响应）");
        }
        RemoteResponse response = serializer.deserialize(responseBytes, RemoteResponse.class);

        if (response.getErrorCode() != null) {
            throw new RpcException("服务端异常[" + response.getErrorCode() + "]: " + response.getErrorMessage());
        }

        return response.getData();
    }
}
