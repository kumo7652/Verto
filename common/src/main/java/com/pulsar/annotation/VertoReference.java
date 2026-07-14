package com.pulsar.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <h3>服务引用注解</h3>
 * 标注在服务接口字段上，覆盖全局配置。
 *
 * <pre>{@code
 * @VertoReference(version = "2.0", timeout = 5000)
 * private HelloService helloService;
 * }</pre>
 */
@Target({ElementType.TYPE, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface VertoReference {

    /** 服务版本，覆盖全局 {@code verto.version} */
    String version() default "";

    /** 调用超时（ms），覆盖全局 {@code verto.responseTimeoutMs} */
    long timeout() default -1;

    /** 序列化器（jdk / json / kryo / hessian），覆盖全局 {@code verto.serializer} */
    String serializer() default "";

    /** 负载均衡策略，覆盖全局 {@code verto.loadBalancer} */
    String loadBalancer() default "";

    /** 重试次数（仅幂等操作建议 > 0） */
    int retries() default 0;
}

