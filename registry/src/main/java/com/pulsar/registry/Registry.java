package com.pulsar.registry;

import com.pulsar.exception.RegistryException;
import com.pulsar.model.ServiceNode;
import com.pulsar.registry.config.RegistryConfig;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface Registry {
    // ========== 生命周期 ==========

    /**
     * 初始化，建立连接
     */
    void init(RegistryConfig config);

    /**
     * 销毁，释放资源
     */
    void destroy();


    // ========== 服务注册 ==========

    /**
     * 注册单个服务节点
     */
    void register(ServiceNode serviceNode) throws RegistryException;

    /**
     * 批量注册服务节点
     */
    default void registerBatch(List<ServiceNode> serviceNodes) throws RegistryException {}

    /**
     * 注销服务节点
     */
    void unregister(ServiceNode serviceNode) throws RegistryException;

    // ========== 服务发现 ==========

    /**
     * 发现单个服务的所有节点
     */
    List<ServiceNode> discover(String serviceKey);

    /**
     * 批量发现多个服务
     */
    default Map<String, List<ServiceNode>> discoverBatch(List<String> serviceKeys){
        return Collections.emptyMap();
    }

    /**
     * 获取所有已注册的服务名
     */
    Set<String> getServices();

}
