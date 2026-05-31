package com.pulsar.fault.tolerant;

import com.pulsar.RpcApplication;
import com.pulsar.config.ApplicationConfig;
import com.pulsar.constant.NetworkConstant;
import com.pulsar.extension.SpiExtension;
import com.pulsar.fault.retry.RetryStrategy;
import com.pulsar.fault.retry.RetryStrategyFactory;
import com.pulsar.loadbalancer.LoadBalancer;
import com.pulsar.loadbalancer.LoadBalancerFactory;
import com.pulsar.model.LoadBalancerContext;
import com.pulsar.model.RpcRequest;
import com.pulsar.model.RpcResponse;
import com.pulsar.model.ServiceNode;
import com.pulsar.transport.netty.client.NettyTransportClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

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
            LoadBalancerContext lbContext = LoadBalancerContext.builder()
                    .serviceKey(selectedService.getServiceKey())
                    .methodName(request.getMethodName())
                    .arguments(request.getParameters())
                    .attributes(new HashMap<>())
                    .build();

            LoadBalancer loadBalancer = LoadBalancerFactory.getLoadBalancer(applicationConfig.getLoadBalancer());
            Optional<ServiceNode> currentOpt = loadBalancer.select(lbContext, serviceNodes);
            if (currentOpt.isEmpty()) {
                break;
            }
            ServiceNode currentService = currentOpt.get();

            try {
                RetryStrategy retryStrategy = RetryStrategyFactory.getRetryStrategy(applicationConfig.getRetryStrategy());
                NettyTransportClient client = RpcApplication.getTransportClient();
                String serializerKey = applicationConfig.getSerializer();
                return retryStrategy.doRetry(() -> {
                    try {
                        return client.send(request, currentService, serializerKey)
                                .get(NetworkConstant.DEFAULT_RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                });
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
