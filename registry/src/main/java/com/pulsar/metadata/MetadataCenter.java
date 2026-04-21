package com.pulsar.metadata;

import com.pulsar.metadata.config.MetadataConfig;
import com.pulsar.model.MethodMetadata;
import com.pulsar.model.ServiceMetadata;

import java.util.List;

/**
 * 元数据中心接口
 */
public interface MetadataCenter {
    /**
     * 初始化
     */
    void init(MetadataConfig config);

    /**
     * 存储服务元数据
     */
    void storeService(ServiceMetadata metadata);

    /**
     * 删除服务元数据
     */
    void removeService(String serviceKey);

    /**
     * 获取服务元数据
     */
    ServiceMetadata getService(String serviceKey);

    /**
     * 存储方法元数据
     */
    void storeMethod(String serviceKey, MethodMetadata metadata);

    /**
     * 删除方法元数据
     */
    void removeMethod(String serviceKey, String methodKey);

    /**
     * 获取服务的所有方法元数据
     */
    List<MethodMetadata> getMethods(String serviceKey);

    /**
     * 销毁
     */
    void destroy();
}
