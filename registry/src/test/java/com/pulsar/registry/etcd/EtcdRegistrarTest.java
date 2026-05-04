package com.pulsar.registry.etcd;

import com.pulsar.model.ServiceNode;
import io.etcd.jetcd.*;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.lease.LeaseTimeToLiveResponse;
import io.etcd.jetcd.options.DeleteOption;
import io.etcd.jetcd.options.LeaseOption;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EtcdRegistrar 集成测试
 *
 * <p>每个测试方法仅验证 EtcdRegistrar 中一个方法的职责：
 * <ul>
 *   <li>register：获取租约上下文并将节点信息写入</li>
 *   <li>unregister：关闭续约流并删除节点对应的键</li>
 *   <li>destroy：关闭所有续约流、撤销租约并清理资源</li>
 * </ul>
 *
 * <p>需要本地运行 etcd 服务（默认 http://localhost:2379），否则测试将跳过。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtcdRegistrarTest {

    private static final String ETCD_ENDPOINT = "http://localhost:2379";
    private static final long REQUEST_TIMEOUT_MS = 5_000L;

    private Client etcdClient;
    private KV verifyKv;
    private Lease verifyLease;
    private EtcdRegistrar registrar;

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
        verifyLease = etcdClient.getLeaseClient();
    }

    @BeforeEach
    void setup() {
        registrar = new EtcdRegistrar(
                etcdClient.getKVClient(),
                etcdClient.getLeaseClient(),
                REQUEST_TIMEOUT_MS
        );
    }

    @AfterEach
    void teardown() {
        if (registrar != null) {
            try { registrar.destroy(); } catch (Exception ignored) {}
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

    // ===== register：获取租约上下文并将节点信息写入 =====

    @Test
    void register_shouldWriteKeyWithLease() throws Exception {
        ServiceNode node = buildNode("itest-reg", "192.168.1.1", 8080);
        registrar.register(node);

        Thread.sleep(600_000L);

        ByteSequence key = toEtcdKey(node.getServiceNodeKey());
        GetResponse resp = verifyKv.get(key).get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        assertEquals(1, resp.getCount(), "注册后 key 应存在");

        long leaseId = resp.getKvs().get(0).getLease();
        assertNotEquals(0, leaseId, "key 应绑定租约");

        String value = resp.getKvs().get(0).getValue().toString(StandardCharsets.UTF_8);
        assertTrue(value.contains("itest-reg"), "value 应包含 serviceName");
        assertTrue(value.contains("192.168.1.1"), "value 应包含 serviceHost");

        // 租约应存活（TTL > 0），证明 lease 已被 grant 且 keepAlive 流已启动
        LeaseTimeToLiveResponse ttl = verifyLease.timeToLive(leaseId, LeaseOption.DEFAULT)
                .get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertTrue(ttl.getTTL() > 0, "租约应存活");
    }

    // ===== unregister：关闭续约流并删除节点对应的键 =====

    @Test
    void unregister_shouldDeleteKey() throws Exception {
        ServiceNode node = buildNode("itest-unreg", "172.16.0.1", 7070);
        registrar.register(node);

        ByteSequence key = toEtcdKey(node.getServiceNodeKey());

        // 注销前 key 存在
        assertEquals(1, verifyKv.get(key).get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS).getCount());

        registrar.unregister(node);

        // 注销后 key 已删除
        assertEquals(0, verifyKv.get(key).get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS).getCount(),
                "注销后 key 应被删除");
    }

    // ===== destroy：关闭所有续约流、撤销租约并清理资源 =====

    @Test
    void destroy_shouldRevokeAllLeases() throws Exception {
        ServiceNode node1 = buildNode("itest-dest-a", "10.0.0.1", 8080);
        ServiceNode node2 = buildNode("itest-dest-b", "10.0.0.2", 8081);

        registrar.register(node1);
        registrar.register(node2);

        long leaseId1 = getLeaseId(node1.getServiceNodeKey());
        long leaseId2 = getLeaseId(node2.getServiceNodeKey());

        registrar.destroy();

        // leaseClient.revoke 是异步的，轮询等待撤销生效
        awaitLeaseRevoked(leaseId1);
        awaitLeaseRevoked(leaseId2);
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

    private ByteSequence toEtcdKey(String nodeKey) {
        return ByteSequence.from(EtcdConstants.ETCD_ROOT_PATH + nodeKey, StandardCharsets.UTF_8);
    }

    private long getLeaseId(String nodeKey) throws Exception {
        ByteSequence key = toEtcdKey(nodeKey);
        GetResponse resp = verifyKv.get(key).get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertEquals(1, resp.getCount(), "获取 leaseId 时 key 应存在: " + nodeKey);
        return resp.getKvs().get(0).getLease();
    }

    private void awaitLeaseRevoked(long leaseId) throws Exception {
        for (int i = 0; i < 10; i++) {
            LeaseTimeToLiveResponse resp = verifyLease.timeToLive(leaseId, LeaseOption.DEFAULT)
                    .get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (resp.getTTL() == 0) return;
            Thread.sleep(500);
        }
        fail("租约 " + leaseId + " 未在超时内被撤销");
    }
}
