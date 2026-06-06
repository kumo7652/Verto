package com.pulsar.core.server;

import com.pulsar.model.RemoteRequest;
import com.pulsar.model.RemoteResponse;
import com.pulsar.protocol.verto.VertoPacket;
import com.pulsar.transport.RequestHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * <h3>业务线程池包装器</h3>
 * 将 {@link RequestHandler#handle} 的调用从 Netty worker 线程
 * 委托到独立的业务线程池执行，避免业务调用阻塞 I/O 线程。
 */
@Slf4j
public class ThreadPoolRequestHandler implements RequestHandler {

    private final RequestHandler delegate;
    private final ExecutorService businessPool;

    public ThreadPoolRequestHandler(RequestHandler delegate, ExecutorService businessPool) {
        this.delegate = delegate;
        this.businessPool = businessPool;
    }

    @Override
    public VertoPacket<RemoteResponse> handle(VertoPacket<RemoteRequest> request) {
        try {
            return businessPool.submit(() -> delegate.handle(request)).get();
        } catch (RejectedExecutionException e) {
            log.error("业务线程池已满, requestId={}", request.getHeader().getRequestId());
            throw new RuntimeException("服务过载", e);
        } catch (Exception e) {
            log.error("业务线程池执行异常, requestId={}", request.getHeader().getRequestId(), e);
            throw new RuntimeException(e);
        }
    }
}
