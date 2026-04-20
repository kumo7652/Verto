package com.pulsar.annotation;

import com.pulsar.bootstrap.RpcConsumerBootStrap;
import com.pulsar.bootstrap.RpcInitBootStrap;
import com.pulsar.bootstrap.RpcProviderBootStrap;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import({RpcConsumerBootStrap.class, RpcProviderBootStrap.class, RpcInitBootStrap.class})
public @interface EnableRpc {
    boolean needServer() default false;
}
