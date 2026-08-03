package com.pulsar.core.protocol.verto;

/**
 * <h3>Verto 协议常量</h3>
 * 定义 Verto 应用层协议的常量。
 */
public final class VertoConstants {
    private VertoConstants() {}

    /** 协议版本 */
    public static final String PROTOCOL_VERSION = "2.0";

    /** 默认请求超时（毫秒） */
    public static final long DEFAULT_TIMEOUT_MS = 3000;

    /** 默认重试次数 */
    public static final int DEFAULT_RETRIES = 0;
}
