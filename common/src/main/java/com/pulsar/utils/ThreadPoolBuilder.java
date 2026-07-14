package com.pulsar.utils;

import cn.hutool.core.util.StrUtil;
import lombok.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadPoolBuilder {
    private static final Logger log = LoggerFactory.getLogger(ThreadPoolBuilder.class);
    // ========== 持有全局池索引和关闭钩子注册 ==========
    private static final Map<String, ExecutorService> POOLS = new ConcurrentHashMap<>();
    private static final AtomicBoolean HOOK_REGISTERED = new AtomicBoolean(false);

    // ========== Builder字段 ==========
    private final String name;
    private int coreThreads = Runtime.getRuntime().availableProcessors();
    private int maximumThreads = Runtime.getRuntime().availableProcessors();
    private long keepAliveTime = 60L;
    private int queueSize = 1000;
    private boolean daemon = false;
    private RejectedExecutionHandler rejectPolicy = new ThreadPoolExecutor.CallerRunsPolicy();

    private ThreadPoolBuilder(String name) {
        this.name = name;
    }

    public static ThreadPoolBuilder forName(String name) {
        if (StrUtil.isBlank(name)) {
            throw new IllegalArgumentException("thread pool name is blank");
        }
        return new ThreadPoolBuilder(name);
    }

    public ThreadPoolBuilder coreThreads(int coreThreads) {
        this.coreThreads = coreThreads;
        return this;
    }

    public ThreadPoolBuilder maximumThreads(int maximumThreads) {
        this.maximumThreads = maximumThreads;
        return this;
    }

    public ThreadPoolBuilder keepAliveTime(long keepAliveTime) {
        this.keepAliveTime = keepAliveTime;
        return this;
    }

    public ThreadPoolBuilder queueSize(int queueSize) {
        this.queueSize = queueSize;
        return this;
    }

    public ThreadPoolBuilder daemon(boolean daemon) {
        this.daemon = daemon;
        return this;
    }

    public ThreadPoolBuilder rejectPolicy(RejectedExecutionHandler rejectPolicy) {
        this.rejectPolicy = rejectPolicy;
        return this;
    }

    // === build() — 普通线程池 ===
    public ExecutorService build() {
        BlockingQueue<Runnable> queue = queueSize > 0
                ? new ArrayBlockingQueue<>(queueSize)
                : new SynchronousQueue<>();

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                coreThreads,
                maximumThreads,
                keepAliveTime, TimeUnit.SECONDS,
                queue,
                new NamedThreadFactory(name, daemon),
                rejectPolicy
        );

        register(pool, false);
        return pool;
    }

    // === buildScheduled() — 调度线程池 ===
    public ScheduledExecutorService buildScheduled() {
        ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(
                coreThreads,
                new NamedThreadFactory(name, daemon),
                rejectPolicy
        );
        pool.setKeepAliveTime(keepAliveTime, TimeUnit.SECONDS);
        if (coreThreads == 0) {
            pool.allowCoreThreadTimeOut(true);
        }

        register(pool, true);
        return pool;
    }

    private void register(ExecutorService pool, boolean scheduled) {
        ExecutorService existing = POOLS.putIfAbsent(name, pool);
        if (existing != null) {
            pool.shutdownNow();
            throw new IllegalStateException("pool [" + name + "] already exists");
        }
        ensureShutdownHook();
        log.info("pool [{}] created: scheduled={}, core={}", name, scheduled, coreThreads);
    }

    private static void ensureShutdownHook() {
        if (HOOK_REGISTERED.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("shutting down all thread pools, count={}", POOLS.size());
                shutdownAll();
                log.info("all thread pools shut down");
            }));
        }
    }

    // ========== 关闭 ==========
    public static void shutdown(String name) {
        ExecutorService pool = POOLS.remove(name);
        if (pool == null) return;

        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                List<Runnable> dropped = pool.shutdownNow();
                log.warn("pool [{}] forced shutdown, dropped {} tasks", name, dropped.size());
                if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                    log.error("pool [{}] failed to terminate", name);
                }
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("pool [{}] shut down", name);
    }

    public static void shutdownAll() {
        for (String name : POOLS.keySet().toArray(String[]::new)) {
            shutdown(name);
        }
    }

    @SuppressWarnings("ClassCanBeRecord")
    private static class NamedThreadFactory implements ThreadFactory {
        private static final AtomicInteger counter = new AtomicInteger(0);
        private final String namePrefix;
        private final boolean daemon;

        NamedThreadFactory(String namePrefix, boolean daemon) {
            this.namePrefix = namePrefix;
            this.daemon = daemon;
        }

        @Override
        public Thread newThread(@NonNull Runnable r) {
            Thread t = new Thread(r, namePrefix + "-" + counter.getAndIncrement());
            t.setDaemon(daemon);
            return t;
        }
    }
}
