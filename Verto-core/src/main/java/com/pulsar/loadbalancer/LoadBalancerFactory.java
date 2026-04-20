package com.pulsar.loadbalancer;

import com.pulsar.extension.ExtensionLoader;

public class LoadBalancerFactory {
    static {
        ExtensionLoader.load(LoadBalancer.class);
    }

    public static LoadBalancer getLoadBalancer(String key) {
        return ExtensionLoader.getInstance(LoadBalancer.class, key);
    }
}
