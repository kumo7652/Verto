package com.pulsar.core.protocol;

import java.io.Serializable;
import java.util.Map;

/**
 * <h3>RPC 请求接口</h3>
 * 协议无关的 RPC 调用数据契约。默认实现：{@link RemoteRequest}。
 */
public interface Request extends Serializable {

    String getServiceName();
    String getMethodName();
    Class<?>[] getParameterTypes();
    Object[] getParameters();
    String getServiceVersion();

    /** 可扩展的请求级元数据（trace_id、env、timeout 等），中间件透传 */
    Map<String, String> getAttachments();

    default String getAttachment(String key) {
        Map<String, String> att = getAttachments();
        return att != null ? att.get(key) : null;
    }
}
