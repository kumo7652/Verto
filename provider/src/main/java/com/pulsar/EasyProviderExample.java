package com.pulsar;

import com.pulsar.config.ApplicationConfig;
import com.pulsar.registry.Registry;
import com.pulsar.registry.RegistryFactory;
import com.pulsar.registry.config.RegistryConfig;
import com.pulsar.registry.local.LocalRegistry;
import com.pulsar.registry.model.ServiceInstance;
import com.pulsar.server.TcpServer;
import com.pulsar.server.VertxTcpServer;

public class EasyProviderExample {
    public static void main(String[] args) {
        RpcApplication.init();

        String serviceName = UserService.class.getName();
        LocalRegistry.register(serviceName, UserServiceImpl.class);

        ApplicationConfig rpcConfig = RpcApplication.getApplicationConfig();
        RegistryConfig registryConfig = rpcConfig.getRegistryConfig();

        Registry registry = RegistryFactory.getRegistry(registryConfig.getRegistry());
        ServiceInstance serviceInstance = ServiceInstance.builder()
                .serviceName(serviceName)
                .serviceHost(rpcConfig.getServerHost())
                .servicePort(rpcConfig.getServerPort())
                .build();
        try {
            registry.register(serviceInstance);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        TcpServer tcpServer = new VertxTcpServer();
        tcpServer.doStart(RpcApplication.getApplicationConfig().getServerPort());
    }
}