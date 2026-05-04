package com.pulsar.registry.etcd;

import com.pulsar.model.ServiceNode;
import io.etcd.jetcd.*;
import io.etcd.jetcd.options.DeleteOption;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EtcdWatcher 集成测试
 *
 * <p>验证 EtcdWatcher 的核心行为：
 * <ul>
 *   <li>discover：全量拉取并进入 Watch 模式</li>
 *   <li>Watch 增量更新：PUT/DELETE 事件实时反映到缓存</li>
 *   <li>discoverAsync：异步发现服务节点</li>
 *   <li>destroy：关闭所有 Watch 流并清理资源</li>
 * </ul>
 *
 * <p>需要本地运行 etcd 服务（默认 http://localhost:2379），否则测试将跳过。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtcdWatcherTest {

    private static final String ETCD_ENDPOINT = "http://localhost:2379";
    private static final long REQUEST_TIMEOUT_MS = 5_000L;

    private Client etcdClient;
    private KV verifyKv;
    private EtcdWatcher watcher;

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
            probe.close();
        } catch (Exception e) {
            Assumptions.assumeTrue(false,
                    "etcd 不可用 (" + ETCD_ENDPOINT + ")，跳过集成测试: " + e.getMessage());
        }
        etcdClient = Client.builder().endpoints(ETCD_ENDPOINT).build();
        verifyKv = etcdClient.getKVClient();
    }

    @BeforeEach
    void setup() {
        watcher = new EtcdWatcher(
                etcdClient.getKVClient(),
                etcdClient.getWatchClient(),
                REQUEST_TIMEOUT_MS
        );
    }

    @AfterEach
    void teardown() {
        if (watcher != null) {
            try { watcher.destroy(); } catch (Exception ignored) {}
        }
    }

    @AfterAll
    void cleanupEtcdData() throws Exception {
        if (etcdClient != null) {
            ByteSequence prefix = ByteSequence.from(EtcdConstants.ETCD_ROOT_PATH, StandardCharsets.UTF_8);
            etcdClient.getKVClient()
                    .delete(prefix, DeleteOption.builder().isPrefix(true).build())
                    .get(5, TimeUnit.SECONDS);
            etcdClient.close();
        }
    }

    // ===== discover：全量拉取并进入 Watch 模式 =====

    @Test
    void discover_shouldReturnNodesFromEtcd() throws Exception {
        ServiceNode node = buildNode("itest-disc", "192.168.1.1", 8080);
        putNode(node);

        List<ServiceNode> result = watcher.discover(node.getServiceKey());

        Thread.sleep(60_000L);

        assertEquals(1, result.size());
        assertEquals(node.getServiceHost(), result.get(0).getServiceHost());
        assertEquals(node.getServicePort(), result.get(0).getServicePort());
    }

    @Test
    void discover_shouldReturnEmptyListWhenNoNodes() throws Exception {
        String serviceKey = "noexist-svc:1.0";
        ensureClean(serviceKey);

        List<ServiceNode> result = watcher.discover(serviceKey);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void discover_shouldHitCacheOnSecondCall() throws Exception {
        ServiceNode node = buildNode("itest-cache", "10.0.0.1", 9090);
        putNode(node);

        watcher.discover(node.getServiceKey());

        // 删除 etcd 中的数据，缓存仍然存在
        deleteAllUnderServiceKey(node.getServiceKey());

        List<ServiceNode> result = watcher.discover(node.getServiceKey());
        assertEquals(1, result.size(), "第二次调用应命中缓存");
    }

    // ===== Watch 增量更新 =====

    @Test
    void watch_shouldDetectNewNode() throws Exception {
        String serviceKey = "itest-watch-add:1.0";
        ensureClean(serviceKey);
        watcher.discover(serviceKey);

        ServiceNode node = buildNode("itest-watch-add", "172.16.0.1", 7070);
        putNode(node);

        Thread.sleep(1000);

        List<ServiceNode> result = watcher.discover(serviceKey);
        assertEquals(1, result.size(), "Watch 应检测到新节点");
        assertEquals("172.16.0.1", result.get(0).getServiceHost());
    }

    @Test
    void watch_shouldDetectNodeRemoval() throws Exception {
        ServiceNode node = buildNode("itest-watch-del", "172.16.0.2", 7071);
        putNode(node);
        watcher.discover(node.getServiceKey());

        deleteNode(node);

        Thread.sleep(1000);

        List<ServiceNode> result = watcher.discover(node.getServiceKey());
        assertTrue(result.isEmpty(), "Watch 应检测到节点删除");
    }

    @Test
    void watch_shouldDetectNodeUpdate() throws Exception {
        ServiceNode node = buildNode("itest-watch-upd", "172.16.0.3", 7072);
        putNode(node);
        watcher.discover(node.getServiceKey());

        ServiceNode updated = ServiceNode.builder()
                .serviceName("itest-watch-upd")
                .serviceVersion("1.0")
                .serviceHost("172.16.0.3")
                .servicePort(7072)
                .weight(50)
                .build();
        putNode(updated);

        Thread.sleep(1000);

        List<ServiceNode> result = watcher.discover(node.getServiceKey());
        assertEquals(1, result.size());
        assertEquals(50, result.get(0).getWeight(), "Watch 应检测到节点更新");
    }

    // ===== discoverAsync =====

    @Test
    void discoverAsync_shouldReturnNodes() throws Exception {
        ServiceNode node = buildNode("itest-async", "10.0.0.5", 6060);
        putNode(node);

        List<ServiceNode> result = watcher.discoverAsync(node.getServiceKey())
                .get(5, TimeUnit.SECONDS);

        assertEquals(1, result.size());
        assertEquals(node.getServiceHost(), result.get(0).getServiceHost());
    }

    // ===== destroy =====

    @Test
    void destroy_shouldCleanupWithoutError() throws Exception {
        ServiceNode node = buildNode("itest-destroy", "10.0.0.10", 5050);
        putNode(node);
        watcher.discover(node.getServiceKey());

        assertDoesNotThrow(() -> watcher.destroy());
    }

    // ===== 辅助方法 =====

    private ServiceNode buildNode(String serviceName, String host, int port) {
        return ServiceNode.builder()
                .serviceName(serviceName)
                .serviceVersion("1.0")
                .serviceHost(host)
                .servicePort(port)
                .build();
    }

    private ByteSequence toEtcdKey(ServiceNode node) {
        return ByteSequence.from(
                EtcdConstants.ETCD_ROOT_PATH + node.getServiceNodeKey(),
                StandardCharsets.UTF_8);
    }

    private ByteSequence toEtcdValue(ServiceNode node) {
        return ByteSequence.from(
                cn.hutool.json.JSONUtil.toJsonStr(node),
                StandardCharsets.UTF_8);
    }

    private void putNode(ServiceNode node) throws Exception {
        verifyKv.put(toEtcdKey(node), toEtcdValue(node))
                .get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void deleteNode(ServiceNode node) throws Exception {
        verifyKv.delete(toEtcdKey(node))
                .get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void ensureClean(String serviceKey) throws Exception {
        ByteSequence prefix = ByteSequence.from(
                EtcdConstants.ETCD_ROOT_PATH + serviceKey + "/",
                StandardCharsets.UTF_8);
        verifyKv.delete(prefix, DeleteOption.builder().isPrefix(true).build())
                .get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void deleteAllUnderServiceKey(String serviceKey) throws Exception {
        ensureClean(serviceKey);
    }
}
