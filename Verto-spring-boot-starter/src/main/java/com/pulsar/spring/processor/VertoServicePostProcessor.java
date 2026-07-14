package com.pulsar.spring.processor;

import com.pulsar.annotation.VertoService;
import com.pulsar.core.server.ServiceRegistration;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <h3>Verto 服务自动注册处理器</h3>
 * 扫描所有标注 {@link VertoService} 的 Spring Bean，将其绑定到实现的接口，
 * 收集为 {@link ServiceRegistration} 列表。
 *
 * <p>本处理器只负责<b>收集</b>，真正的注册与 Netty 监听由
 * {@code VertoServerLifecycle} 在所有 Bean 初始化完成后统一触发——
 * 因为 {@code VertoServer.start()} 是"先注册全部服务再开始监听"的模型。
 */
@Slf4j
@Getter
public class VertoServicePostProcessor implements BeanPostProcessor {

    private final List<ServiceRegistration> registrations = new CopyOnWriteArrayList<>();

    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        // 解包 AOP 代理，取真实类判断注解（@VertoService 无 @Inherited，代理子类读不到）
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        if (!targetClass.isAnnotationPresent(VertoService.class)) {
            return bean;
        }

        Class<?>[] interfaces = targetClass.getInterfaces();
        if (interfaces.length == 0) {
            log.warn("@VertoService 标注的 {} 未实现任何接口，跳过注册", targetClass.getName());
            return bean;
        }

        for (Class<?> itf : interfaces) {
            registrations.add(new ServiceRegistration(itf, bean));
            log.info("发现 Verto 服务: {} -> {}", itf.getName(), targetClass.getName());
        }
        return bean;
    }
}
