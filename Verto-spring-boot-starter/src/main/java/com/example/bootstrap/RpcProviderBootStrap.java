package com.example.bootstrap;

import com.example.RpcApplication;
import com.example.annotation.RpcService;
import com.example.config.ApplicationConfig;
import com.example.registry.Registry;
import com.example.registry.RegistryFactory;
import com.example.registry.config.RegistryConfig;
import com.example.registry.local.LocalRegistry;
import com.example.registry.model.ServiceInstance;
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

            ServiceInstance serviceInstance = ServiceInstance.builder()
                    .serviceName(serviceName)
                    .serviceVersion(serviceVersion)
                    .serviceHost(applicationConfig.getServerHost())
                    .servicePort(applicationConfig.getServerPort())
                    .build();

            Registry registry = RegistryFactory.getRegistry(registryConfig.getRegistry());
            try {
                registry.register(serviceInstance);
            } catch (Exception e) {
                throw new RuntimeException("注册中心注册失败" + e);
            }
        }

        return BeanPostProcessor.super.postProcessAfterInitialization(bean, beanName);
    }
}