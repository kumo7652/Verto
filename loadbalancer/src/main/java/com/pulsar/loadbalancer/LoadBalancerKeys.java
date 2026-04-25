package com.pulsar.loadbalancer;

public final class LoadBalancerKeys {
    private LoadBalancerKeys() {}

    public static final String RANDOM = "random";
    public static final String ROUND_ROBIN = "round-robin";
    public static final String WEIGHTED_RANDOM = "weighted-random";
    public static final String WEIGHTED_ROUND_ROBIN = "weighted-round-robin";
    public static final String CONSISTENT_HASH = "consistent-hash";
}
