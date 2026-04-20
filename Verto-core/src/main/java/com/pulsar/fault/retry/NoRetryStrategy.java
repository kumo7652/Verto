package com.pulsar.fault.retry;

import com.pulsar.extension.SpiExtension;
import com.pulsar.model.RpcResponse;

import java.util.concurrent.Callable;

@SpiExtension(name = "no")
public class NoRetryStrategy implements RetryStrategy {
    @Override
    public RpcResponse doRetry(Callable<RpcResponse> callable) throws Exception {
        return callable.call();
    }
}
