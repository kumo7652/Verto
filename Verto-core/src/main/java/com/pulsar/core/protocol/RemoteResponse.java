package com.pulsar.core.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * <h3>RPC 响应默认实现</h3>
 * 参考 Dubbo {@code RpcResult} 字段设计。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemoteResponse implements Response {

    private Object data;
    private Class<?> dataType;
    private String message;
    private String errorCode;
    private String errorMessage;

    @Builder.Default
    private Map<String, String> attachments = new HashMap<>();
}
