package com.pulsar.core.server;

import com.pulsar.remoting.protocol.PacketStatus;
import com.pulsar.core.protocol.RemoteRequest;
import com.pulsar.core.protocol.RemoteResponse;
import com.pulsar.remoting.protocol.VertoPacket;
import com.pulsar.serializer.Serializer;
import com.pulsar.registry.local.LocalRegistry;
import com.pulsar.serializer.SerializerFactory;
import com.pulsar.remoting.transport.RequestHandler;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.lang.reflect.Method;

/**
 * <h3>服务端请求调用器</h3>
 * 实现传输层的 {@link RequestHandler}，负责：查本地注册表 → 反射调用 → 封包响应。
 */
@Slf4j
public class ServerInvoker implements RequestHandler {

    private final String serializerKey;

    public ServerInvoker(String serializerKey) {
        this.serializerKey = serializerKey;
    }

    @Override
    public VertoPacket handle(VertoPacket requestPacket) {
        byte code = SerializerFactory.getInstance().getCodeByName(serializerKey);
        Serializer serializer = SerializerFactory.getInstance().getByCode(code);
        long requestId = requestPacket.getHeader().getRequestId();

        RemoteRequest request;
        try {
            request = serializer.deserialize(requestPacket.getBodyBytes(), RemoteRequest.class);
        } catch (IOException e) {
            log.error("请求反序列化失败, requestId={}", requestId, e);
            return buildEmptyError(requestId, PacketStatus.SERVER_ERROR);
        }
        String serviceName = request.getServiceName();

        try {
            Object impl = LocalRegistry.get(serviceName);
            if (impl == null) {
                log.warn("服务未找到: {}", serviceName);
                return buildError(serializer, requestId, PacketStatus.SERVICE_NOT_FOUND,
                        "服务未找到: " + serviceName);
            }

            Method method = impl.getClass().getMethod(request.getMethodName(), request.getParameterTypes());
            Object result = method.invoke(impl, request.getParameters());

            Class<?> returnType = method.getReturnType();
            return buildSuccess(serializer, requestId, result, returnType);

        } catch (NoSuchMethodException e) {
            log.error("方法未找到: {}#{}", serviceName, request.getMethodName(), e);
            return buildError(serializer, requestId, PacketStatus.METHOD_NOT_FOUND,
                    "方法未找到: " + request.getMethodName());
        } catch (Exception e) {
            log.error("服务调用异常: {}#{}", serviceName, request.getMethodName(), e);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return buildError(serializer, requestId, PacketStatus.SERVER_ERROR, cause.getMessage());
        }
    }

    private VertoPacket buildSuccess(Serializer serializer, long requestId, Object data, Class<?> dataType) {
        byte code = SerializerFactory.getInstance().getCodeByName(serializerKey);
        VertoPacket.Header header = VertoPacket.responseHeader(requestId, code);
        RemoteResponse response = RemoteResponse.builder()
                .data(data)
                .dataType(dataType)
                .message("success")
                .build();
        try {
            return new VertoPacket(header, serializer.serialize(response));
        } catch (IOException e) {
            log.error("响应序列化失败, requestId={}", requestId, e);
            return buildEmptyError(requestId, PacketStatus.SERVER_ERROR);
        }
    }

    private VertoPacket buildError(Serializer serializer, long requestId, PacketStatus status, String message) {
        byte code = SerializerFactory.getInstance().getCodeByName(serializerKey);
        VertoPacket.Header header = VertoPacket.responseHeader(requestId, code);
        header.setStatus((byte) status.getValue());
        RemoteResponse response = RemoteResponse.builder()
                .errorCode(String.valueOf(status.getValue()))
                .errorMessage(message)
                .build();
        try {
            return new VertoPacket(header, serializer.serialize(response));
        } catch (IOException e) {
            log.error("响应序列化失败, requestId={}", requestId, e);
            return buildEmptyError(requestId, PacketStatus.SERVER_ERROR);
        }
    }

    private VertoPacket buildEmptyError(long requestId, PacketStatus status) {
        byte code = SerializerFactory.getInstance().getCodeByName(serializerKey);
        VertoPacket.Header header = VertoPacket.responseHeader(requestId, code);
        header.setStatus((byte) status.getValue());
        return new VertoPacket(header, new byte[0]);
    }
}
