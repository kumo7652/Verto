package com.pulsar.constant;

public final class ProtocolConstant {
    private ProtocolConstant() {}

    public static final byte MAGIC = 0x76;
    public static final byte VERSION = 0x1;
    public static final int MESSAGE_HEADER_LENGTH = 17;

    public static final int MAGIC_OFFSET = 0;
    public static final int VERSION_OFFSET = 1;
    public static final int SERIALIZER_OFFSET = 2;
    public static final int TYPE_OFFSET = 3;
    public static final int STATUS_OFFSET = 4;
    public static final int REQUEST_ID_OFFSET = 5;
    public static final int BODY_LENGTH_OFFSET = 13;
    public static final int MIN_BUFFER_LENGTH = 17;

    public static final String ILLEGAL_MESSAGE = "非法消息";
}
