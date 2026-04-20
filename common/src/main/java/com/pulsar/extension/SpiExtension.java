package com.pulsar.extension;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * SPI 扩展注解 - 声明扩展点的元数据
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SpiExtension {
    /**
     * 扩展名称，用于 SPI key
     */
    String name();

    /**
     * 扩展编码，用于协议头等场景
     */
    int code() default 0;

    /**
     * 是否覆盖同名扩展（用户扩展覆盖内置扩展时设为 true）
     */
    boolean override() default false;
}