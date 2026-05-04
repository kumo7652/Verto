package com.pulsar.protocol.verto;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <h3>Verto 协议包附件区域编解码</h3>
 * 使用 TLV 风格的 KV 编码，格式为 {@code keyLen(2B) + key(UTF-8) + valueLen(2B) + value(UTF-8)}，
 * 不引入 JSON/Protobuf 等外部依赖，解析速度优于 JSON 一个数量级。
 * 内部使用 {@link LinkedHashMap} 保证编解码顺序一致
 *
 * @see VertoPacket
 */
public class PacketAttachment {

    private final Map<String, String> data = new LinkedHashMap<>();

    /**
     * <h3>从字节数组解码附件</h3>
     * 按顺序读取 keyLen + key + valueLen + value 对
     *
     * @param bytes 编码后的附件字节数组
     * @return 解码后的附件对象
     */
    public static PacketAttachment decode(byte[] bytes) {
        PacketAttachment attachment = new PacketAttachment();
        int offset = 0;
        while (offset < bytes.length) {
            int keyLen = ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
            offset += 2;
            String key = new String(bytes, offset, keyLen, StandardCharsets.UTF_8);
            offset += keyLen;

            int valueLen = ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
            offset += 2;
            String value = new String(bytes, offset, valueLen, StandardCharsets.UTF_8);
            offset += valueLen;

            attachment.data.put(key, value);
        }
        return attachment;
    }

    /**
     * <h3>将附件编码为字节数组</h3>
     * 按 {@link LinkedHashMap} 的插入顺序依次写入 keyLen + key + valueLen + value
     *
     * @return 编码后的字节数组
     */
    public byte[] encode() {
        int totalLen = 0;
        for (Map.Entry<String, String> entry : data.entrySet()) {
            byte[] keyBytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] valueBytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
            totalLen += 2 + keyBytes.length + 2 + valueBytes.length;
        }

        byte[] result = new byte[totalLen];
        int offset = 0;
        for (Map.Entry<String, String> entry : data.entrySet()) {
            byte[] keyBytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] valueBytes = entry.getValue().getBytes(StandardCharsets.UTF_8);

            result[offset++] = (byte) (keyBytes.length >> 8);
            result[offset++] = (byte) keyBytes.length;
            System.arraycopy(keyBytes, 0, result, offset, keyBytes.length);
            offset += keyBytes.length;

            result[offset++] = (byte) (valueBytes.length >> 8);
            result[offset++] = (byte) valueBytes.length;
            System.arraycopy(valueBytes, 0, result, offset, valueBytes.length);
            offset += valueBytes.length;
        }
        return result;
    }

    /**
     * <h3>获取附件值</h3>
     *
     * @param key 键名
     * @return 对应的值，不存在则返回 null
     */
    public String get(String key) {
        return data.get(key);
    }

    /**
     * <h3>写入附件键值对</h3>
     *
     * @param key   键名
     * @param value 值
     * @return 当前附件对象，支持链式调用
     */
    public PacketAttachment put(String key, String value) {
        data.put(key, value);
        return this;
    }
}
