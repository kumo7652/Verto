package com.pulsar.bootstrap;

import com.pulsar.RpcApplication;
import com.pulsar.annotation.RpcService;
import com.pulsar.config.ApplicationConfig;
import com.pulsar.registry.Registry;
import com.pulsar.registry.RegistryFactory;
import com.pulsar.registry.config.RegistryConfig;
import com.pulsar.registry.local.LocalRegistry;
import com.pulsar.model.ServiceNode;
import org.springframework.beans.factory.config.BeanPostProcessor;

import javax.annotation.Nonnull;

public class RpcProviderBootStrap implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(@Nonnull Object bean, @Nonnull String beanName) {
        Class<?> beanClass = bean.getClass();
        RpcService rpcService = beanClass.getAnnotation(RpcService.class);

        if (rpcService != null) {
            Class<?> interfaceClass = rpcService.interfaceClass();

            if (interfaceClass.equals(void.class)) {
                interfaceClass = beanClass.getInterfaces()[0];
            }

            String serviceName = interfaceClass.getName();
            String serviceVersion = rpcService.serviceVersion();

            LocalRegistry.register(serviceName, beanClass);

            ApplicationConfig applicationConfig = RpcApplication.getApplicationConfig();
            RegistryConfig registryConfig = applicationConfig.getRegistryConfig();

            ServiceNode serviceNode = ServiceNode.builder()
                    .serviceName(serviceName)
                    .serviceVersion(serviceVersion)
                    .serviceHost(applicationConfig.getServerHost())
                    .servicePort(applicationConfig.getServerPort())
                    .build();

            Registry registry = RegistryFactory.getRegistry(registryConfig.getRegistry());
            try {
                registry.register(serviceNode);
            } catch (Exception e) {
                throw new RuntimeException("注册中心注册失败" + e);
            }
        }

        return BeanPostProcessor.super.postProcessAfterInitialization(bean, beanName);
    }
}