package com.pulsar.spring.processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pulsar.annotation.VertoService;
import com.pulsar.core.provider.ServiceRegistration;
import lombok.Getter;
import lombok.NonNull;
import org.springframework.aop.framework.AopProxyUtils;
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
 * {

    private static final Logger log = LoggerFactory.getLogger(VertoServicePostProcessor.class);@code VertoServerLifecycle} 在所有 Bean 初始化完成后统一触发——
 * 因为 {

    private static final Logger log = LoggerFactory.getLogger(VertoServicePostProcessor.class);@code VertoServer.start()} 是"先注册全部服务再开始监听"的模型。
 *
 * <p>注意：Bean 在此阶段可能已被其他 BPP 包装为代理（如 {

    private static final Logger log = LoggerFactory.getLogger(VertoServicePostProcessor.class);@code @Transactional}）。
 * 类信息通过 {@link AopUtils#getTargetClass} 穿透代理读取，
 * 但存入 {@link ServiceRegistration} 的实例必须通过
 * {@link AopProxyUtils#getSingletonTarget} 还原为原始对象——
 * 否则 RPC 调用会多一层不必要的 AOP 代理链。
 */
@Getter
public class VertoServicePostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(VertoServicePostProcessor.class);

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

        // 还原为原始实例：若 bean 已被 @Transactional 等 AOP 包装，直接取代理目标，
        // 避免 RPC 调用时多一层不必要的 AOP 调用链
        Object targetBean = AopUtils.isAopProxy(bean) ? AopProxyUtils.getSingletonTarget(bean) : bean;
        if (targetBean == null) {
            log.warn("@VertoService 标注的 {} 无法获取原始实例（可能是作用域代理），跳过注册", targetClass.getName());
            return bean;
        }

        for (Class<?> itf : interfaces) {
            registrations.add(new ServiceRegistration(itf, targetBean));
            log.info("发现 Verto 服务: {} -> {}", itf.getName(), targetClass.getName());
        }
        return bean;
    }
}
