package com.example.metadata.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 服务元数据
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
    private String serviceVersion = "1.0";

    /**
     * 服务分组
     */
    private String serviceGroup;

    /**
     * 服务提供者主机
     */
    private String serviceHost;

    /**
     * 服务提供者端口
     */
    private Integer servicePort;
}