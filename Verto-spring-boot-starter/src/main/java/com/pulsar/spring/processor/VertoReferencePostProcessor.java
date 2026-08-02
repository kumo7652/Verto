package com.pulsar.spring.processor;

import com.pulsar.annotation.VertoReference;
import com.pulsar.core.client.VertoClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.ReflectionUtils;

/**
 * <h3>Verto 服务引用注入处理器</h3>
 * 扫描所有 Bean 中标注 {@link VertoReference} 的字段，
 * 通过 {@link VertoClient#createProxy} 创建 RPC 动态代理并注入。
 *
 * <p>若 Bean 已被 AOP 代理（如 {@code @Transactional} JDK 动态代理），
 * 字段取原始类、注入到原始对象——因为 JDK 代理不承载字段。
 */
@Slf4j
public class VertoReferencePostProcessor implements BeanPostProcessor {

    private final VertoClient vertoClient;

    public VertoReferencePostProcessor(VertoClient vertoClient) {
        this.vertoClient = vertoClient;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        // 若上游 BPP 已创建 AOP 代理，需要还原原始对象来写入字段
        // 否则 JDK 动态代理不承载字段，注入会丢失
        Object targetBean = AopUtils.isAopProxy(bean) ? AopProxyUtils.getSingletonTarget(bean) : bean;
        if (targetBean == null) {
            log.debug("Bean {} 是作用域代理，无法获取目标实例，跳过 @VertoReference 注入", beanName);
            return bean;
        }

        Class<?> targetClass = AopUtils.getTargetClass(bean);

        ReflectionUtils.doWithFields(targetClass, field -> {
            VertoReference ref = field.getAnnotation(VertoReference.class);
            if (ref == null) {
                return;
            }
            Class<?> fieldType = field.getType();
            if (!fieldType.isInterface()) {
                throw new FatalBeanException("@VertoReference 只能标注在接口类型字段上: "
                        + targetClass.getName() + "#" + field.getName());
            }

            Object proxy = vertoClient.createProxy(fieldType, ref);
            ReflectionUtils.makeAccessible(field);
            ReflectionUtils.setField(field, targetBean, proxy);
            log.info("注入 Verto 引用: {}#{} -> {}",
                    targetClass.getSimpleName(), field.getName(), fieldType.getName());
        });
        return bean;
    }
}
