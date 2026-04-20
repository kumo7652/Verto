package com.pulsar.server;

import io.vertx.core.Vertx;
import io.vertx.core.net.NetServer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class VertxTcpServer implements TcpServer {
    @Override
    public void doStart(int port) {
        // 创建Vert.x示例
        Vertx vertx = Vertx.vertx();

        // 创建Tcp服务器
        NetServer server = vertx.createNetServer();

        // 注册处理器，处理请求
        server.connectHandler(new ServerHandler());

        // 启动服务器并监听端口
        server.listen(port, res -> {
            if (res.succeeded()) {
                log.info("TCP server started on port {}", port);
            } else  {
                log.error("TCP server start failed on port {}",port);
            }
        });
    }
}
