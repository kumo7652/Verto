package com.pulsar.protocol;

import com.pulsar.constant.ProtocolConstant;
import com.pulsar.model.RpcResponse;
import com.pulsar.serializer.SerializerFactory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ProtocolMessage<T> {
    /**
     * 消息头
     */
    private Header header;

    /**
     * 消息体
     */
    private T body;


    /**
     * 消息头，总共17字节
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Header {
        /**
         * 魔数 —— 快速识别协议
         */
        private byte magic;

        /**
         * 协议版本号
         */
        private byte version;

        /**
         * 序列化器 —— 整个消息的序列化方式
         */
        private byte serializerCode;

        /**
         * 消息类型（请求/响应）
         */
        private byte type;

        /**
         * 状态 —— 成功，失败，超时等
         */
        private byte status;

        /**
         * 请求id 8字节
         * 1. 匹配请求与响应
         * 2. 多路复用
         */
        private long requestId;

        /**
         * 消息体长度 4字节
         * 1. 解决tcp的半包粘包问题
         * 2. 内存预分配
         */
        private int bodyLength;
    }

    /**
     * 构建一个 RPC 请求或响应消息
     *
     * @param body 消息体 (RpcRequest 或 RpcResponse)
     * @param type 消息类型 (MessageTypeEnum)
     * @param serializerKey 序列化器别名 (如 "jdk", "json")
     * @param requestId 请求 ID
     * @return 完整的协议消息对象
     */
    public static <T> ProtocolMessage<T> createMessage(
            MessageTypeEnum type,
            String serializerKey, long requestId, T body) {
        // 构建消息头
        Header header = new Header();
        header.setMagic(ProtocolConstant.MAGIC);
        header.setVersion(ProtocolConstant.VERSION);

        // 根据序列化器名称获取协议编号
        byte serializerCode = SerializerFactory.getInstance().getCodeByName(serializerKey);
        header.setSerializerCode(serializerCode);

        // 设置基本信息
        header.setType((byte) type.getValue());
        header.setStatus((byte) MessageStatusEnum.SUCCESS.getValue());
        header.setRequestId(requestId);

        // 组装消息
        ProtocolMessage<T> message = new ProtocolMessage<>();
        message.setHeader(header);
        message.setBody(body);

        return message;
    }

    /**
     * 构建成功消息
     *
     * @param requestId 请求ID
     * @param data      响应数据
     * @param dataType  响应数据类型
     * @param serializerKey 序列化器别名
     * @return 成功的协议消息
     */
    public static ProtocolMessage<RpcResponse> success(
            long requestId, Object data, Class<?> dataType, String serializerKey) {
        RpcResponse response = RpcResponse.builder()
                .data(data)
                .dataType(dataType)
                .message("ok")
                .build();

        ProtocolMessage<RpcResponse> message = createMessage(
                MessageTypeEnum.RESPONSE,
                serializerKey,
                requestId,
                response
        );

        message.getHeader().setStatus((byte) MessageStatusEnum.SUCCESS.getValue());

        return message;
    }

    /**
     * 构建请求失败消息
     * 用于：解码失败、参数错误等客户端问题
     *
     * @param requestId 请求ID
     * @param message   错误信息
     * @param serializerKey 序列化器别名
     * @return 请求失败的协议消息
     */
    public static ProtocolMessage<RpcResponse> badRequest(
            long requestId, String message, String serializerKey) {
        RpcResponse response = RpcResponse.builder()
                .data(null)
                .message(message)
                .build();

        ProtocolMessage<RpcResponse> msg = createMessage(
                MessageTypeEnum.RESPONSE,
                serializerKey,
                requestId,
                response
        );

        msg.getHeader().setStatus((byte) MessageStatusEnum.BAD_REQUEST.getValue());

        return msg;
    }

    /**
     * 构建响应失败消息
     * 用于：方法调用异常等服务端问题
     *
     * @param requestId 请求ID
     * @param exception 异常信息
     * @param serializerKey 序列化器别名
     * @return 响应失败的协议消息
     */
    public static ProtocolMessage<RpcResponse> error(
            long requestId, Exception exception, String serializerKey) {
        RpcResponse response = RpcResponse.builder()
                .data(null)
                .exception(exception)
                .message(exception.getMessage())
                .build();

        ProtocolMessage<RpcResponse> msg = createMessage(
                MessageTypeEnum.RESPONSE,
                serializerKey,
                requestId,
                response
        );
        msg.getHeader().setStatus((byte) MessageStatusEnum.BAD_RESPONSE.getValue());

        return msg;
    }

}
