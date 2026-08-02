package com.pulsar.remoting.protocol;

import lombok.Getter;

/**
 * <h3>Verto 协议状态码</h3>
 * 定义包的响应状态，从 0 开始连续编号，覆盖常见业务与协议层错误场景。
 * 未知状态码兜底返回 {@link #SERVER_ERROR}，避免 NPE
 *
 * @see VertoPacket
 */
@Getter
public enum PacketStatus {
    SUCCESS(0),
    BAD_REQUEST(1),
    SERVICE_NOT_FOUND(2),
    METHOD_NOT_FOUND(3),
    SERIALIZE_ERROR(4),
    TIMEOUT(5),
    RATE_LIMITED(6),
    SERVER_ERROR(7);

    private final int value;

    PacketStatus(int value) {
        this.value = value;
    }

    /**
     * <h3>根据数值获取状态码</h3>
     * 遍历所有枚举值匹配给定的数值，不匹配时返回 {@link #SERVER_ERROR}
     *
     * @param value 状态码的数值编码
     * @return 对应的状态码，不存在则返回 SERVER_ERROR
     */
    public static PacketStatus fromValue(int value) {
        for (PacketStatus s : values()) {
            if (s.value == value) {
                return s;
            }
        }
        return SERVER_ERROR;
    }
}
