package com.pulsar.core.server;

import com.pulsar.annotation.VertoService;
import lombok.Getter;

/**
 * <h3>服务注册信息</h3>
 * 绑定接口类与实现类/实例，并从 {@link VertoService} 注解提取服务元数据。
 */
@Getter
public class ServiceRegistration {

    private final Class<?> interfaceClass;
    private final Object implInstance;
    private final Class<?> implClass;
    private final String version;
    private final String group;
    private final int weight;

    /**
     * @param interfaceClass 接口类
     * @param implClass      实现类（通过无参构造实例化）
     */
    public ServiceRegistration(Class<?> interfaceClass, Class<?> implClass) throws Exception {
        this.interfaceClass = interfaceClass;
        this.implClass = implClass;
        this.implInstance = implClass.getConstructor().newInstance();

        VertoService anno = implClass.getAnnotation(VertoService.class);
        this.version = resolveVersion(anno);
        this.group = anno != null ? anno.group() : "";
        this.weight = anno != null ? anno.weight() : 100;
    }

    /**
     * @param interfaceClass 接口类
     * @param implInstance   已实例化的实现对象（如 Spring Bean）
     */
    public ServiceRegistration(Class<?> interfaceClass, Object implInstance) {
        this.interfaceClass = interfaceClass;
        this.implInstance = implInstance;
        this.implClass = implInstance.getClass();

        VertoService anno = implClass.getAnnotation(VertoService.class);
        this.version = resolveVersion(anno);
        this.group = anno != null ? anno.group() : "";
        this.weight = anno != null ? anno.weight() : 100;
    }

    /** 返回 null 表示使用全局默认版本 */
    private static String resolveVersion(VertoService anno) {
        if (anno == null || anno.version().isEmpty()) {
            return null;
        }
        return anno.version();
    }

    public String getServiceName() {
        return interfaceClass.getName();
    }
}
