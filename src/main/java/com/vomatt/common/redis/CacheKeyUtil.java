package com.vomatt.common.redis;

public class CacheKeyUtil {

    private static final String SEPARATOR = ":";

    public static String buildKey(String cacheName, String key) {
        if (cacheName == null || cacheName.isEmpty()) {
            throw new IllegalArgumentException("Cache name cannot be null or empty");
        }
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Key cannot be null or empty");
        }
        return cacheName + SEPARATOR + key;
    }
}
