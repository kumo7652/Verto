package com.pulsar.remoting.message;

/**
 * <h3>协议层异常</h3>
 * 编解码、版本协商、安全校验等协议层错误统一抛出此异常，
 * 放在 protocol 包根目录以便多协议适配器共享
 */
public class ProtocolException extends RuntimeException {

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
