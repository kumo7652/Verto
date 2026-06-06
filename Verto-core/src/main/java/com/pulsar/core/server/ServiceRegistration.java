package com.pulsar.core.server;

/**
 * <h3>服务注册信息</h3>
 * 绑定接口类与实现类/实例。
 */
public class ServiceRegistration {

    private final Class<?> interfaceClass;
    private final Object implInstance;
    private final Class<?> implClass;

    /**
     * @param interfaceClass 接口类
     * @param implClass      实现类（通过无参构造实例化）
     */
    public ServiceRegistration(Class<?> interfaceClass, Class<?> implClass) {
        this.interfaceClass = interfaceClass;
        this.implClass = implClass;
        this.implInstance = null;
    }

    /**
     * @param interfaceClass 接口类
     * @param implInstance   已实例化的实现对象（如 Spring Bean）
     */
    public ServiceRegistration(Class<?> interfaceClass, Object implInstance) {
        this.interfaceClass = interfaceClass;
        this.implInstance = implInstance;
        this.implClass = implInstance.getClass();
    }

    public Class<?> getInterfaceClass() {
        return interfaceClass;
    }

    public String getServiceName() {
        return interfaceClass.getName();
    }

    public Object getImplInstance() {
        return implInstance;
    }

    public Class<?> getImplClass() {
        return implClass;
    }
}
