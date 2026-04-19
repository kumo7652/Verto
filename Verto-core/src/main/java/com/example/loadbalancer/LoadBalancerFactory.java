package com.example.loadbalancer;

import com.example.extension.ExtensionLoader;

public class LoadBalancerFactory {
    static {
        ExtensionLoader.load(LoadBalancer.class);
    }

    public static LoadBalancer getLoadBalancer(String key) {
        return ExtensionLoader.getInstance(LoadBalancer.class, key);
    }
}
