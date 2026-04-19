package com.example.fault.tolerant;

import com.example.RpcApplication;
import com.example.client.VertxTcpClient;
import com.example.config.ApplicationConfig;
import com.example.fault.retry.RetryStrategy;
import com.example.fault.retry.RetryStrategyFactory;
import com.example.loadbalancer.LoadBalancer;
import com.example.loadbalancer.LoadBalancerFactory;
import com.example.model.RpcRequest;
import com.example.model.RpcResponse;
import com.example.registry.model.ServiceInstance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FailOverTolerantStrategy implements TolerantStrategy {

    @Override
    @SuppressWarnings("unchecked")
    public RpcResponse doTolerant(Map<String, Object> context, Exception e) {
        RpcRequest request = (RpcRequest) context.get("rpcRequest");
        List<ServiceInstance> serviceInstances = (List<ServiceInstance>) context.get("serviceInstances");
        ServiceInstance selectedService = (ServiceInstance) context.get("selectedService");

        removeFailedService(selectedService, serviceInstances);

        ApplicationConfig applicationConfig = RpcApplication.getApplicationConfig();

        while (serviceInstances != null && !serviceInstances.isEmpty()) {
            LoadBalancer loadBalancer = LoadBalancerFactory.getLoadBalancer(applicationConfig.getLoadBalancer());
            Map<String, Object> requestParams = new HashMap<>();
            requestParams.put("methodName", request.getMethodName());

            ServiceInstance currentService = loadBalancer.select(requestParams, serviceInstances);

            try {
                RetryStrategy retryStrategy = RetryStrategyFactory.getRetryStrategy(applicationConfig.getRetryStrategy());
                VertxTcpClient client = VertxTcpClient.getInstance();
                return retryStrategy.doRetry(() -> client.sendRequest(request, currentService));
            } catch (Exception exception) {
                removeFailedService(currentService, serviceInstances);
            }
        }

        throw new RuntimeException("暂时无可用服务");
    }

    private void removeFailedService(ServiceInstance selectedService, List<ServiceInstance> serviceInstances) {
        if (serviceInstances == null || serviceInstances.isEmpty()) {
            return;
        }

        serviceInstances.removeIf(service ->
            service.getServiceNodeKey().equals(selectedService.getServiceNodeKey())
        );
    }
}