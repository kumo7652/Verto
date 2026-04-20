package com.pulsar.utils;

public final class Assert {
    private Assert() {}

    public static void notNull(Object obj, String message) {
        if (obj == null) {
            throw new IllegalArgumentException(message);
        }
    }

    public static void isTrue(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    public static void notEmpty(String str, String message) {
        if (str == null || str.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }

    public static void notEmpty(Object[] arr, String message) {
        if (arr == null || arr.length == 0) {
            throw new IllegalArgumentException(message);
        }
    }
}