package com.pulsar.fault.tolerant;

import com.pulsar.extension.SpiExtension;
import com.pulsar.model.RpcResponse;

import java.util.Map;

@SpiExtension(name = "failFast")
public class FailFastTolerantStrategy implements TolerantStrategy {
    @Override
    public RpcResponse doTolerant(Map<String, Object> context, Exception e) {
        throw new ServiceException("服务错误", e);
    }
}
