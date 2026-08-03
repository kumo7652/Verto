package com.pulsar.core.consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pulsar.LoadBalancer;
import com.pulsar.core.protocol.RemoteRequest;
import com.pulsar.core.protocol.RemoteResponse;
import com.pulsar.core.protocol.Caller;
import com.pulsar.exception.RpcException;
import com.pulsar.exception.ServiceException;
import com.pulsar.model.LoadBalancerContext;
import com.pulsar.model.ServiceNode;
import com.pulsar.registry.Registry;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/**
 * <h3>客户端调用处理器</h3>
 * 实现 JDK {@link InvocationHandler}，将方法调用转为 RPC 远程调用。
 * 流程：服务发现 → 负载均衡 → 通过 {@link Caller} 发起协议调用。
 */
@SuppressWarnings("ClassCanBeRecord")
public class ClientInvocationHandler implements InvocationHandler {

    private static final Logger log = LoggerFactory.getLogger(ClientInvocationHandler.class);

    private final Registry registry;
    private final LoadBalancer loadBalancer;
    private final Caller invoker;
    private final String serviceVersion;
    private final int retries;

    public ClientInvocationHandler(Registry registry, LoadBalancer loadBalancer,
                                   Caller invoker,
                                   String serviceVersion, int retries) {
        this.registry = registry;
        this.loadBalancer = loadBalancer;
        this.invoker = invoker;
        this.serviceVersion = serviceVersion;
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

        RemoteResponse response = invoker.invoke(request, selected.get());
        if (response.getErrorCode() != null) {
            throw new RpcException("服务端异常[" + response.getErrorCode() + "]: " + response.getErrorMessage());
        }
        return response.getData();
    }
}
