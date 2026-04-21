package com.pulsar.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务元数据 - 元数据中心操作的实体，包含服务定义和方法信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceMetadata {
    /**
     * 服务键名 (serviceName:version)
     */
    private String serviceKey;

    /**
     * 服务名称
     */
    private String serviceName;

    /**
     * 服务描述
     */
    private String description;

    /**
     * 版本号
     */
    @Builder.Default
    private String serviceVersion = "1.0";

    /**
     * 服务分组
     */
    private String serviceGroup;

    /**
     * 服务接口类名
     */
    private String interfaceClass;

    /**
     * 方法列表
     */
    @Builder.Default
    private List<MethodMetadata> methods = new ArrayList<>();
}
