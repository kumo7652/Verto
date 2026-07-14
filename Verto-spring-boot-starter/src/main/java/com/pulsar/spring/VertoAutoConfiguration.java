package com.pulsar.spring;

import com.pulsar.config.VertoConfig;
import com.pulsar.core.VertoBootstrap;
import com.pulsar.core.client.VertoClient;
import com.pulsar.spring.processor.VertoReferencePostProcessor;
import com.pulsar.spring.processor.VertoServicePostProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * <h3>Verto 自动装配</h3>
 * Spring Boot 环境下一键装配 Verto RPC：
 * <ul>
 *   <li>{@link VertoConfig} / {@link VertoBootstrap} / {@link VertoClient} 生命周期交由 Spring 管理；</li>
 *   <li>{@link VertoServicePostProcessor} 自动收集 {@code @VertoService} 服务；</li>
 *   <li>{@link VertoReferencePostProcessor} 自动为 {@code @VertoReference} 字段注入 RPC 代理；</li>
 *   <li>{@link VertoServerLifecycle} 在应用就绪后统一暴露服务。</li>
 * </ul>
 *
 * <p>通过 {@code verto.enabled=false} 可整体关闭（默认开启）。
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(VertoProperties.class)
@ConditionalOnProperty(prefix = "verto", name = "enabled", havingValue = "true", matchIfMissing = true)
public class VertoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public VertoConfig vertoConfig(VertoProperties properties) {
        return properties.toVertoConfig();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public VertoBootstrap vertoBootstrap(VertoConfig vertoConfig) {
        return VertoBootstrap.create(vertoConfig);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public VertoClient vertoClient(VertoBootstrap vertoBootstrap) {
        return vertoBootstrap.client().build();
    }

    @Bean
    public VertoServicePostProcessor vertoServicePostProcessor() {
        return new VertoServicePostProcessor();
    }

    @Bean
    public VertoReferencePostProcessor vertoReferencePostProcessor(VertoClient vertoClient) {
        return new VertoReferencePostProcessor(vertoClient);
    }

    @Bean
    public VertoServerLifecycle vertoServerLifecycle(VertoBootstrap vertoBootstrap,
                                                     VertoServicePostProcessor servicePostProcessor) {
        return new VertoServerLifecycle(vertoBootstrap, servicePostProcessor);
    }
}
