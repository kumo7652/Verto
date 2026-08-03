package com.pulsar.remoting.transport;

import com.pulsar.remoting.message.VertoPacket;

/**
 * <h3>请求处理回调接口</h3>
 * 上层实现此接口处理 RPC 请求，传输层通过此接口将请求交给业务层，实现传输与业务的解耦
 *
 * @see VertoPacket
 */
@FunctionalInterface
public interface RequestHandler {

    /**
     * <h3>处理收到的 RPC 请求</h3>
     * 传输层解码后调用此方法，上层完成服务查找、反射调用等业务逻辑
     *
     * @param request 收到的请求包（含 header、body）
     * @return 响应包
     */
    VertoPacket handle(VertoPacket request);
}
