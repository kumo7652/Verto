package com.pulsar.metadata.redis;

import cn.hutool.json.JSONUtil;
import com.pulsar.extension.SpiExtension;
import com.pulsar.metadata.MetadataCenter;
import com.pulsar.metadata.config.MetadataConfig;
import com.pulsar.model.MethodMetadata;
import com.pulsar.model.ServiceMetadata;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@SpiExtension(name = "redis")
public class RedisMetadataCenter implements MetadataCenter {
    private static final String SERVICE_KEY_PREFIX = "rpc:metadata:service:";
    private static final String METHOD_KEY_PREFIX = "rpc:metadata:method:";

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;

    @Override
    public void init(MetadataConfig config) {
        RedisURI.Builder uriBuilder = RedisURI.builder()
                .withHost(RedisURI.create(config.getAddress()).getHost())
                .withPort(RedisURI.create(config.getAddress()).getPort());

        if (config.getPassword() != null && !config.getPassword().isEmpty()) {
            uriBuilder.withPassword(config.getPassword().toCharArray());
        }
        uriBuilder.withDatabase(config.getDatabase());
        uriBuilder.withTimeout(Duration.ofMillis(config.getTimeout()));

        redisClient = RedisClient.create(uriBuilder.build());
        connection = redisClient.connect();
        log.info("Redis 元数据中心初始化成功: {}", config.getAddress());
    }

    @Override
    public void storeService(ServiceMetadata metadata) {
        RedisCommands<String, String> commands = connection.sync();
        String key = SERVICE_KEY_PREFIX + metadata.getServiceKey();
        Map<String, String> fields = Map.of(
                "serviceKey", nullSafe(metadata.getServiceKey()),
                "serviceName", nullSafe(metadata.getServiceName()),
                "description", nullSafe(metadata.getDescription()),
                "serviceVersion", nullSafe(metadata.getServiceVersion()),
                "serviceGroup", nullSafe(metadata.getServiceGroup()),
                "interfaceClass", nullSafe(metadata.getInterfaceClass())
        );
        commands.hset(key, fields);
        log.debug("存储服务元数据: {}", metadata.getServiceKey());
    }

    @Override
    public void removeService(String serviceKey) {
        RedisCommands<String, String> commands = connection.sync();
        commands.del(SERVICE_KEY_PREFIX + serviceKey);

        // 同时删除该服务的所有方法元数据
        List<String> methodKeys = commands.keys(METHOD_KEY_PREFIX + serviceKey + ":*");
        if (methodKeys != null && !methodKeys.isEmpty()) {
            commands.del(methodKeys.toArray(new String[0]));
        }
        log.debug("删除服务元数据: {}", serviceKey);
    }

    @Override
    public ServiceMetadata getService(String serviceKey) {
        RedisCommands<String, String> commands = connection.sync();
        String key = SERVICE_KEY_PREFIX + serviceKey;
        Map<String, String> fields = commands.hgetall(key);

        if (fields == null || fields.isEmpty()) {
            return null;
        }

        return ServiceMetadata.builder()
                .serviceKey(fields.get("serviceKey"))
                .serviceName(fields.get("serviceName"))
                .description(fields.get("description"))
                .serviceVersion(fields.get("serviceVersion"))
                .serviceGroup(fields.get("serviceGroup"))
                .interfaceClass(fields.get("interfaceClass"))
                .methods(getMethods(serviceKey))
                .build();
    }

    @Override
    public void storeMethod(String serviceKey, MethodMetadata metadata) {
        RedisCommands<String, String> commands = connection.sync();
        String key = METHOD_KEY_PREFIX + serviceKey + ":" + metadata.getMethodKey();

        String paramTypesJson = metadata.getParameterTypes() != null
                ? JSONUtil.toJsonStr(metadata.getParameterTypes())
                : "[]";

        Map<String, String> fields = Map.of(
                "methodName", nullSafe(metadata.getMethodName()),
                "parameterTypes", paramTypesJson,
                "returnType", nullSafe(metadata.getReturnType()),
                "description", nullSafe(metadata.getDescription())
        );
        commands.hset(key, fields);
        log.debug("存储方法元数据: {}.{}", serviceKey, metadata.getMethodName());
    }

    @Override
    public void removeMethod(String serviceKey, String methodKey) {
        RedisCommands<String, String> commands = connection.sync();
        List<String> keys = commands.keys(METHOD_KEY_PREFIX + serviceKey + ":" + methodKey + "*");
        if (keys != null && !keys.isEmpty()) {
            commands.del(keys.toArray(new String[0]));
        }
        log.debug("删除方法元数据: {}.{}", serviceKey, methodKey);
    }

    @Override
    public List<MethodMetadata> getMethods(String serviceKey) {
        RedisCommands<String, String> commands = connection.sync();
        List<String> keys = commands.keys(METHOD_KEY_PREFIX + serviceKey + ":*");

        List<MethodMetadata> result = new ArrayList<>();
        if (keys == null || keys.isEmpty()) {
            return result;
        }

        for (String key : keys) {
            Map<String, String> fields = commands.hgetall(key);
            if (fields != null && !fields.isEmpty()) {
                String paramTypesJson = fields.get("parameterTypes");
                String[] paramTypes = paramTypesJson != null && !paramTypesJson.equals("[]")
                        ? JSONUtil.toBean(paramTypesJson, String[].class)
                        : new String[0];

                result.add(MethodMetadata.builder()
                        .methodName(fields.get("methodName"))
                        .parameterTypes(paramTypes)
                        .returnType(fields.get("returnType"))
                        .description(fields.get("description"))
                        .build());
            }
        }
        return result;
    }

    @Override
    public void destroy() {
        if (connection != null) {
            connection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
        log.info("Redis 元数据中心已关闭");
    }

    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}
