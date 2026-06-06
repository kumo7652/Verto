package com.pulsar.core.client;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

/**
 * <h3>JDK 动态代理工厂</h3>
 * 为服务接口创建客户端代理。
 */
public class ServiceProxyFactory {

    @SuppressWarnings("unchecked")
    public static <T> T create(Class<T> interfaceClass, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(
                interfaceClass.getClassLoader(),
                new Class<?>[]{interfaceClass},
                handler
        );
    }
}
