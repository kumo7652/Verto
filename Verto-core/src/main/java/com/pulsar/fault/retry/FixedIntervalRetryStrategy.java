package com.pulsar.fault.retry;

import com.pulsar.constant.RetryConstant;
import com.pulsar.extension.SpiExtension;
import com.pulsar.model.RpcResponse;
import com.github.rholder.retry.*;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

@Slf4j
@SpiExtension(name = "fixedInterval")
public class FixedIntervalRetryStrategy implements RetryStrategy {
    @Override
    public RpcResponse doRetry(Callable<RpcResponse> callable) throws Exception {
        Retryer<RpcResponse> retryer = RetryerBuilder.<RpcResponse>newBuilder()
                .retryIfExceptionOfType(Exception.class)
                .withWaitStrategy(WaitStrategies.fixedWait(RetryConstant.DEFAULT_RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS))
                .withStopStrategy(StopStrategies.stopAfterAttempt(RetryConstant.DEFAULT_MAX_ATTEMPTS))
                .withRetryListener(new RetryListener() {
                    @Override
                    public <V> void onRetry(Attempt<V> attempt) {
                        log.info("重试次数：{}",attempt.getAttemptNumber());
                    }
                })
                .build();
        return retryer.call(callable);
    }
}
