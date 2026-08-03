package com.pulsar.core.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * <h3>RPC 请求默认实现</h3>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemoteRequest implements Request {

    private String serviceName;
    private String methodName;
    private Class<?>[] parameterTypes;
    private Object[] parameters;
    private String serviceVersion;

    @Builder.Default
    private Map<String, String> attachments = new HashMap<>();
}
