package com.vomattapi.infrastructure.redis;

/**
 * Utility class for generating Redis cache keys with consistent colon-separated format
 */
public class CacheKeyUtil {
    
    private static final String SEPARATOR = ":";
    
    /**
     * Build cache key by combining cache name and key with colon separator
     */
    public static String buildKey(String cacheName, String key) {
        if (cacheName == null || cacheName.isEmpty()) {
            throw new IllegalArgumentException("Cache name cannot be null or empty");
        }
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Key cannot be null or empty");
        }
        return cacheName + SEPARATOR + key;
    }
    
    /**
     * Build cache key with multiple parts separated by colons
     */
    public static String buildKey(String cacheName, String... keyParts) {
        if (cacheName == null || cacheName.isEmpty()) {
            throw new IllegalArgumentException("Cache name cannot be null or empty");
        }
        if (keyParts == null || keyParts.length == 0) {
            throw new IllegalArgumentException("Key parts cannot be null or empty");
        }
        
        StringBuilder keyBuilder = new StringBuilder(cacheName);
        for (String part : keyParts) {
            if (part == null || part.isEmpty()) {
                throw new IllegalArgumentException("Key part cannot be null or empty");
            }
            keyBuilder.append(SEPARATOR).append(part);
        }
        return keyBuilder.toString();
    }
    
    /**
     * Build pattern for Redis key matching
     */
    public static String buildPattern(String cacheName, String pattern) {
        if (cacheName == null || cacheName.isEmpty()) {
            throw new IllegalArgumentException("Cache name cannot be null or empty");
        }
        if (pattern == null) {
            pattern = "*";
        }
        return cacheName + SEPARATOR + pattern;
    }
    
    /**
     * Extract cache name from a full cache key
     */
    public static String extractCacheName(String fullKey) {
        if (fullKey == null || fullKey.isEmpty()) {
            return null;
        }
        int separatorIndex = fullKey.indexOf(SEPARATOR);
        return separatorIndex > 0 ? fullKey.substring(0, separatorIndex) : fullKey;
    }
    
    /**
     * Extract key part from a full cache key (everything after first colon)
     */
    public static String extractKey(String fullKey) {
        if (fullKey == null || fullKey.isEmpty()) {
            return null;
        }
        int separatorIndex = fullKey.indexOf(SEPARATOR);
        return separatorIndex >= 0 && separatorIndex < fullKey.length() - 1 
            ? fullKey.substring(separatorIndex + 1) 
            : fullKey;
    }
}