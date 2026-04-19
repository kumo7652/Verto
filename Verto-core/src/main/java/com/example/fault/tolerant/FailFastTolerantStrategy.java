package com.example.fault.tolerant;

import com.example.exception.ServiceException;
import com.example.model.RpcResponse;

import java.util.Map;

public class FailFastTolerantStrategy implements TolerantStrategy {
    @Override
    public RpcResponse doTolerant(Map<String, Object> context, Exception e) {
        throw new ServiceException("服务错误", e);
    }
}
