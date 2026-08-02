package com.pulsar.remoting.transport.netty;

import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;

/**
 * <h3>EventLoopGroup 共享管理</h3>
 * 同一进程内的服务端和客户端共享 worker 实例，避免重复创建事件循环线程池。
 * boss Group（1 线程）仅服务端使用，负责接受新连接。
 * <p>
 * Netty 4.2 起 NioEventLoopGroup 弃用，统一使用 MultiThreadIoEventLoopGroup + IoHandlerFactory
 */
public class NettyEventLoopGroup {
    private static volatile MultiThreadIoEventLoopGroup boss;
    private static volatile MultiThreadIoEventLoopGroup worker;

    private NettyEventLoopGroup() {}

    public static MultiThreadIoEventLoopGroup getBoss() {
        if (boss == null) {
            synchronized (NettyEventLoopGroup.class) {
                if (boss == null) {
                    boss = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
                }
            }
        }
        return boss;
    }

    public static MultiThreadIoEventLoopGroup getWorker() {
        if (worker == null) {
            synchronized (NettyEventLoopGroup.class) {
                if (worker == null) {
                    worker = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
                }
            }
        }
        return worker;
    }

    public static void shutdown() {
        if (boss != null) {
            boss.shutdownGracefully();
            boss = null;
        }
        if (worker != null) {
            worker.shutdownGracefully();
            worker = null;
        }
    }
}
