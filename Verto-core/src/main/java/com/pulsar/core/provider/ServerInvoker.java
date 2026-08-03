package com.pulsar.core.provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pulsar.core.protocol.RemoteRequest;
import com.pulsar.core.protocol.RemoteResponse;
import com.pulsar.core.protocol.verto.VertoCodec;
import com.pulsar.core.protocol.verto.VertoProtocol;
import com.pulsar.remoting.protocol.PacketStatus;
import com.pulsar.remoting.protocol.VertoPacket;
import com.pulsar.remoting.transport.RequestHandler;

import java.io.IOException;

/**
 * <h3>服务端请求调用器</h3>
 * 实现传输层的 {@link RequestHandler}，衔接传输层（byte[]）与协议层（{@link VertoProtocol}）：
 * 解码请求 → 协议处理 → 编码响应。
 */
public class ServerInvoker implements RequestHandler {

    private static final Logger log = LoggerFactory.getLogger(ServerInvoker.class);

    private final VertoProtocol protocol;
    private final VertoCodec codec;
    private final String serializerKey;

    public ServerInvoker(VertoProtocol protocol) {
        this.protocol = protocol;
        this.codec = protocol.getCodec();
        this.serializerKey = protocol.getSerializerKey();
    }

    @Override
    public VertoPacket handle(VertoPacket requestPacket) {
        long requestId = requestPacket.getHeader().getRequestId();
        try {
            RemoteRequest request = codec.decodeRequest(requestPacket.getBodyBytes(), serializerKey);
            RemoteResponse response = protocol.handleRequest(request);
            byte[] body = codec.encodeResponse(response, serializerKey);
            VertoPacket.Header header = VertoPacket.responseHeader(requestId, codec.getSerializerCode(serializerKey));
            return new VertoPacket(header, body);
        } catch (IOException e) {
            log.error("协议处理失败, requestId={}", requestId, e);
            return buildEmptyError(requestId);
        }
    }

    private VertoPacket buildEmptyError(long requestId) {
        VertoPacket.Header header = VertoPacket.responseHeader(requestId, codec.getSerializerCode(serializerKey));
        header.setStatus((byte) PacketStatus.SERVER_ERROR.getValue());
        return new VertoPacket(header, new byte[0]);
    }
}
