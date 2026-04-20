package com.pulsar.server;

import com.pulsar.constant.ProtocolConstant;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.parsetools.RecordParser;

public class BufferHandlerWrapper implements Handler<Buffer> {
    private final RecordParser parser;

    public BufferHandlerWrapper(Handler<Buffer> handler) {
        parser = initRecordParser(handler);
    }

    @Override
    public void handle(Buffer buffer) {
        parser.handle(buffer);
    }

    private RecordParser initRecordParser(Handler<Buffer> handler) {
        // 构建parser
        RecordParser parser = RecordParser.newFixed(ProtocolConstant.MESSAGE_HEADER_LENGTH);

        parser.setOutput(new Handler<>() {
            private int size = -1;
            private Buffer resultBuffer = Buffer.buffer();

            @Override
            public void handle(Buffer buffer) {
                if (-1 == size) {
                    size = buffer.getInt(ProtocolConstant.BODY_LENGTH_OFFSET);
                    parser.fixedSizeMode(size);
                    resultBuffer.appendBuffer(buffer);
                } else {
                    resultBuffer.appendBuffer(buffer);
                    handler.handle(resultBuffer);

                    parser.fixedSizeMode(ProtocolConstant.MESSAGE_HEADER_LENGTH);
                    size = -1;
                    resultBuffer = Buffer.buffer();
                }
            }
        });

        return parser;
    }
}
