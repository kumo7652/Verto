package com.pulsar.registry.etcd;

import com.pulsar.exception.RegistryException;
import com.pulsar.exception.RpcErrorCode;
import com.pulsar.model.ServiceNode;
import com.pulsar.registry.ServiceListener;
import com.pulsar.registry.config.RegistryConfig;
import com.pulsar.utils.ThreadPoolBuilder;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.options.DeleteOption;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EtcdRegistry 集成测试
 * <p>需要本地运行 etcd 服务（默认 http://localhost:2379），否则测试将跳过。
 * <p>每个测试创建独立的 EtcdRegistry 实例，测试间通过唯一服务名隔离。
 * 测试结束后统一清理 etcd 中写入的数据。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EtcdRegistryTest {

    private static final String ETCD_ENDPOINT = "http://localhost:2379";
    private static final String ETCD_ROOT_PATH = "/rpc/service/";

    /** 收集所有测试创建的 serviceKey，用于 @AfterAll 统一清理 etcd 数据 */
    private final Set<String> createdServiceKeys = ConcurrentHashMap.newKeySet();

    /** 当前测试使用的 registry 实例 */
    private EtcdRegistry registry;

    /** 用于清理 etcd 数据的独立客户端 */
    private Client cleanupClient;

    @BeforeAll
    void checkEtcdAvailable() {
        try {
            Client probe = Client.builder()
                    .endpoints(ETCD_ENDPOINT)
                    .connectTimeout(java.time.Duration.ofSeconds(2))
                    .build();
            probe.getKVClient().get(ByteSequence.from("/probe", StandardCharsets.UTF_8))
                    .get(3, TimeUnit.SECONDS);
            // 清理上次测试残留数据
            ByteSequence prefix = ByteSequence.from(ETCD_ROOT_PATH, StandardCharsets.UTF_8);
            probe.getKVClient().delete(prefix,
                    DeleteOption.builder().isPrefix(true).build()).get(3, TimeUnit.SECONDS);
            probe.close();
        } catch (Exception e) {
            Assumptions.assumeTrue(false,
                    "etcd 不可用 (" + ETCD_ENDPOINT + ")，跳过集成测试: " + e.getMessage());
        }
        cleanupClient = Client.builder().endpoints(ETCD_ENDPOINT).build();
    }
//
//    @AfterAll
//    void cleanupEtcdData() throws Exception {
//        KV kv = cleanupClient.getKVClient();
//        for (String serviceKey : createdServiceKeys) {
//            try {
//                ByteSequence prefix = ByteSequence.from(
//                        ETCD_ROOT_PATH + serviceKey + "/", StandardCharsets.UTF_8);
//                kv.delete(prefix, DeleteOption.builder().isPrefix(true).build())
//                        .get(3, TimeUnit.SECONDS);
//            } catch (Exception ignored) {
//            }
//        }
//        cleanupClient.close();
//    }

    @BeforeEach
    void createNewRegistry() {
        registry = new EtcdRegistry();
        RegistryConfig config = new RegistryConfig();
        config.setRegistryAddress(ETCD_ENDPOINT);
        config.setConnectTimeout(3000L);
        config.setRequestTimeout(5000L);
        registry.init(config);
    }

//    @AfterEach
//    void destroyRegistry() {
//        if (registry != null) {
//            try {
//                registry.destroy();
//            } catch (Exception ignored) {
//            }
//        }
//        // 关闭 reconnect-executor 线程池，避免下一个测试创建同名池时报错
//        ThreadPoolBuilder.shutdown("reconnect-executor");
//    }

    // ==================== register ====================

    @Test
    @Order(1)
    void register_shouldAssignNodeIdAndPersistToEtcd() throws InterruptedException {
        ServiceNode node = buildNode("itest-register", "1.0", "192.168.1.10", 8080);
        track("itest-register:1.0");

        registry.register(node);

        assertNotNull(node.getNodeId());
        assertEquals("itest-register-001", node.getNodeId());

        List<ServiceNode> found = registry.discover("itest-register:1.0");
        assertEquals(1, found.size());
        assertEquals("192.168.1.10", found.get(0).getServiceHost());
        assertEquals(8080, found.get(0).getServicePort());

        // 暂停 30 秒，方便在 etcd 中查看注册数据（key: /rpc/service/itest-register:1.0/itest-register-001）
        // 可用命令验证: etcdctl get /rpc/service/itest-register:1.0/ --prefix
        Thread.sleep(30_000_000_0);
    }

    @Test
    @Order(2)
    void register_multipleNodesSameService_incrementsNodeId() {
        track("itest-multi:1.0");
        ServiceNode node1 = buildNode("itest-multi", "1.0", "192.168.1.10", 8080);
        ServiceNode node2 = buildNode("itest-multi", "1.0", "192.168.1.11", 8081);

        registry.register(node1);
        registry.register(node2);

        assertEquals("itest-multi-001", node1.getNodeId());
        assertEquals("itest-multi-002", node2.getNodeId());

        // 直接查询 etcd 验证，避免 registry 内部 gRPC 连接时序问题
        List<ServiceNode> found = queryEtcdDirectly("itest-multi:1.0");
        assertEquals(2, found.size());
    }

    @Test
    @Order(3)
    void register_differentServices_independentNodeIdCounters() {
        track("itest-order:1.0");
        track("itest-user:2.0");

        ServiceNode orderNode = buildNode("itest-order", "1.0", "10.0.0.1", 8080);
        ServiceNode userNode = buildNode("itest-user", "2.0", "10.0.0.2", 9090);

        registry.register(orderNode);
        registry.register(userNode);

        assertEquals("itest-order-001", orderNode.getNodeId());
        assertEquals("itest-user-001", userNode.getNodeId());
    }

    @Test
    @Order(4)
    void discover_blankServiceKey_throwsRegistryException() {
        RegistryException ex = assertThrows(RegistryException.class, () -> registry.discover(""));
        assertEquals(RpcErrorCode.DISCOVERY_FAILED.getCode(), ex.getCode());

        assertThrows(RegistryException.class, () -> registry.discover(null));
    }

    // ==================== unregister ====================

    @Test
    @Order(10)
    void unregister_removesNodeFromEtcd() {
        track("itest-unreg:1.0");
        ServiceNode node = buildNode("itest-unreg", "1.0", "192.168.1.10", 8080);
        registry.register(node);

        List<ServiceNode> before = registry.discover("itest-unreg:1.0");
        assertEquals(1, before.size());

        registry.unregister(node);

        // 使用新 registry 实例查询以绕过缓存
        List<ServiceNode> after = queryEtcdDirectly("itest-unreg:1.0");
        assertTrue(after.isEmpty(), "注销后应无法发现该节点");
    }

    @Test
    @Order(11)
    void unregister_noLeaseContext_stillDeletesFromEtcd() throws Exception {
        track("itest-unreg-nolc:1.0");
        ServiceNode node = buildNode("itest-unreg-nolc", "1.0", "192.168.1.10", 8080);
        registry.register(node);

        // 手动移除 nodeLeases 中的条目，模拟没有 LeaseContext
        @SuppressWarnings("unchecked")
        Map<String, Object> nodeLeases = (Map<String, Object>) getField("nodeLeases");
        nodeLeases.remove(node.getServiceNodeKey());

        assertDoesNotThrow(() -> registry.unregister(node));

        List<ServiceNode> after = queryEtcdDirectly("itest-unreg-nolc:1.0");
        assertTrue(after.isEmpty());
    }

    // ==================== discover ====================

    @Test
    @Order(20)
    void discover_returnsRegisteredNodes() {
        track("itest-disc:1.0");
        ServiceNode node = buildNode("itest-disc", "1.0", "10.0.0.1", 8080);
        registry.register(node);

        List<ServiceNode> result = registry.discover("itest-disc:1.0");

        assertEquals(1, result.size());
        assertEquals("itest-disc", result.get(0).getServiceName());
        assertEquals("10.0.0.1", result.get(0).getServiceHost());
    }

    @Test
    @Order(21)
    void discover_cacheHit_returnsSameResult() {
        track("itest-cache:1.0");
        ServiceNode node = buildNode("itest-cache", "1.0", "10.0.0.1", 8080);
        registry.register(node);

        List<ServiceNode> first = registry.discover("itest-cache:1.0");
        assertEquals(1, first.size());

        List<ServiceNode> second = registry.discover("itest-cache:1.0");
        assertEquals(1, second.size());
        assertEquals(first.get(0).getServiceHost(), second.get(0).getServiceHost());
    }

    @Test
    @Order(22)
    void discover_returnsEmptyListWhenNoNodes() {
        track("itest-empty:1.0");

        List<ServiceNode> result = registry.discover("itest-empty:1.0");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @Order(23)
    void discover_returnsDefensiveCopy() {
        track("itest-copy:1.0");
        ServiceNode node = buildNode("itest-copy", "1.0", "10.0.0.1", 8080);
        registry.register(node);

        List<ServiceNode> result1 = registry.discover("itest-copy:1.0");
        List<ServiceNode> result2 = registry.discover("itest-copy:1.0");

        assertNotSame(result1, result2, "每次 discover 应返回新副本");
    }

    @Test
    @Order(24)
    void discover_watchEstablishedOnFirstQuery() throws Exception {
        track("itest-watch:1.0");

        registry.discover("itest-watch:1.0");

        @SuppressWarnings("unchecked")
        Map<String, Object> watchers = (Map<String, Object>) getField("serviceWatchers");
        assertTrue(watchers.containsKey("itest-watch:1.0"), "首次 discover 应建立 watch");
    }

    // ==================== subscribe ====================

    @Test
    @Order(30)
    void subscribe_receivesNodeAddedEvent() {
        track("itest-sub:1.0");

        registry.subscribe("itest-sub:1.0", e -> {});

        ServiceNode node = buildNode("itest-sub", "1.0", "10.0.0.5", 8080);
        registry.register(node);

        List<ServiceNode> cached = registry.discover("itest-sub:1.0");
        assertEquals(1, cached.size(), "订阅的服务应发现新注册的节点");
    }

    @Test
    @Order(31)
    void subscribe_multipleListeners_sameService() throws Exception {
        track("itest-multisub:1.0");

        registry.subscribe("itest-multisub:1.0", e -> {});
        registry.subscribe("itest-multisub:1.0", e -> {});

        @SuppressWarnings("unchecked")
        Map<String, Set<ServiceListener>> listenersMap =
                (Map<String, Set<ServiceListener>>) getField("listeners");
        assertEquals(2, listenersMap.get("itest-multisub:1.0").size());

        ServiceNode node = buildNode("itest-multisub", "1.0", "10.0.0.1", 8080);
        registry.register(node);

        List<ServiceNode> found = registry.discover("itest-multisub:1.0");
        assertEquals(1, found.size());
    }

    // ==================== getServices ====================

    @Test
    @Order(40)
    void getServices_returnsSubscribedServiceKeys() {
        track("itest-gs-order:1.0");
        track("itest-gs-user:2.0");

        registry.subscribe("itest-gs-order:1.0", e -> {});
        registry.subscribe("itest-gs-user:2.0", e -> {});

        Set<String> services = registry.getServices();
        assertEquals(2, services.size());
        assertTrue(services.contains("itest-gs-order:1.0"));
        assertTrue(services.contains("itest-gs-user:2.0"));
    }

    @Test
    @Order(41)
    void getServices_emptyWhenNoSubscriptions() {
        Set<String> services = registry.getServices();
        assertTrue(services.isEmpty());
    }

    // ==================== destroy ====================

    @Test
    @Order(50)
    void destroy_cleansUpAllResources() {
        track("itest-destroy:1.0");
        ServiceNode node = buildNode("itest-destroy", "1.0", "192.168.1.10", 8080);
        registry.register(node);
        registry.subscribe("itest-destroy:1.0", e -> {});

        assertDoesNotThrow(() -> registry.destroy());
        // 标记为 null，避免 @AfterEach 再次 destroy
        registry = null;
    }

    // ==================== helper methods ====================

    private ServiceNode buildNode(String name, String version, String host, int port) {
        return ServiceNode.builder()
                .serviceName(name)
                .serviceVersion(version)
                .serviceHost(host)
                .servicePort(port)
                .build();
    }

    private void track(String serviceKey) {
        createdServiceKeys.add(serviceKey);
    }

    /** 绕过缓存，直接用 cleanupClient 从 etcd 查询 */
    private List<ServiceNode> queryEtcdDirectly(String serviceKey) {
        try {
            KV kv = cleanupClient.getKVClient();
            ByteSequence prefix = ByteSequence.from(
                    ETCD_ROOT_PATH + serviceKey + "/", StandardCharsets.UTF_8);
            var getResp = kv.get(prefix,
                    io.etcd.jetcd.options.GetOption.builder().isPrefix(true).build())
                    .get(5, TimeUnit.SECONDS);
            return getResp.getKvs().stream()
                    .map(kv1 -> cn.hutool.json.JSONUtil.toBean(
                            kv1.getValue().toString(StandardCharsets.UTF_8),
                            ServiceNode.class))
                    .toList();
        } catch (Exception e) {
            throw new RuntimeException("直接查询 etcd 失败", e);
        }
    }

    private Object getField(String fieldName) throws Exception {
        Field field = registry.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(registry);
    }
}
