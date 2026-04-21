package com.pulsar.exception;

public class RegistryException extends RpcException {
    public RegistryException(RpcErrorCode errorCode) {
        super(errorCode);
    }

    public RegistryException(RpcErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    public RegistryException(RpcErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
