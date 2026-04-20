package com.pulsar.bootstrap;

import com.pulsar.RpcApplication;
import com.pulsar.config.ApplicationConfig;
import com.pulsar.metadata.MetadataCenter;
import com.pulsar.metadata.MetadataCenterFactory;
import com.pulsar.metadata.model.ServiceMetadata;
import com.pulsar.registry.Registry;
import com.pulsar.registry.RegistryFactory;
import com.pulsar.registry.config.RegistryConfig;
import com.pulsar.registry.local.LocalRegistry;
import com.pulsar.registry.model.ServiceInstance;
import com.pulsar.server.VertxTcpServer;

import java.util.List;

/**
 * 服务提供者启动类
 */
public class ProviderBootStrap {
    public static void init(List<ServiceRegisterInfo> serviceRegisterInfos) {
        ApplicationConfig applicationConfig = RpcApplication.getApplicationConfig();

        RegistryConfig registryConfig = applicationConfig.getRegistryConfig();
        Registry registry = RegistryFactory.getRegistry(registryConfig.getRegistry());
        registry.init(registryConfig);

        MetadataCenter metadataCenter = MetadataCenterFactory.getMetadataCenter(
                applicationConfig.getMetadataConfig().getMetadata());
        metadataCenter.init(applicationConfig.getMetadataConfig());

        for (ServiceRegisterInfo service : serviceRegisterInfos) {
            String serviceName = service.getServiceName();

            ServiceInstance serviceInstance = ServiceInstance.builder()
                    .serviceName(serviceName)
                    .serviceHost(applicationConfig.getServerHost())
                    .servicePort(applicationConfig.getServerPort())
                    .serviceVersion(applicationConfig.getVersion())
                    .build();

            try {
                registry.register(serviceInstance);
            } catch (Exception e) {
                throw new RuntimeException("服务注册失败", e);
            }

            ServiceMetadata serviceMetadata = ServiceMetadata.builder()
                    .serviceKey(serviceInstance.getServiceKey())
                    .serviceName(serviceName)
                    .serviceVersion(applicationConfig.getVersion())
                    .serviceHost(applicationConfig.getServerHost())
                    .servicePort(applicationConfig.getServerPort())
                    .build();
            metadataCenter.storeService(serviceMetadata);

            LocalRegistry.register(serviceName, service.getImplClass());
        }

        VertxTcpServer vertxTcpServer = new VertxTcpServer();
        vertxTcpServer.doStart(applicationConfig.getServerPort());
    }
}