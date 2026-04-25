package com.pulsar.loadbalancer;

import com.pulsar.extension.SpiExtension;
import com.pulsar.model.LoadBalancerContext;
import com.pulsar.model.ServiceNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h3>一致性哈希负载均衡器</h3>
 *
 * <p>将请求按哈希键映射到固定节点，同一键始终路由到同一节点。
 * 节点变更时仅影响相邻节点上的键，最小化缓存失效和会话丢失。</p>
 *
 * <p>哈希键提取：默认取 {@link LoadBalancerContext#arguments()} 的第一个参数，
 * 无参时退化为随机选择。</p>
 *
 * <p>虚拟节点：每个物理节点创建 128 个副本，解决节点数少时负载不均的问题。</p>
 */
@SpiExtension(name = "consistent-hash")
public class ConsistentHashLoadBalancer implements LoadBalancer {

    private static final int VIRTUAL_NODES = 128;

    private final ConcurrentHashMap<String, HashRing> rings = new ConcurrentHashMap<>();

    private static final ThreadLocal<MessageDigest> MD5 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    });

    /**
     * 从节点列表中选出一个节点。
     *
     * <p>先检查当前哈希环是否与节点列表一致（通过指纹比对），不一致则重建环。
     * 然后从上下文中提取哈希键，在环上顺时针查找命中节点。</p>
     *
     * @param context 负载均衡上下文，提供 serviceKey 和请求参数
     * @param nodes   可用服务节点列表
     * @return 选中的节点，节点列表为空时返回 {@link Optional#empty()}
     */
    @Override
    public Optional<ServiceNode> select(LoadBalancerContext context, List<ServiceNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return Optional.empty();
        }
        if (nodes.size() == 1) {
            return Optional.of(nodes.get(0));
        }

        String serviceKey = context.serviceKey();
        HashRing ring = rings.get(serviceKey);

        // 节点列表变更时重建哈希环
        if (ring == null || !ring.matches(nodes)) {
            ring = new HashRing(nodes);
            rings.put(serviceKey, ring);
        }

        String hashKey = extractHashKey(context);
        return Optional.ofNullable(ring.select(hashKey));
    }

    /**
     * 从调用上下文中提取用于哈希的路由键。
     *
     * <p>默认取 {@code arguments} 数组的第一个元素并调用其 {@code toString()}。
     * 若无参或首参为 null 则返回 null，由哈希环降级为随机选择。</p>
     *
     * @param context 负载均衡上下文
     * @return 哈希键字符串，可能为 null
     */
    private String extractHashKey(LoadBalancerContext context) {
        Object[] args = context.arguments();
        if (args != null && args.length > 0 && args[0] != null) {
            return args[0].toString();
        }
        return null;
    }

    /**
     * 哈希环，通过 TreeMap 实现 O(log n) 的顺时针节点查找。
     *
     * <p>每个物理节点创建 {@link #VIRTUAL_NODES} 个虚拟副本，
     * 以节点唯一标识后追加序号作为虚拟节点的哈希输入，保证不同节点间
     * 虚拟节点的哈希值均匀分散在整个环上。</p>
     *
     * <p>通过指纹（节点列表排序后的 FNV-1a 哈希）快速判断节点列表是否变更，
     * 避免每次调用都全量重建环。</p>
     */
    static class HashRing {

        private final TreeMap<Integer, ServiceNode> ring = new TreeMap<>();
        private final long fingerprint;

        /**
         * 根据节点列表构造哈希环。
         *
         * @param nodes 可用服务节点列表
         */
        HashRing(List<ServiceNode> nodes) {
            for (ServiceNode node : nodes) {
                for (int i = 0; i < VIRTUAL_NODES; i++) {
                    int hash = hash(node.getServiceNodeKey() + "#" + i);
                    ring.put(hash, node);
                }
            }
            this.fingerprint = computeFingerprint(nodes);
        }

        /**
         * 判读当前哈希环是否与给定的节点列表匹配。
         *
         * @param nodes 当前可用节点列表
         * @return true 表示节点未变更，环无需重建
         */
        boolean matches(List<ServiceNode> nodes) {
            return this.fingerprint == computeFingerprint(nodes);
        }

        /**
         * 根据哈希键在环上顺时针查找命中的物理节点。
         *
         * <p>查找逻辑：对 key 取哈希，在 TreeMap 中找第一个哈希值 ≥ 该哈希的
         * 虚拟节点（{@link TreeMap#ceilingEntry}）；若哈希值超出环的最大值则
         * 回绕到环首（{@link TreeMap#firstEntry}）。key 为 null 或空时降级为
         * 伪随机选择，避免所有无参请求集中到一个节点。</p>
         *
         * @param key 哈希键，可为 null
         * @return 命中的物理节点，环为空时返回 null
         */
        ServiceNode select(String key) {
            if (ring.isEmpty()) {
                return null;
            }
            if (key == null || key.isEmpty()) {
                List<ServiceNode> values = new ArrayList<>(ring.values());
                return values.get(Math.abs((int) System.nanoTime()) % values.size());
            }

            int hash = hash(key);
            Map.Entry<Integer, ServiceNode> entry = ring.ceilingEntry(hash);
            if (entry == null) {
                entry = ring.firstEntry();
            }
            return entry.getValue();
        }
    }

    /**
     * 计算节点列表的指纹，用于快速判断节点是否变更。
     *
     * <p>取所有节点的 {@code serviceNodeKey}，字典序排序后以逗号拼接，
     * 对拼接串计算 FNV-1a 64 位哈希。排序保证节点顺序不影响指纹一致性。</p>
     *
     * @param nodes 服务节点列表
     * @return 64 位指纹
     */
    private static long computeFingerprint(List<ServiceNode> nodes) {
        List<String> keys = new ArrayList<>(nodes.size());
        for (ServiceNode node : nodes) {
            keys.add(node.getServiceNodeKey());
        }
        keys.sort(null);
        return fnv1a64(String.join(",", keys));
    }

    /**
     * 对字符串取 MD5，截取低 32 位作为 int 型哈希值。
     *
     * <p>使用 {@link ThreadLocal} 缓存 MessageDigest 实例，
     * 避免每次调用都创建新实例并保证线程安全。</p>
     *
     * @param key 待哈希字符串
     * @return 32 位哈希值
     */
    private static int hash(String key) {
        byte[] digest = MD5.get().digest(key.getBytes(StandardCharsets.UTF_8));
        return ((digest[3] & 0xFF) << 24)
             | ((digest[2] & 0xFF) << 16)
             | ((digest[1] & 0xFF) << 8)
             |  (digest[0] & 0xFF);
    }

    /**
     * FNV-1a 64 位哈希，用于计算节点列表指纹。
     *
     * <p>FNV-1a 算法：逐字节异或后乘以固定质数，速度快且碰撞率低，
     * 适合非加密用途的快速哈希场景。</p>
     *
     * @param key 待哈希字符串
     * @return 64 位哈希值
     */
    private static long fnv1a64(String key) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
