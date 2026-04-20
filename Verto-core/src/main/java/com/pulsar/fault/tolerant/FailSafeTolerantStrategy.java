package com.pulsar.fault.tolerant;

import com.pulsar.extension.SpiExtension;
import com.pulsar.model.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 静默处理异常 —— 直接忽略
 */
@Slf4j
@SpiExtension(name = "failSafe")
public class FailSafeTolerantStrategy implements TolerantStrategy {
    @Override
    public RpcResponse doTolerant(Map<String, Object> context, Exception e) {
        log.error("静默处理异常，{}", e.getMessage());
        return null;
    }
}
