package com.pulsar.spring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pulsar.core.VertoBootstrap;
import com.pulsar.core.provider.ServiceRegistration;
import com.pulsar.core.provider.VertoServer;
import com.pulsar.spring.processor.VertoServicePostProcessor;
import org.springframework.context.SmartLifecycle;

import java.util.List;

/**
 * <h3>Verto 服务端生命周期管理</h3>
 * 以 {@link SmartLifecycle} 接管 {@link VertoServer} 的启停，确保：
 * <ul>
 *   <li>在<b>所有 Bean 初始化完成后</b>才注册服务并启动 Netty 监听
 *       （此时 {@link VertoServicePostProcessor} 已收集齐全部 {@code @VertoService}）；</li>
 *   <li>应用关闭时优雅注销服务、停止传输层。</li>
 * </ul>
 *
 * <p>纯消费者应用（无任何 {

    private static final Logger log = LoggerFactory.getLogger(VertoServerLifecycle.class);@code @VertoService}）不会启动服务端，避免空占端口。
 */
public class VertoServerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(VertoServerLifecycle.class);

    /** 尽量晚启动、尽量早停止，保证 RPC 服务在应用其余组件就绪后才对外暴露 */
    private static final int PHASE = Integer.MAX_VALUE - 1;

    private final VertoBootstrap bootstrap;
    private final VertoServicePostProcessor servicePostProcessor;

    private volatile VertoServer server;
    private volatile boolean running = false;

    public VertoServerLifecycle(VertoBootstrap bootstrap,
                                VertoServicePostProcessor servicePostProcessor) {
        this.bootstrap = bootstrap;
        this.servicePostProcessor = servicePostProcessor;
    }

    @Override
    public void start() {
        List<ServiceRegistration> registrations = servicePostProcessor.getRegistrations();
        if (registrations.isEmpty()) {
            log.info("未发现 @VertoService，跳过 VertoServer 启动（消费者模式）");
            running = true;
            return;
        }

        VertoServer.Builder builder = bootstrap.server();
        for (ServiceRegistration reg : registrations) {
            builder.addService(reg.getInterfaceClass(), reg.getImplInstance());
        }
        this.server = builder.build().start();
        running = true;
        log.info("VertoServer 已通过 Spring 生命周期启动，共 {} 个服务", registrations.size());
    }

    @Override
    public void stop() {
        if (server != null) {
            server.close();
            log.info("VertoServer 已停止");
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
