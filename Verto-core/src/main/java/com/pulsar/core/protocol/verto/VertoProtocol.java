package com.pulsar.core.protocol.verto;

import com.pulsar.core.protocol.Caller;
import com.pulsar.core.protocol.Protocol;
import com.pulsar.core.protocol.RemoteRequest;
import com.pulsar.core.protocol.RemoteResponse;
import com.pulsar.remoting.message.PacketStatus;
import com.pulsar.remoting.exchange.ExchangeClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h3>Verto 协议骨架</h3>
 * 管理服务导出（{@link #export}）、
 * 服务引用（{@link #refer}）、请求处理（{@link #handleRequest}）。
 * 传输层与调用层通过本类衔接。
 */
public class VertoProtocol implements Protocol {

    private final Map<String, VertoExporter> exporterMap = new ConcurrentHashMap<>();
    private final VertoCodec codec = new VertoCodec();
    private final String serializerKey;

    public VertoProtocol(String serializerKey) {
        this.serializerKey = serializerKey;
    }

    /**
     * 服务导出：注册服务实现
     */
    @Override
    public void export(String serviceName, Object impl) {
        exporterMap.put(serviceName, new VertoExporter(serviceName, impl));
    }

    /**
     * 请求处理核心（服务端）：查导出器并反射调用
     */
    @Override
    public RemoteResponse handleRequest(RemoteRequest request) {
        VertoExporter exporter = exporterMap.get(request.getServiceName());
        if (exporter == null) {
            return RemoteResponse.builder()
                .errorCode(String.valueOf(PacketStatus.SERVICE_NOT_FOUND.getValue()))
                .errorMessage("服务未找到: " + request.getServiceName())
                .build();
        }
        return exporter.invoke(request);
    }

    /**
     * 服务引用：创建远程调用器（使用指定序列化器）
     */
    @Override
    public Caller refer(ExchangeClient exchangeClient, String serializerKey, long timeoutMs) {
        return new VertoCaller(exchangeClient, codec, serializerKey, timeoutMs);
    }

    /**
     * 服务引用：创建远程调用器（使用协议默认序列化器）
     */
    public Caller refer(ExchangeClient exchangeClient, long timeoutMs) {
        return refer(exchangeClient, serializerKey, timeoutMs);
    }

    public VertoCodec getCodec() {
        return codec;
    }

    public String getSerializerKey() {
        return serializerKey;
    }
}
