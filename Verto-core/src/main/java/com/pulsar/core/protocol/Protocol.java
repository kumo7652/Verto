package com.pulsar.core.protocol;

import com.pulsar.remoting.exchange.ExchangeClient;

/**
 * <h3>协议抽象接口</h3>
 * 统一抽象"协议"的能力——
 * 服务导出（{@link #export}）、请求处理（{@link #handleRequest}）、服务引用（{@link #refer}）。
 * 具体协议（如 Verto、未来的 HTTP）实现此接口。
 */
public interface Protocol {

    /**
     * 服务导出：注册服务实现
     */
    void export(String serviceName, Object impl);

    /**
     * 请求处理核心（服务端）：解析请求并返回响应
     */
    RemoteResponse handleRequest(RemoteRequest request);

    /**
     * 服务引用：创建远程调用器
     */
    Caller refer(ExchangeClient exchangeClient, String serializerKey, long timeoutMs);
}
