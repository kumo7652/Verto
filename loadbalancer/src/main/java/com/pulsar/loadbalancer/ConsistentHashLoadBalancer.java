package com.pulsar.loadbalancer;

import com.pulsar.extension.SpiExtension;
import com.pulsar.model.LoadBalancerContext;
import com.pulsar.model.ServiceNode;

import java.nio.charset.StandardCharsets;
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
 * 节点变更时仅影响相邻节点上的键，最小化缓存失效。</p>
 *
 * <p>缓存失效：先引用相等 O(1) 快速路径，再指纹确认。</p>
 */
@SpiExtension(name = "consistent-hash")
public class ConsistentHashLoadBalancer extends AbstractLoadBalancer {

    private static final int VIRTUAL_NODES = 128;

    private final ConcurrentHashMap<String, HashRing> rings = new ConcurrentHashMap<>();

    @Override
    protected Optional<ServiceNode> doSelect(LoadBalancerContext context, List<ServiceNode> nodes) {
        String serviceKey = context.serviceKey();
        HashRing ring = rings.get(serviceKey);

        if (ring == null || !ring.matches(nodes)) {
            ring = new HashRing(nodes);
            rings.put(serviceKey, ring);
        }

        String hashKey = extractHashKey(context);
        return Optional.ofNullable(ring.select(hashKey));
    }

    private String extractHashKey(LoadBalancerContext context) {
        Object[] args = context.arguments();
        if (args != null && args.length > 0 && args[0] != null) {
            return args[0].toString();
        }
        return null;
    }

    static class HashRing {

        private final TreeMap<Integer, ServiceNode> ring = new TreeMap<>();
        private final long fingerprint;
        private final List<ServiceNode> nodesRef;

        HashRing(List<ServiceNode> nodes) {
            for (ServiceNode node : nodes) {
                String base = node.getServiceNodeKey();
                for (int i = 0; i < VIRTUAL_NODES; i++) {
                    byte[] keyBytes = (base + "#" + i).getBytes(StandardCharsets.UTF_8);
                    ring.put(MurmurHash3.hash32(keyBytes), node);
                }
            }
            this.nodesRef = nodes;
            this.fingerprint = computeFingerprint(nodes);
        }

        boolean matches(List<ServiceNode> nodes) {
            return this.nodesRef == nodes || this.fingerprint == computeFingerprint(nodes);
        }

        ServiceNode select(String key) {
            if (ring.isEmpty()) {
                return null;
            }
            if (key == null || key.isEmpty()) {
                List<ServiceNode> values = new ArrayList<>(ring.values());
                return values.get(Math.abs((int) System.nanoTime()) % values.size());
            }

            int hash = MurmurHash3.hash32(key.getBytes(StandardCharsets.UTF_8));
            Map.Entry<Integer, ServiceNode> entry = ring.ceilingEntry(hash);
            if (entry == null) {
                entry = ring.firstEntry();
            }
            return entry.getValue();
        }
    }

    private static long computeFingerprint(List<ServiceNode> nodes) {
        List<String> keys = new ArrayList<>(nodes.size());
        for (ServiceNode node : nodes) {
            keys.add(node.getServiceNodeKey());
        }
        keys.sort(null);
        return fnv1a64(String.join(",", keys));
    }

    private static long fnv1a64(String key) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
