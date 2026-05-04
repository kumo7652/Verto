package com.pulsar.protocol.verto;

import lombok.Getter;

/**
 * <h3>Verto 协议包类型</h3>
 * 定义包的类型值，用于标识线上传输的包属于请求、响应还是心跳
 *
 * @see VertoPacket
 */
@Getter
public enum PacketType {
    REQUEST(0),
    RESPONSE(1),
    HEARTBEAT(2);

    private final int value;

    PacketType(int value) {
        this.value = value;
    }

    /**
     * <h3>根据数值获取包类型</h3>
     * 遍历所有枚举值匹配给定的数值
     *
     * @param value 包类型的数值编码
     * @return 对应的包类型，不存在则返回 null
     */
    public static PacketType fromValue(int value) {
        for (PacketType t : values()) {
            if (t.value == value) {
                return t;
            }
        }
        return null;
    }
}
