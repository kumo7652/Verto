package com.pulsar.core.protocol;

import com.pulsar.model.ServiceNode;

/**
 * <h3>调用器抽象接口</h3>
 * 统一抽象"发起一次调用"的能力。
 * 具体协议（Verto 远程调用、未来的 HTTP 等）实现此接口。
 */
public interface Caller {

    /** 发起一次调用：序列化请求 → 发送 → 接收响应 → 反序列化 */
    RemoteResponse invoke(RemoteRequest request, ServiceNode node) throws Exception;
}
