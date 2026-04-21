package com.pulsar.exception;

import lombok.Getter;

@Getter
public class RpcException extends RuntimeException {
    private final int code;
    private final String message;

    public RpcException(RpcErrorCode errorCode) {
        this.code = errorCode.getCode();
        this.message = errorCode.getDefaultMessage();
    }

    public RpcException(RpcErrorCode errorCode, String detail) {
        super(errorCode.getDefaultMessage() + ": " + detail);
        this.code = errorCode.getCode();
        this.message = errorCode.getDefaultMessage() + ": " + detail;
    }

    public RpcException(RpcErrorCode errorCode, Throwable cause) {
        super(errorCode.getDefaultMessage(), cause);
        this.code = errorCode.getCode();
        this.message = errorCode.getDefaultMessage();
    }

    // 向后兼容：支持直接传入消息
    public RpcException(String message) {
        super(message);
        this.code = RpcErrorCode.UNKNOWN_ERROR.getCode();
        this.message = message;
    }

    public RpcException(String message, Throwable cause) {
        super(message, cause);
        this.code = RpcErrorCode.UNKNOWN_ERROR.getCode();
        this.message = message;
    }
}
