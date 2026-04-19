package com.example.server;

import com.example.constant.ProtocolConstant;
import com.example.constant.RpcConstant;
import com.example.model.RpcRequest;
import com.example.model.RpcResponse;
import com.example.protocol.*;
import com.example.registry.local.LocalRegistry;
import com.example.serializer.SerializerFactory;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;

@Slf4j
public class ServerHandler implements Handler<NetSocket> {

    @Override
    @SuppressWarnings("unchecked")
    public void handle(NetSocket netSocket) {
        BufferHandlerWrapper handlerWrapper = new BufferHandlerWrapper(buffer -> {
            ProtocolMessage<RpcRequest> message;

            try {
                message = (ProtocolMessage<RpcRequest>) MessageDecoder.decode(buffer);
            } catch (Exception e) {
                log.error("解析消息错误：{}", e.getMessage());

                long requestId = extractRequestId(buffer);
                sendResponse(netSocket, ProtocolMessage.badRequest(requestId, "消息解析失败: " + e.getMessage(), RpcConstant.DEFAULT_SERIALIZER));
                return;
            }


            RpcRequest rpcRequest = message.getBody();
            long requestId = message.getHeader().getRequestId();
            String serializerKey = SerializerFactory.getInstance().getNameByCode(message.getHeader().getSerializerCode());

            try {
                Class<?> implClass = LocalRegistry.get(rpcRequest.getServiceName());
                Method method = implClass.getMethod(rpcRequest.getMethodName(), rpcRequest.getParameterTypes());
                Object result = method.invoke(implClass.getConstructor().newInstance(), rpcRequest.getParameters());

                sendResponse(netSocket, ProtocolMessage.success(requestId, result, method.getReturnType(), serializerKey));
            } catch (Exception e) {
                log.error("请求处理失败：{}", e.getMessage());
                sendResponse(netSocket, ProtocolMessage.error(requestId, e, serializerKey));
            }
        });

        netSocket.handler(handlerWrapper);
    }

    private long extractRequestId(Buffer buffer) {
        if (buffer != null && buffer.length() >= ProtocolConstant.MIN_BUFFER_LENGTH) {
            return buffer.getLong(ProtocolConstant.REQUEST_ID_OFFSET);
        }
        return -1;
    }

    private void sendResponse(NetSocket netSocket, ProtocolMessage<RpcResponse> message) {
        try {
            netSocket.write(MessageEncoder.encode(message));
        } catch (Exception e) {
            log.error("编码响应失败", e);
        }
    }
}