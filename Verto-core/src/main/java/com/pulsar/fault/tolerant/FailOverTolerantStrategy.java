package com.pulsar.fault.tolerant;

import com.pulsar.RpcApplication;
import com.pulsar.client.VertxTcpClient;
import com.pulsar.config.ApplicationConfig;
import com.pulsar.extension.SpiExtension;
import com.pulsar.fault.retry.RetryStrategy;
import com.pulsar.fault.retry.RetryStrategyFactory;
import com.pulsar.loadbalancer.LoadBalancer;
import com.pulsar.loadbalancer.LoadBalancerFactory;
import com.pulsar.model.RpcRequest;
import com.pulsar.model.RpcResponse;
import com.pulsar.model.ServiceNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpiExtension(name = "failOver")
public class FailOverTolerantStrategy implements TolerantStrategy {

    @Override
    @SuppressWarnings("unchecked")
    public RpcResponse doTolerant(Map<String, Object> context, Exception e) {
        RpcRequest request = (RpcRequest) context.get("rpcRequest");
        List<ServiceNode> serviceNodes = (List<ServiceNode>) context.get("serviceNodes");
        ServiceNode selectedService = (ServiceNode) context.get("selectedService");

        removeFailedService(selectedService, serviceNodes);

        ApplicationConfig applicationConfig = RpcApplication.getApplicationConfig();

        while (serviceNodes != null && !serviceNodes.isEmpty()) {
            LoadBalancer loadBalancer = LoadBalancerFactory.getLoadBalancer(applicationConfig.getLoadBalancer());
            Map<String, Object> requestParams = new HashMap<>();
            requestParams.put("methodName", request.getMethodName());

            ServiceNode currentService = loadBalancer.select(requestParams, serviceNodes);

            try {
                RetryStrategy retryStrategy = RetryStrategyFactory.getRetryStrategy(applicationConfig.getRetryStrategy());
                VertxTcpClient client = VertxTcpClient.getInstance();
                return retryStrategy.doRetry(() -> client.sendRequest(request, currentService));
            } catch (Exception exception) {
                removeFailedService(currentService, serviceNodes);
            }
        }

        throw new RuntimeException("暂时无可用服务");
    }

    private void removeFailedService(ServiceNode selectedService, List<ServiceNode> serviceNodes) {
        if (serviceNodes == null || serviceNodes.isEmpty()) {
            return;
        }

        serviceNodes.removeIf(service ->
            service.getServiceNodeKey().equals(selectedService.getServiceNodeKey())
        );
    }
}