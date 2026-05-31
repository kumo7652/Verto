package com.pulsar.proxy;

import com.pulsar.RpcApplication;
import com.pulsar.config.ApplicationConfig;
import com.pulsar.constant.NetworkConstant;
import com.pulsar.exception.ServiceException;
import com.pulsar.fault.retry.RetryStrategy;
import com.pulsar.fault.retry.RetryStrategyFactory;
import com.pulsar.fault.tolerant.TolerantStrategy;
import com.pulsar.fault.tolerant.TolerantStrategyFactory;
import com.pulsar.loadbalancer.LoadBalancer;
import com.pulsar.loadbalancer.LoadBalancerFactory;
import com.pulsar.model.LoadBalancerContext;
import com.pulsar.model.RpcRequest;
import com.pulsar.model.RpcResponse;
import com.pulsar.registry.Registry;
import com.pulsar.registry.RegistryFactory;
import com.pulsar.model.ServiceNode;
import com.pulsar.transport.netty.client.NettyTransportClient;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 服务动态代理
 */
@Slf4j
public class ServiceProxy implements InvocationHandler {

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Exception {
        ApplicationConfig applicationConfig = RpcApplication.getApplicationConfig();

        Registry registry =
                RegistryFactory.getRegistry(applicationConfig.getRegistryConfig().getRegistry());

        String serviceName = method.getDeclaringClass().getName();
        ServiceNode serviceNode = ServiceNode.builder()
                .serviceName(serviceName)
                .serviceVersion(applicationConfig.getVersion())
                .build();

        List<ServiceNode> serviceNodes = registry.discover(serviceNode.getServiceKey());
        if (serviceNodes == null || serviceNodes.isEmpty()) {
            throw new ServiceException("暂时无可用服务");
        }

        Map<String, String> attributes = new HashMap<>();
        attributes.put("address", applicationConfig.getServerHost() + ":" + applicationConfig.getServerPort());

        LoadBalancerContext context = LoadBalancerContext.builder()
                .serviceKey(serviceNode.getServiceKey())
                .methodName(method.getName())
                .arguments(args)
                .attributes(attributes)
                .build();

        LoadBalancer loadBalancer = LoadBalancerFactory.getLoadBalancer(applicationConfig.getLoadBalancer());
        Optional<ServiceNode> selectedOpt = loadBalancer.select(context, serviceNodes);
        if (selectedOpt.isEmpty()) {
            throw new ServiceException("负载均衡未选中任何服务");
        }
        ServiceNode selectedService = selectedOpt.get();

        RpcRequest rpcRequest = RpcRequest.builder()
                .serviceName(serviceName)
                .methodName(method.getName())
                .parameterTypes(method.getParameterTypes())
                .parameters(args)
                .build();

        RpcResponse response;
        try {
            RetryStrategy retryStrategy = RetryStrategyFactory.getRetryStrategy(applicationConfig.getRetryStrategy());
            NettyTransportClient client = RpcApplication.getTransportClient();
            String serializerKey = applicationConfig.getSerializer();

            response = retryStrategy.doRetry(() -> {
                try {
                    return client.send(rpcRequest, selectedService, serializerKey)
                            .get(NetworkConstant.DEFAULT_RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            log.error("调用失败，{}", e.getMessage());

            Map<String, Object> tolerateContext = new HashMap<>();
            tolerateContext.put("rpcRequest", rpcRequest);
            tolerateContext.put("selectedService", selectedService);
            tolerateContext.put("serviceNodes", serviceNodes);

            TolerantStrategy tolerantStrategy = TolerantStrategyFactory.getTolerantStrategy(applicationConfig.getTolerantStrategy());
            response = tolerantStrategy.doTolerant(tolerateContext, e);
        }

        return response.getData();
    }
}
