package com.example;

import com.example.RpcApplication;
import com.example.config.ApplicationConfig;
import com.example.registry.Registry;
import com.example.registry.RegistryFactory;
import com.example.registry.config.RegistryConfig;
import com.example.registry.local.LocalRegistry;
import com.example.registry.model.ServiceInstance;
import com.example.server.TcpServer;
import com.example.server.VertxTcpServer;

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