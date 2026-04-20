package com.pulsar.proxy;

import com.pulsar.RpcApplication;
import com.pulsar.client.VertxTcpClient;
import com.pulsar.config.ApplicationConfig;
import com.pulsar.fault.retry.RetryStrategy;
import com.pulsar.fault.retry.RetryStrategyFactory;
import com.pulsar.fault.tolerant.TolerantStrategy;
import com.pulsar.fault.tolerant.TolerantStrategyFactory;
import com.pulsar.loadbalancer.LoadBalancer;
import com.pulsar.loadbalancer.LoadBalancerFactory;
import com.pulsar.model.RpcRequest;
import com.pulsar.model.RpcResponse;
import com.pulsar.registry.Registry;
import com.pulsar.registry.RegistryFactory;
import com.pulsar.registry.model.ServiceInstance;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        ServiceInstance serviceInstance = ServiceInstance.builder()
                .serviceName(serviceName)
                .serviceVersion(applicationConfig.getVersion())
                .build();

        List<ServiceInstance> serviceInstances = registry.serviceDiscovery(serviceInstance.getServiceKey());
        if (serviceInstances == null || serviceInstances.isEmpty()) {
            throw new ServiceException("暂时无可用服务");
        }

        Map<String, Object> requestParams = new HashMap<>();
        requestParams.put("methodName", method.getName());
        requestParams.put("address", applicationConfig.getServerHost() + ":" + applicationConfig.getServerPort());

        LoadBalancer loadBalancer = LoadBalancerFactory.getLoadBalancer(applicationConfig.getLoadBalancer());
        ServiceInstance selectedService = loadBalancer.select(requestParams, serviceInstances);

        RpcRequest rpcRequest = RpcRequest.builder()
                .serviceName(serviceName)
                .methodName(method.getName())
                .parameterTypes(method.getParameterTypes())
                .parameters(args)
                .build();

        RpcResponse response;
        try {
            RetryStrategy retryStrategy = RetryStrategyFactory.getRetryStrategy(applicationConfig.getRetryStrategy());
            VertxTcpClient client = VertxTcpClient.getInstance();

            response = retryStrategy.doRetry(() ->
                 client.sendRequest(rpcRequest, selectedService)
            );
        } catch (Exception e) {
            log.error("调用失败，{}", e.getMessage());

            Map<String, Object> context = new HashMap<>();
            context.put("rpcRequest", rpcRequest);
            context.put("selectedService", selectedService);
            context.put("serviceInstances", serviceInstances);

            TolerantStrategy tolerantStrategy = TolerantStrategyFactory.getTolerantStrategy(applicationConfig.getTolerantStrategy());
            response = tolerantStrategy.doTolerant(context, e);
        }

        return response.getData();
    }
}