package com.pulsar.utils;

import cn.hutool.core.util.StrUtil;
import com.pulsar.model.Snapshot;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class ThreadPoolBuilder {
    // ========== 持有全局池索引和关闭钩子注册 ==========
    private static final Map<String, ExecutorService> POOLS = new ConcurrentHashMap<>();
    private static final AtomicBoolean HOOK_REGISTERED = new AtomicBoolean(false);

    // ========== Builder字段 ==========
    private final String name;
    private int coreThreads = Runtime.getRuntime().availableProcessors();
    private int maximumThreads = Runtime.getRuntime().availableProcessors();
    private long keepAliveTime = 60L;
    private int queueSize = 1000;
    private boolean scheduled = false;
    private int scheduledThreads = 1;
    private RejectedExecutionHandler rejectPolicy = new ThreadPoolExecutor.CallerRunsPolicy();

    public ThreadPoolBuilder(String name) {
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

    public ThreadPoolBuilder rejectPolicy(RejectedExecutionHandler rejectPolicy) {
        this.rejectPolicy = rejectPolicy;
        return this;
    }

    public ThreadPoolBuilder scheduled(int threads) {
        this.scheduled = true;
        this.scheduledThreads = threads;
        return this;
    }

    // === build() — 创建 + 注册 + 挂关闭钩子 ===
    public ExecutorService build() {
        ExecutorService pool;

        // 线程池类型是否为调度线程池
        if (scheduled) {
            pool = Executors.newScheduledThreadPool(scheduledThreads, new NamedThreadFactory(name));
        } else {
            // 使用有界的ArrayBlockingQueue
            BlockingQueue<Runnable> queue = queueSize > 0
                    ? new ArrayBlockingQueue<>(queueSize)
                    : new SynchronousQueue<>();

            // 创建线程池
            pool = new ThreadPoolExecutor(
                    coreThreads,
                    maximumThreads,
                    keepAliveTime, TimeUnit.SECONDS,
                    queue,
                    new NamedThreadFactory(name),
                    new MonitoredPolicy(name, rejectPolicy)
            );
        }

        ExecutorService existing = POOLS.putIfAbsent(name, pool);
        if (existing != null) {
            pool.shutdownNow();
            throw new IllegalStateException("pool [" + name + "] already exists");
        }

        ensureShutdownHook();
        log.info("pool [{}] created: scheduled={}, core={}, queue={}", name, scheduled, coreThreads, queueSize);
        return pool;
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

    // ========== 指标快照 ==========

    public static Snapshot snapshot(String name) {
        ExecutorService pool = POOLS.get(name);
        if (pool == null) return null;

        if (pool instanceof ScheduledExecutorService) {
            return Snapshot.scheduled(name);
        }

        ThreadPoolExecutor executor = (ThreadPoolExecutor) pool;
        RejectedExecutionHandler policy = executor.getRejectedExecutionHandler();
        long rejected = policy instanceof MonitoredPolicy mp ? mp.rejectedCount.get() : -1;
        return Snapshot.of(
                name, executor.getActiveCount(), executor.getPoolSize(),
                executor.getCorePoolSize(),
                executor.getQueue().size(), executor.getQueue().remainingCapacity(),
                executor.getCompletedTaskCount(),
                rejected
        );
    }

    public static List<Snapshot> allSnapshots() {
        return POOLS.keySet().stream()
                .map(ThreadPoolBuilder::snapshot)
                .filter(Objects::nonNull)
                .toList();
    }

    static class MonitoredPolicy implements RejectedExecutionHandler {
        private final String poolName;
        private final RejectedExecutionHandler delegate;
        final AtomicLong rejectedCount = new AtomicLong(0);

        MonitoredPolicy(String poolName, RejectedExecutionHandler delegate) {
            this.poolName = poolName;
            this.delegate = delegate;
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            rejectedCount.incrementAndGet();
            log.warn("pool [{}] rejected a task", poolName);
            if (executor != null && !executor.isShutdown()) {
                delegate.rejectedExecution(r, executor);
            }
        }
    }

    private static class NamedThreadFactory implements ThreadFactory {
        private final String namePrefix;
        private final AtomicInteger counter = new AtomicInteger(0);

        NamedThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + "-" + counter.getAndIncrement());
            t.setDaemon(false);
            return t;
        }
    }
}
