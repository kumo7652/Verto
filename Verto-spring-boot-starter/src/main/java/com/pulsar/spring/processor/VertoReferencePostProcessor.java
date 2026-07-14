package com.pulsar.spring.processor;

import com.pulsar.annotation.VertoReference;
import com.pulsar.core.client.VertoClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.util.ReflectionUtils;

/**
 * <h3>Verto 服务引用注入处理器</h3>
 * 扫描所有 Bean 中标注 {@link VertoReference} 的字段，
 * 通过 {@link VertoClient#createProxy} 创建 RPC 动态代理并注入。
 *
 * <p>使用 {@link InstantiationAwareBeanPostProcessor#postProcessProperties} 而非
 * {@code postProcessBeforeInitialization}：前者是 Spring 专为属性填充阶段
 * （{@code populateBean}）设计的注入扩展点，时机准确且与标准依赖注入协同。
 */
@Slf4j
public class VertoReferencePostProcessor implements InstantiationAwareBeanPostProcessor {

    private final VertoClient vertoClient;

    public VertoReferencePostProcessor(VertoClient vertoClient) {
        this.vertoClient = vertoClient;
    }

    @Override
    public PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName)
            throws BeansException {
        ReflectionUtils.doWithFields(bean.getClass(), field -> {
            VertoReference ref = field.getAnnotation(VertoReference.class);
            if (ref == null) {
                return;
            }
            Class<?> fieldType = field.getType();
            if (!fieldType.isInterface()) {
                throw new FatalBeanException("@VertoReference 只能标注在接口类型字段上: "
                        + bean.getClass().getName() + "#" + field.getName());
            }

            Object proxy = vertoClient.createProxy(fieldType, ref);
            ReflectionUtils.makeAccessible(field);
            ReflectionUtils.setField(field, bean, proxy);
            log.info("注入 Verto 引用: {}#{} -> {}",
                    bean.getClass().getSimpleName(), field.getName(), fieldType.getName());
        });
        return pvs;
    }
}
