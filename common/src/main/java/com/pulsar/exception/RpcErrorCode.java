package com.pulsar.exception;

import lombok.Getter;

@Getter
public enum RpcErrorCode {
    // 通用错误 1000-1999
    SUCCESS(0, "success"),
    UNKNOWN_ERROR(1000, "未知错误"),
    TIMEOUT(1001, "请求超时"),

    // 传输层错误 2000-2999
    CONNECT_FAILED(2000, "连接远程服务器失败"),
    CONNECTION_CLOSED(2001, "连接意外关闭"),

    // 协议层错误 3000-3999
    SERIALIZATION_FAILED(3000, "序列化失败"),
    DESERIALIZATION_FAILED(3001, "反序列化失败"),
    MAGIC_NUMBER_MISMATCH(3002, "魔数不匹配"),

    // 注册中心错误 4000-4999
    REGISTRY_FAILED(4000, "服务注册失败"),
    DISCOVERY_FAILED(4001, "服务发现失败"),
    NO_AVAILABLE_SERVICE(4002, "暂无可用服务"),

    // 业务错误 5000-5999（由业务方定义）
    ;

    private final int code;
    private final String defaultMessage;

    RpcErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }
}