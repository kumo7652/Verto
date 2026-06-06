package com.pulsar.core.server;

import com.pulsar.model.RemoteRequest;
import com.pulsar.model.RemoteResponse;
import com.pulsar.protocol.verto.PacketStatus;
import com.pulsar.protocol.verto.VertoPacket;
import com.pulsar.registry.local.LocalRegistry;
import com.pulsar.transport.RequestHandler;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;

/**
 * <h3>服务端请求调用器</h3>
 * 实现传输层的 {@link RequestHandler}，负责：查本地注册表 → 反射调用 → 封包响应。
 * 优先使用预实例化的服务对象，否则通过 {@link LocalRegistry} 获取实现类并反射实例化。
 */
@Slf4j
public class ServerInvoker implements RequestHandler {

    private final String serializerKey;

    public ServerInvoker(String serializerKey) {
        this.serializerKey = serializerKey;
    }

    @Override
    public VertoPacket<RemoteResponse> handle(VertoPacket<RemoteRequest> requestPacket) {
        RemoteRequest request = requestPacket.getBody();
        long requestId = requestPacket.getHeader().getRequestId();
        String serviceName = request.getServiceName();

        try {
            Class<?> implClass = LocalRegistry.get(serviceName);
            if (implClass == null) {
                log.warn("服务未找到: {}", serviceName);
                return VertoPacket.fail(requestId, PacketStatus.SERVICE_NOT_FOUND,
                        "服务未找到: " + serviceName, serializerKey);
            }

            Object impl = implClass.getConstructor().newInstance();
            Method method = implClass.getMethod(request.getMethodName(), request.getParameterTypes());
            Object result = method.invoke(impl, request.getParameters());

            Class<?> returnType = method.getReturnType();
            if (returnType == void.class || returnType == Void.class) {
                return VertoPacket.success(requestId, null, Void.class, serializerKey);
            }
            return VertoPacket.success(requestId, result, returnType, serializerKey);

        } catch (NoSuchMethodException e) {
            log.error("方法未找到: {}#{}", serviceName, request.getMethodName(), e);
            return VertoPacket.fail(requestId, PacketStatus.METHOD_NOT_FOUND,
                    "方法未找到: " + request.getMethodName(), serializerKey);
        } catch (Exception e) {
            log.error("服务调用异常: {}#{}", serviceName, request.getMethodName(), e);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return VertoPacket.fail(requestId, PacketStatus.SERVER_ERROR,
                    cause.getMessage(), serializerKey);
        }
    }
}
