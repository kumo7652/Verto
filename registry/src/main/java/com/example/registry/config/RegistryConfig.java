package com.example.registry.config;

import lombok.Data;

/**
 * 注册中心配置
 */
@Data
public class RegistryConfig {
    /**
     * 选取的注册中心
     */
    private String registry = "etcd";

    /**
     * 注册中心地址
     */
    private String registryAddress = "http://localhost:2379";

    /**
     * 用户名
     */
    private String username;

    /**
     * 密码
     */
    private String password;

    /**
     * 过期时间（ms）
     */
    private Long timeout = 10000L;
}