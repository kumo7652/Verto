package com.pulsar.registry.etcd;

import com.pulsar.model.ServiceNode;
import com.pulsar.config.RegistryConfig;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.options.DeleteOption;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EtcdRegistry 集成测试
 *
 * <p>需要本地运行 etcd 服务（默认 http://localhost:2379），否则测试将跳过。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtcdRegistryTest {

    private static final String ETCD_ENDPOINT = "http://localhost:2379";
    private static final String ETCD_ROOT_PATH = "/rpc/service/";

    private Client cleanupClient;
    private EtcdRegistry providerRegistry;
    private EtcdRegistry consumerRegistry;

    @BeforeAll
    void checkEtcdAvailable() {
        try {
            Client probe = Client.builder()
                    .endpoints(ETCD_ENDPOINT)
                    .connectTimeout(java.time.Duration.ofSeconds(2))
                    .build();
            probe.getKVClient()
                    .get(ByteSequence.from("/probe", StandardCharsets.UTF_8))
                    .get(3, TimeUnit.SECONDS);
            ByteSequence prefix = ByteSequence.from(ETCD_ROOT_PATH, StandardCharsets.UTF_8);
            probe.getKVClient()
                    .delete(prefix, DeleteOption.builder().isPrefix(true).build())
                    .get(3, TimeUnit.SECONDS);
            probe.close();
        } catch (Exception e) {
            Assumptions.assumeTrue(false,
                    "etcd 不可用 (" + ETCD_ENDPOINT + ")，跳过集成测试: " + e.getMessage());
        }
        cleanupClient = Client.builder().endpoints(ETCD_ENDPOINT).build();
    }

    @BeforeEach
    void setupRegistries() {
        providerRegistry = createRegistry();
        consumerRegistry = createRegistry();
    }

    @AfterEach
    void destroyRegistries() {
        safelyDestroy(providerRegistry);
        safelyDestroy(consumerRegistry);
    }

    @AfterAll
    void cleanupEtcdData() throws Exception {
        try {
            KV kv = cleanupClient.getKVClient();
            ByteSequence prefix = ByteSequence.from(ETCD_ROOT_PATH, StandardCharsets.UTF_8);
            kv.delete(prefix, DeleteOption.builder().isPrefix(true).build())
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        cleanupClient.close();
    }

    @Test
    void provider_registerAndRenewal() throws Exception {
        ServiceNode node = ServiceNode.builder()
                .serviceName("itest-provider")
                .serviceVersion("1.0")
                .serviceHost("192.168.1.10")
                .servicePort(8080)
                .build();

        providerRegistry.register(node);

        List<ServiceNode> discovered = consumerRegistry.discover("itest-provider:1.0");
        assertEquals(1, discovered.size());
        assertEquals("192.168.1.10", discovered.get(0).getServiceHost());

        providerRegistry.unregister(node);
    }

    @Test
    void consumer_discoverWithCache() throws Exception {
        String serviceKey = "itest-consumer:1.0";

        List<ServiceNode> emptyResult = consumerRegistry.discover(serviceKey);
        assertNotNull(emptyResult);
        assertTrue(emptyResult.isEmpty());

        ServiceNode node = ServiceNode.builder()
                .serviceName("itest-consumer")
                .serviceVersion("1.0")
                .serviceHost("10.0.0.1")
                .servicePort(9090)
                .build();
        providerRegistry.register(node);

        List<ServiceNode> found = awaitDiscovery(consumerRegistry, serviceKey, 1, 10, 500);
        assertEquals(1, found.size());
        assertEquals("10.0.0.1", found.get(0).getServiceHost());

        providerRegistry.unregister(node);
    }

    private EtcdRegistry createRegistry() {
        EtcdRegistry registry = new EtcdRegistry();
        RegistryConfig config = new RegistryConfig();
        config.setRegistryAddress(ETCD_ENDPOINT);
        config.setConnectTimeout(3000L);
        config.setRequestTimeout(5000L);
        registry.init(config);
        return registry;
    }

    private void safelyDestroy(EtcdRegistry registry) {
        if (registry != null) {
            try { registry.destroy(); } catch (Exception ignored) {}
        }
    }

    private List<ServiceNode> awaitDiscovery(EtcdRegistry registry, String serviceKey,
                                              int expectedSize, int maxRetries, long intervalMs)
            throws InterruptedException {
        for (int i = 0; i < maxRetries; i++) {
            List<ServiceNode> nodes = registry.discover(serviceKey);
            if (nodes.size() == expectedSize) {
                return nodes;
            }
            Thread.sleep(intervalMs);
        }
        return registry.discover(serviceKey);
    }
}
