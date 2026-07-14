package com.pulsar.config;

import lombok.Data;

/** 注册中心配置 */
@Data
public class RegistryConfig {
    /** 注册中心类型 */
    private String registry = "etcd";
    /** 注册中心地址 */
    private String registryAddress = "http://localhost:2379";
    /** 用户名 */
    private String username;
    /** 密码 */
    private String password;
    /** 连接超时（ms） */
    private long connectTimeout = 5000;
    /** 请求超时（ms） */
    private long requestTimeout = 5000;
    /** Etcd 客户端参数 */
    private EtcdConfig etcd = new EtcdConfig();
}
