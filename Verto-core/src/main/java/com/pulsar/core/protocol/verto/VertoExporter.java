package com.pulsar.core.protocol.verto;

import com.pulsar.core.protocol.RemoteRequest;
import com.pulsar.core.protocol.RemoteResponse;

import com.pulsar.remoting.protocol.PacketStatus;

import java.lang.reflect.Method;

/**
 * <h3>Verto 服务导出器</h3>
 * 封装一个服务的实现，
 * {@link #invoke} 时反射调用并返回 {@link RemoteResponse}（含成功/错误封装）。
 */
public class VertoExporter {

    private final String serviceName;
    private final Object impl;

    public VertoExporter(String serviceName, Object impl) {
        this.serviceName = serviceName;
        this.impl = impl;
    }

    public String getServiceName() {
        return serviceName;
    }

    public RemoteResponse invoke(RemoteRequest request) {
        try {
            Method method = impl.getClass().getMethod(request.getMethodName(), request.getParameterTypes());
            Object result = method.invoke(impl, request.getParameters());
            return success(result, method.getReturnType());
        } catch (NoSuchMethodException e) {
            return error(PacketStatus.METHOD_NOT_FOUND, "方法未找到: " + request.getMethodName());
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return error(PacketStatus.SERVER_ERROR, cause.getMessage());
        }
    }

    private RemoteResponse success(Object data, Class<?> dataType) {
        return RemoteResponse.builder()
                .data(data)
                .dataType(dataType)
                .message("success")
                .build();
    }

    private RemoteResponse error(PacketStatus status, String message) {
        return RemoteResponse.builder()
                .errorCode(String.valueOf(status.getValue()))
                .errorMessage(message)
                .build();
    }
}
