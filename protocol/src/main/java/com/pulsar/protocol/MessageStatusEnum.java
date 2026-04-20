package com.pulsar.protocol;

import lombok.Getter;

/**
 * 消息状态
 * 20 —— 成功
 * 40 —— 请求失败
 * 50 —— 相应失败
 */
@Getter
public enum MessageStatusEnum {
    SUCCESS("success", 20),
    BAD_REQUEST("bad request", 40),
    BAD_RESPONSE("bad response", 50);

    private final String text;
    private final int value;

    MessageStatusEnum(String text, int value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据value获取类型
     */
    public static MessageStatusEnum getEnumByValue(int value) {
        for (MessageStatusEnum statusEnum : MessageStatusEnum.values()) {
            if (statusEnum.value == value) {
                return statusEnum;
            }
        }
        return null;
    }
}
