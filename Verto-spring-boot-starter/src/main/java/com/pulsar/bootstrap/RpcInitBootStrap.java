package com.pulsar.bootstrap;

import com.pulsar.RpcApplication;
import com.pulsar.annotation.EnableRpc;
import com.pulsar.config.ApplicationConfig;
import com.pulsar.model.RpcRequest;
import com.pulsar.protocol.verto.PacketStatus;
import com.pulsar.protocol.verto.VertoPacket;
import com.pulsar.registry.local.LocalRegistry;
import com.pulsar.serializer.SerializerFactory;
import com.pulsar.transport.RequestHandler;
import com.pulsar.transport.config.TransportConfig;
import com.pulsar.transport.netty.server.NettyTransportServer;
import jakarta.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

import java.lang.reflect.Method;

@Slf4j
public class RpcInitBootStrap implements ImportBeanDefinitionRegistrar {
    /**
     * Spring 初始化时执行，初始化 RPC 框架
     */
    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, @Nonnull BeanDefinitionRegistry registry) {
        boolean needServer = (boolean) importingClassMetadata.getAnnotationAttributes(EnableRpc.class.getName())
                .get("needServer");

        final ApplicationConfig applicationConfig = RpcApplication.getApplicationConfig();

        if (needServer) {
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
        } else {
            log.info("不启动 server");
        }
    }
}
