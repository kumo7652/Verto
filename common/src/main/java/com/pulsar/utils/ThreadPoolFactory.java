package com.pulsar.utils;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public final class ThreadPoolFactory {
    private static final Map<String, ExecutorService> POOLS = new ConcurrentHashMap<>();

    private ThreadPoolFactory() {}

    public static ScheduledExecutorService newScheduledPool(String name, int threads) {
        ScheduledExecutorService pool = Executors.newScheduledThreadPool(threads,
                new NamedThreadFactory(name));
        POOLS.put(name, pool);
        return pool;
    }

    public static ExecutorService newFixedPool(String name, int threads) {
        ExecutorService pool = Executors.newFixedThreadPool(threads,
                new NamedThreadFactory(name));
        POOLS.put(name, pool);
        return pool;
    }

    public static ExecutorService getPool(String name) {
        return POOLS.get(name);
    }

    public static void shutdown(String name) {
        ExecutorService pool = POOLS.remove(name);
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("线程池 [{}] 已关闭", name);
        }
    }

    public static void shutdownAll() {
        for (String name : POOLS.keySet()) {
            shutdown(name);
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