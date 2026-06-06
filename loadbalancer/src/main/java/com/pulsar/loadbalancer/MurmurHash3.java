package com.pulsar.loadbalancer;

/**
 * <h3>MurmurHash3 32bit 实现</h3>
 * 用于一致性哈希虚拟节点的哈希计算，参考 Dubbo MurmurHash3。
 */
final class MurmurHash3 {

    private MurmurHash3() {}

    static int hash32(byte[] data) {
        int len = data.length;
        int h = 0;
        int c1 = 0xcc9e2d51;
        int c2 = 0x1b873593;

        int i = 0;
        while (i + 4 <= len) {
            int k = (data[i] & 0xFF)
                  | ((data[i + 1] & 0xFF) << 8)
                  | ((data[i + 2] & 0xFF) << 16)
                  | ((data[i + 3] & 0xFF) << 24);
            i += 4;

            k *= c1;
            k = Integer.rotateLeft(k, 15);
            k *= c2;

            h ^= k;
            h = Integer.rotateLeft(h, 13);
            h = h * 5 + 0xe6546b64;
        }

        int k = 0;
        int tail = len & 3;
        if (tail >= 3) k ^= (data[i + 2] & 0xFF) << 16;
        if (tail >= 2) k ^= (data[i + 1] & 0xFF) << 8;
        if (tail >= 1) {
            k ^= (data[i] & 0xFF);
            k *= c1;
            k = Integer.rotateLeft(k, 15);
            k *= c2;
            h ^= k;
        }

        h ^= len;
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }
}
