package com.pulsar.bootstrap;

import com.pulsar.RpcApplication;
import com.pulsar.config.ApplicationConfig;
import com.pulsar.metadata.MetadataCenter;
import com.pulsar.metadata.MetadataCenterFactory;
import com.pulsar.model.RpcRequest;
import com.pulsar.model.RpcResponse;
import com.pulsar.model.ServiceMetadata;
import com.pulsar.model.ServiceNode;
import com.pulsar.protocol.verto.PacketStatus;
import com.pulsar.protocol.verto.VertoPacket;
import com.pulsar.registry.Registry;
import com.pulsar.registry.RegistryFactory;
import com.pulsar.registry.config.RegistryConfig;
import com.pulsar.registry.local.LocalRegistry;
import com.pulsar.serializer.SerializerFactory;
import com.pulsar.transport.RequestHandler;
import com.pulsar.transport.config.TransportConfig;
import com.pulsar.transport.netty.server.NettyTransportServer;

import java.lang.reflect.Method;
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

            ServiceNode serviceNode = ServiceNode.builder()
                    .serviceName(serviceName)
                    .serviceHost(applicationConfig.getServerHost())
                    .servicePort(applicationConfig.getServerPort())
                    .serviceVersion(applicationConfig.getVersion())
                    .build();

            try {
                registry.register(serviceNode);
            } catch (Exception e) {
                throw new RuntimeException("服务注册失败", e);
            }

            ServiceMetadata serviceMetadata = ServiceMetadata.builder()
                    .serviceKey(serviceNode.getServiceKey())
                    .serviceName(serviceName)
                    .serviceVersion(applicationConfig.getVersion())
                    .interfaceClass(service.getServiceInterface().getName())
                    .build();
            metadataCenter.storeService(serviceMetadata);

            LocalRegistry.register(serviceName, service.getImplClass());
        }

        // 创建 RequestHandler：从 LocalRegistry 查找实现 → 反射调用
        RequestHandler requestHandler = requestPacket -> {
            RpcRequest rpcRequest = requestPacket.getBody();
            long requestId = requestPacket.getHeader().getRequestId();
            String serializerKey = SerializerFactory.getInstance()
                    .getNameByCode(requestPacket.getHeader().getSerializerCode());

            try {
                Class<?> implClass = LocalRegistry.get(rpcRequest.getServiceName());
                Method method = implClass.getMethod(rpcRequest.getMethodName(), rpcRequest.getParameterTypes());
                Object result = method.invoke(implClass.getConstructor().newInstance(), rpcRequest.getParameters());
                return VertoPacket.success(requestId, result, method.getReturnType(), serializerKey);
            } catch (Exception e) {
                return VertoPacket.fail(requestId, PacketStatus.SERVER_ERROR, e.getMessage(), serializerKey);
            }
        };

        TransportConfig transportConfig = TransportConfig.builder()
                .port(applicationConfig.getServerPort())
                .serializerKey(applicationConfig.getSerializer())
                .build();

        NettyTransportServer server = new NettyTransportServer();
        server.start(transportConfig, requestHandler);
    }
}
