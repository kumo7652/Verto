package com.pulsar;

import com.pulsar.config.ApplicationConfig;
import com.pulsar.constant.RpcConstant;
import com.pulsar.registry.Registry;
import com.pulsar.registry.RegistryFactory;
import com.pulsar.registry.config.RegistryConfig;
import com.pulsar.transport.config.TransportConfig;
import com.pulsar.transport.netty.client.NettyTransportClient;
import com.pulsar.utils.ConfigUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RpcApplication {
    public static volatile ApplicationConfig applicationConfig;
    private static volatile NettyTransportClient transportClient;

    public static ApplicationConfig getApplicationConfig() {
        if (applicationConfig == null) {
            synchronized (RpcApplication.class) {
                if (applicationConfig == null) {
                    init();
                }
            }
        }
        return applicationConfig;
    }

    public static NettyTransportClient getTransportClient() {
        if (transportClient == null) {
            synchronized (RpcApplication.class) {
                if (transportClient == null) {
                    TransportConfig config = TransportConfig.builder()
                            .serializerKey(getApplicationConfig().getSerializer())
                            .build();
                    transportClient = new NettyTransportClient(config);
                }
            }
        }
        return transportClient;
    }

    public static void init() {
        ApplicationConfig config;
        try {
            config = ConfigUtil.loadConfig(ApplicationConfig.class, RpcConstant.DEFAULT_CONFIG_PREFIX);
        } catch (Exception e) {
            config = new ApplicationConfig();
        }
        init(config);
    }

    private static void init(ApplicationConfig config) {
        applicationConfig = config;
        log.info("rpc框架初始化，配置信息：{}", applicationConfig.toString());

        RegistryConfig registryConfig = applicationConfig.getRegistryConfig();
        log.info("注册中心初始化，配置信息：{}", registryConfig);

        Registry registry = RegistryFactory.getRegistry(registryConfig.getRegistry());
        registry.init(applicationConfig.getRegistryConfig());

        Runtime.getRuntime().addShutdownHook(new Thread(RpcApplication::destroy));
    }

    public static void destroy() {
        log.info("RPC 框架开始销毁...");

        RegistryConfig registryConfig = applicationConfig.getRegistryConfig();
        Registry registry = RegistryFactory.getRegistry(registryConfig.getRegistry());
        if (registry != null) {
            registry.destroy();
        }

        if (transportClient != null) {
            transportClient.close();
            transportClient = null;
        }

        log.info("RPC 框架销毁完成。");
    }
}
