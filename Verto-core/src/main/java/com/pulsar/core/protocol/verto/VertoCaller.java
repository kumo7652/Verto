package com.pulsar.core.protocol.verto;

import com.pulsar.core.protocol.Caller;
import com.pulsar.core.protocol.RemoteRequest;
import com.pulsar.core.protocol.RemoteResponse;

import com.pulsar.exception.RpcException;
import com.pulsar.model.ServiceNode;
import com.pulsar.remoting.exchange.ExchangeClient;
import com.pulsar.remoting.message.VertoPacket;
import com.pulsar.utils.RequestIdGenerator;

import java.util.concurrent.TimeUnit;

/**
 * <h3>Verto 远程调用器</h3>
 * 封装"生成 requestId、构造帧、经交换层发送并等待响应"，
 * 内部使用 {@link ExchangeClient} + {@link VertoCodec}（传输层只认 byte[]）。
 */
public class VertoCaller implements Caller {

    private final ExchangeClient exchangeClient;
    private final VertoCodec codec;
    private final String serializerKey;
    private final long timeoutMs;

    public VertoCaller(ExchangeClient exchangeClient, VertoCodec codec,
                        String serializerKey, long timeoutMs) {
        this.exchangeClient = exchangeClient;
        this.codec = codec;
        this.serializerKey = serializerKey;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public RemoteResponse invoke(RemoteRequest request, ServiceNode node) throws Exception {
        byte[] requestBytes = codec.encodeRequest(request, serializerKey);
        long requestId = RequestIdGenerator.nextId();
        VertoPacket frame = buildFrame(requestId, requestBytes);
        byte[] responseBytes = exchangeClient.request(requestId, frame, node, timeoutMs)
                .get(timeoutMs, TimeUnit.MILLISECONDS);
        if (responseBytes == null || responseBytes.length == 0) {
            throw new RpcException("服务端异常（空响应）");
        }
        return codec.decodeResponse(responseBytes, serializerKey);
    }

    private VertoPacket buildFrame(long requestId, byte[] requestBytes) {
        byte serializerCode = codec.getSerializerCode(serializerKey);
        return new VertoPacket(VertoPacket.requestHeader(requestId, serializerCode), requestBytes);
    }
}
