package com.pulsar.shutdown;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public final class ShutdownHookManager {
    private static final Map<String, Runnable> HOOKS = new ConcurrentHashMap<>();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private ShutdownHookManager() {}

    public static void register(String name, Runnable hook) {
        HOOKS.put(name, hook);
        ensureJvmHookRegistered();
        log.debug("注册关闭钩子: {}", name);
    }

    public static void unregister(String name) {
        HOOKS.remove(name);
        log.debug("注销关闭钩子: {}", name);
    }

    private static void ensureJvmHookRegistered() {
        if (REGISTERED.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(ShutdownHookManager::runAll));
        }
    }

    private static void runAll() {
        log.info("开始执行关闭钩子，共 {} 个", HOOKS.size());
        for (Map.Entry<String, Runnable> entry : HOOKS.entrySet()) {
            try {
                entry.getValue().run();
            } catch (Exception e) {
                log.error("关闭钩子 [{}] 执行失败", entry.getKey(), e);
            }
        }
        log.info("关闭钩子执行完毕");
    }
}