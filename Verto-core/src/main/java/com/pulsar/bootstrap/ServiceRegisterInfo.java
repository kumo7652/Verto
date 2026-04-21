package com.pulsar.bootstrap;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 服务注册信息类
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServiceRegisterInfo {
    /**
     * 服务名称
     */
    private String serviceName;

    /**
     * 服务接口类
     */
    private Class<?> serviceInterface;

    /**
     * 实现类
     */
    private Class<?> implClass;
}
