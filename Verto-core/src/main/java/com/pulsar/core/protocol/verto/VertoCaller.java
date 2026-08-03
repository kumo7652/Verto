package com.pulsar.core.protocol.verto;

import com.pulsar.core.protocol.Caller;
import com.pulsar.core.protocol.RemoteRequest;
import com.pulsar.core.protocol.RemoteResponse;

import com.pulsar.exception.RpcException;
import com.pulsar.model.ServiceNode;
import com.pulsar.remoting.transport.netty.client.NettyTransportClient;

import java.util.concurrent.TimeUnit;

/**
 * <h3>Verto 远程调用器</h3>
 * 封装"发送请求、接收响应"，
 * 内部使用 {@link NettyTransportClient} + {@link VertoCodec}（传输层只认 byte[]）。
 */
public class VertoCaller implements Caller {

    private final NettyTransportClient transportClient;
    private final VertoCodec codec;
    private final String serializerKey;
    private final long timeoutMs;

    public VertoCaller(NettyTransportClient transportClient, VertoCodec codec,
                        String serializerKey, long timeoutMs) {
        this.transportClient = transportClient;
        this.codec = codec;
        this.serializerKey = serializerKey;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public RemoteResponse invoke(RemoteRequest request, ServiceNode node) throws Exception {
        byte[] requestBytes = codec.encodeRequest(request, serializerKey);
        byte[] responseBytes = transportClient.send(requestBytes, node, serializerKey)
                .get(timeoutMs, TimeUnit.MILLISECONDS);
        if (responseBytes == null || responseBytes.length == 0) {
            throw new RpcException("服务端异常（空响应）");
        }
        return codec.decodeResponse(responseBytes, serializerKey);
    }
}
