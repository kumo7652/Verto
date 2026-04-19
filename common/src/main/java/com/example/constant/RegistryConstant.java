package com.example.constant;

public final class RegistryConstant {
    private RegistryConstant() {}

    public static final String ETCD_ROOT_PATH = "/rpc/";
    public static final long DEFAULT_LEASE_TTL = 30L;
    public static final int RECONNECT_DELAY_SECONDS = 2;
    public static final int WATCH_RECONNECT_DELAY_SECONDS = 5;
}
