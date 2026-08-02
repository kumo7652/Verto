package com.pulsar.core.protocol;

import java.io.Serializable;
import java.util.Map;

/**
 * <h3>RPC 响应接口</h3>
 * 协议无关的 RPC 响应数据契约。默认实现：{@link RemoteResponse}。
 */
public interface Response extends Serializable {

    Object getData();
    Class<?> getDataType();
    String getMessage();
    String getErrorCode();
    String getErrorMessage();

    /** 可扩展的响应级元数据（rate_limited、server_version 等），中间件透传 */
    Map<String, String> getAttachments();

    default String getAttachment(String key) {
        Map<String, String> att = getAttachments();
        return att != null ? att.get(key) : null;
    }

    default boolean isError() {
        return getErrorCode() != null && !getErrorCode().isEmpty();
    }
}
