package com.pulsar.client;

import cn.hutool.core.util.IdUtil;
import com.pulsar.RpcApplication;
import com.pulsar.constant.NetworkConstant;
import com.pulsar.constant.ProtocolConstant;
import com.pulsar.exception.NetworkException;
import com.pulsar.model.RpcRequest;
import com.pulsar.model.RpcResponse;
import com.pulsar.registry.model.ServiceInstance;
import com.pulsar.server.BufferHandlerWrapper;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import lombok.extern.slf4j.Slf4j;
import com.pulsar.protocol.MessageDecoder;
import com.pulsar.protocol.MessageEncoder;
import com.pulsar.protocol.MessageTypeEnum;
import com.pulsar.protocol.ProtocolMessage;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class VertxTcpClient {
    private static volatile VertxTcpClient instance;
    private final Vertx vertx;
    private final Map<String, Queue<NetSocket>> connectionPolls;
    private final Map<String, Semaphore> connectionSemaphores;
    private final Map<Long, CompletableFuture<RpcResponse>> requestWindow;
    private final NetClient client;

    private VertxTcpClient() {
        this.vertx = Vertx.vertx();
        this.connectionPolls = new ConcurrentHashMap<>();
        this.connectionSemaphores = new ConcurrentHashMap<>();
        this.requestWindow = new ConcurrentHashMap<>();
        this.client = vertx.createNetClient();
    }

    public static VertxTcpClient getInstance() {
        if (instance == null) {
            synchronized (VertxTcpClient.class) {
                if (instance == null) {
                    instance = new VertxTcpClient();
                }
            }
        }
        return instance;
    }

    private Future<NetSocket> getConnection(String host, int port) throws InterruptedException {
        String address = host + ":" + port;

        Queue<NetSocket> poll = connectionPolls.computeIfAbsent(address,
                k -> new ArrayBlockingQueue<>(NetworkConstant.DEFAULT_MAX_CONNECTIONS));

        Semaphore semaphore = connectionSemaphores.computeIfAbsent(address,
                k -> new Semaphore(NetworkConstant.DEFAULT_MAX_CONNECTIONS, true));

        if (!semaphore.tryAcquire(NetworkConstant.DEFAULT_ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return Future.failedFuture("获取连接超时，连接池已满");
        }

        NetSocket connectionSocket = poll.poll();

        if (connectionSocket != null) {
            return Future.succeededFuture(connectionSocket);
        }

        return createConnection(host, port, semaphore);
    }

    @SuppressWarnings("unchecked")
    private Future<NetSocket> createConnection(String host, int port, Semaphore semaphore) {
        Promise<NetSocket> promise = Promise.promise();
        client.connect(port, host, result -> {
            if (result.succeeded()) {
                NetSocket socket = result.result();

                BufferHandlerWrapper handler = new BufferHandlerWrapper(buffer -> {
                    long requestId = -1;
                    CompletableFuture<RpcResponse> future = null;

                    try {
                        requestId = buffer.getLong(ProtocolConstant.REQUEST_ID_OFFSET);
                        future = requestWindow.remove(requestId);

                        if (future == null) {
                            log.warn("未找到对应的请求，requestId: {}", requestId);
                            return;
                        }

                        ProtocolMessage<RpcResponse> responseMessage =
                                (ProtocolMessage<RpcResponse>) MessageDecoder.decode(buffer);
                        future.complete(responseMessage.getBody());

                    } catch (Exception e) {
                        log.error("响应处理失败，requestId: {}", requestId, e);
                        if (future != null) {
                            future.completeExceptionally(e);
                        }
                    }
                });

                socket.handler(handler);
                promise.complete(socket);
            } else {
                semaphore.release();
                promise.fail(result.cause());
            }
        });

        return promise.future();
    }

    private void releaseConnection(NetSocket socket) {
        if (socket == null) return;

        SocketAddress socketAddress = socket.remoteAddress();
        String address = socketAddress.host() + ":" + socketAddress.port();
        Queue<NetSocket> poll = connectionPolls.get(address);
        Semaphore semaphore = connectionSemaphores.get(address);

        if (poll != null) {
            log.info("归还连接到：{}", address);
            poll.offer(socket);
        } else {
            socket.close();
        }

        if (semaphore != null) {
            semaphore.release();
        }
    }

    public void close() {
        log.debug("正在关闭 VertxTcpClient 连接池...");

        for (Queue<NetSocket> queue : connectionPolls.values()) {
            while (!queue.isEmpty()) {
                NetSocket socket = queue.poll();
                if (socket != null) {
                    socket.close();
                }
            }
        }

        connectionSemaphores.clear();
        connectionPolls.clear();
        requestWindow.forEach((id, future) ->
                future.completeExceptionally(new NetworkException("连接池已关闭")));
        requestWindow.clear();

        if (client != null) {
            client.close();
        }

        if (vertx != null) {
            vertx.close();
        }

        instance = null;
        log.info("VertxTcpClient 连接池已关闭。");
    }

    public RpcResponse sendRequest(RpcRequest request, ServiceInstance serviceInstance) throws Exception {
        CompletableFuture<RpcResponse> responseFuture = new CompletableFuture<>();
        long requestId = IdUtil.getSnowflakeNextId();

        requestWindow.put(requestId, responseFuture);

        responseFuture.whenComplete((res, ex) ->
                requestWindow.remove(requestId));

        String host = serviceInstance.getServiceHost();
        int port = serviceInstance.getServicePort();
        Future<NetSocket> connection = getConnection(host, port);

        connection.onSuccess(socket -> {
            try {
                ProtocolMessage<RpcRequest> requestMessage = ProtocolMessage.createMessage(
                        MessageTypeEnum.REQUEST,
                        RpcApplication.getApplicationConfig().getSerializer(),
                        requestId,
                        request
                );

                Buffer buffer = MessageEncoder.encode(requestMessage);
                socket.write(buffer);
            } catch (Throwable e) {
                log.error("发送请求失败，requestId: {}", requestId, e);
                responseFuture.completeExceptionally(e);
            } finally {
                releaseConnection(socket);
            }
        }).onFailure(responseFuture::completeExceptionally);

        return responseFuture.get(NetworkConstant.DEFAULT_RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }
}