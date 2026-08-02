package com.pulsar.loadbalancer;

import com.pulsar.LoadBalancer;
import com.pulsar.extension.ExtensionLoader;

public class LoadBalancerFactory {
    public static LoadBalancer getLoadBalancer(String name) {
        return ExtensionLoader.getInstance(LoadBalancer.class, name);
    }
}
