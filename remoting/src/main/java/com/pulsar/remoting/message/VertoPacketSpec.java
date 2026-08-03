package com.pulsar.remoting.message;

/**
 * <h3>Verto 协议包规约常量</h3>
 * 定义 v2 包格式的头部偏移、字段长度、安全上限及 flags 位掩码，
 * 供传输层编解码 handler 引用
 */
public final class VertoPacketSpec {
    // 协议标识
    public static final byte MAGIC = 0x76;
    public static final byte VERSION_V2 = 0x2;
    // v2 头部偏移
    public static final int MAGIC_OFFSET = 0;   // 1B
    public static final int VERSION_OFFSET = 1;   // 1B
    public static final int FLAGS_OFFSET = 2;   // 1B
    public static final int SERIALIZER_OFFSET = 3;   // 1B
    public static final int TYPE_OFFSET = 4;   // 1B
    public static final int STATUS_OFFSET = 5;   // 1B
    public static final int REQUEST_ID_OFFSET = 6;   // 8B
    public static final int CONTENT_LENGTH_OFFSET = 14;  // 4B
    // 长度
    public static final int HEADER_LENGTH_V2 = 18;
    // 安全上限
    public static final int MAX_CONTENT_LENGTH = 16 * 1024 * 1024;  // 16MB
    // flags 位掩码
    public static final byte FLAG_HEARTBEAT_NO_BODY = 0x02;
    public static final byte FLAG_ONE_WAY = 0x04;
    private VertoPacketSpec() {
    }
}
