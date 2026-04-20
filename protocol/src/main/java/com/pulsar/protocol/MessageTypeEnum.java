package com.pulsar.protocol;

import lombok.Getter;

/**
 * 消息类型
 * 0 —— 请求
 * 1 —— 相应
 * 2 —— 心跳
 * 3 —— 其它
 */
@Getter
public enum MessageTypeEnum {
    REQUEST(0),
    RESPONSE(1),
    HEART_BEAT(2),
    OTHERS(3);

    private final int value;

    MessageTypeEnum(int value) {
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     */
    public static MessageTypeEnum getEnumByKey(int value) {
        for (MessageTypeEnum anEnum : MessageTypeEnum.values()) {
            if (anEnum.value == value) {
                return anEnum;
            }
        }
        return null;
    }
}
