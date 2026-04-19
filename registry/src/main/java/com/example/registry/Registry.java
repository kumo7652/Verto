package com.example.registry;

import com.example.registry.config.RegistryConfig;
import com.example.registry.model.ServiceInstance;

import java.util.List;

public interface Registry {
    /**
     * 初始化 —— 使框架连接到配置信息中的注册中心
     */
    void init(RegistryConfig registryConfig);

    /**
     * 服务注册 —— 将服务实例注册到注册中心
     */
    void register(ServiceInstance serviceInstance) throws Exception;

    /**
     * 服务注销 —— 删除指定服务
     */
    void unregister(ServiceInstance serviceInstance) throws Exception;

    /**
     * 服务发现 —— 根据服务键查找服务实例列表
     */
    List<ServiceInstance> serviceDiscovery(String serviceKey);

    /**
     * 服务销毁
     */
    void destroy();

    /**
     * 服务监听 —— 根据服务在注册中心状态即时更新本地服务缓存
     */
    void watch(String serviceNodeKey);
}
