package com.example.config;

import com.example.loadbalancer.LoadBalancerKeys;
import com.example.metadata.config.MetadataConfig;
import com.example.registry.config.RegistryConfig;
import com.example.serializer.SerializerKeys;
import lombok.Data;

/**
 * 框架项目配置
 */
@Data
public class ApplicationConfig {
    private String name = "my-rpc";
    private String version = "1.0";
    private String serverHost = "localhost";
    private Integer serverPort = 8081;
    private String serializer = SerializerKeys.JDK;
    private RegistryConfig registryConfig = new RegistryConfig();
    private MetadataConfig metadataConfig = new MetadataConfig();
    private String loadBalancer = LoadBalancerKeys.CONSISTENT_HASH;
    private String retryStrategy = "fixedInterval";
    private String tolerantStrategy = "failOver";
}