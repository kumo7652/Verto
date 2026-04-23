package com.pulsar.annotation;

import com.pulsar.constant.RpcConstant;
import com.pulsar.fault.retry.RetryStrategyKeys;
import com.pulsar.fault.tolerant.TolerantStrategyKeys;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RpcReference {
    /**
     * 服务接口类
     */
    Class<?> interfaceClass() default void.class;

    /**
     * 版本
     */
    String serviceVersion() default RpcConstant.DEFAULT_SERVICE_VERSION;

    /**
     * 负载均衡器
     */
    String loadBalancer() default LoadBalancerKeys.CONSISTENT_HASH;

    /**
     * 重试策略
     */
    String retryStrategy() default RetryStrategyKeys.FIXEDINTERVAL;

    /**
     * 容错策略
     */
    String tolerantStrategy() default TolerantStrategyKeys.FAIL_OVER;

}