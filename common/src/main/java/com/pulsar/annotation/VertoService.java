package com.pulsar.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <h3>Verto 服务标记</h3>
 * 标注在实现类上，标记该类为一个 Verto 服务，并可覆盖全局配置。
 * 服务名始终使用接口全限定名（FQCN）。
 *
 * <pre>{@code
 * @VertoService(version = "2.0", group = "gray", weight = 80)
 * public class HelloServiceImpl implements HelloService { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface VertoService {

    /** 服务版本，覆盖全局 {@code verto.version}。默认 "" 表示使用全局配置 */
    String version() default "";

    /** 服务分组（灰度、多机房等场景）。默认 "" 表示不分组 */
    String group() default "";

    /** 节点权重 [1, 100]。默认 100 */
    int weight() default 100;
}

