package com.pulsar.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 服务节点 - 注册中心轻量级模型，仅含服务发现所需信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceNode {
    /**
     * 服务名称
     */
    private String serviceName;

    /**
     * 服务域名
     */
    private String serviceHost;

    /**
     * 服务端口号
     */
    private Integer servicePort;

    /**
     * 服务所属组
     */
    private String serviceGroup;

    /**
     * 接口全限定名
     */
    private String interfaceClass;

    /**
     * 服务版本号
     */
    @Builder.Default
    private String serviceVersion = "1.0";

    /**
     * 节点ID，格式如 001、002，用于唯一标识同一服务下的不同节点实例
     */
    private String nodeId;

    /**
     * 节点权重，范围 [1, 100]，默认 100。值越大分配流量越多
     */
    @Builder.Default
    private int weight = 100;

    /**
     * 节点标签（灰度、机房、环境等）
     */
    @Builder.Default
    private Map<String, String> tags = new HashMap<>();

    /**
     * 获取服务键名
     */
    public String getServiceKey() {
        return String.format("%s:%s", serviceName, serviceVersion);
    }

    /**
     * 获取服务注册节点键名
     * 格式：{serviceName:version}/{serviceName-id}
     * 例如：order-service:1.0/order-service-001
     */
    public String getServiceNodeKey() {
        return String.format("%s/%s", getServiceKey(), nodeId);
    }

    /**
     * 获取完整服务地址
     */
    public String getServiceAddress() {
        if (serviceHost != null && !serviceHost.contains("http")) {
            return String.format("http://%s:%s", serviceHost, servicePort);
        }
        return String.format("%s:%s", serviceHost, servicePort);
    }
}
