package com.vomattapi.infrastructure.redis;

import java.time.Duration;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Redis 緩存工具類
 * 提供便捷的緩存操作方法，支援緩存穿透保護
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheUtil {
    private final RedisService redisService;

    // 默認緩存時間
    private static final Duration DEFAULT_CACHE_DURATION = Duration.ofMinutes(30);
    private static final Duration SHORT_CACHE_DURATION = Duration.ofMinutes(5);
    private static final Duration LONG_CACHE_DURATION = Duration.ofHours(2);

    /**
     * 獲取或設置緩存
     * 如果緩存不存在，執行提供的函數並緩存結果
     */
    public <T> T getOrSet(String key, Class<T> clazz, Supplier<T> supplier) {
        return getOrSet(key, clazz, supplier, DEFAULT_CACHE_DURATION);
    }

    /**
     * 獲取或設置緩存（指定過期時間）
     */
    public <T> T getOrSet(String key, Class<T> clazz, Supplier<T> supplier, Duration timeout) {
        try {
            // 先嘗試從緩存獲取
            T cachedValue = redisService.get("cache", key, clazz);
            if (cachedValue != null) {
                log.debug("Cache hit: key={}", key);
                return cachedValue;
            }

            // 緩存未命中，執行供應商函數
            log.debug("Cache miss: key={}", key);
            T value = supplier.get();

            if (value != null) {
                // 緩存結果
                redisService.set("cache", key, value, timeout);
                log.debug("Cache set: key={}, timeout={}", key, timeout);
            }

            return value;
        } catch (Exception e) {
            log.error("Cache operation error: key={}, error={}", key, e.getMessage(), e);
            // 發生錯誤時直接執行供應商函數
            return supplier.get();
        }
    }

    /**
     * 刪除緩存
     */
    public boolean evict(String key) {
        try {
            boolean result = redisService.delete("cache", key);
            log.debug("Cache evict: key={}, result={}", key, result);
            return result;
        } catch (Exception e) {
            log.error("Cache evict error: key={}, error={}", key, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 按模式刪除緩存
     */
    public long evictByPattern(String pattern) {
        try {
            long result = redisService.deleteByPattern("cache", pattern);
            log.debug("Cache evict by pattern: pattern={}, count={}", pattern, result);
            return result;
        } catch (Exception e) {
            log.error("Cache evict by pattern error: pattern={}, error={}", pattern, e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 緩存用戶數據（短時間）
     */
    public <T> T cacheUserData(String userId, String dataType, Class<T> clazz, Supplier<T> supplier) {
        String key = buildUserCacheKey(userId, dataType);
        return getOrSet(key, clazz, supplier, SHORT_CACHE_DURATION);
    }

    /**
     * 緩存配置數據（長時間）
     */
    public <T> T cacheConfigData(String configKey, Class<T> clazz, Supplier<T> supplier) {
        String key = buildConfigCacheKey(configKey);
        return getOrSet(key, clazz, supplier, LONG_CACHE_DURATION);
    }

    /**
     * 緩存查詢結果（默認時間）
     */
    public <T> T cacheQueryResult(String queryKey, Class<T> clazz, Supplier<T> supplier) {
        String key = buildQueryCacheKey(queryKey);
        return getOrSet(key, clazz, supplier, DEFAULT_CACHE_DURATION);
    }

    /**
     * 刪除用戶相關的所有緩存
     */
    public long evictUserCache(String userId) {
        String pattern = buildUserCacheKey(userId, "*");
        return evictByPattern(pattern);
    }

    /**
     * 刷新緩存（先刪除再設置）
     */
    public <T> T refreshCache(String key, Class<T> clazz, Supplier<T> supplier, Duration timeout) {
        evict(key);
        return getOrSet(key, clazz, supplier, timeout);
    }

    // ==================== 私有輔助方法 ====================

    private String buildUserCacheKey(String userId, String dataType) {
        return String.format("user:%s:%s", userId, dataType);
    }

    private String buildConfigCacheKey(String configKey) {
        return String.format("config:%s", configKey);
    }

    private String buildQueryCacheKey(String queryKey) {
        return String.format("query:%s", queryKey);
    }

    // ==================== 靜態緩存鍵常量 ====================

    public static class CacheKeys {
        public static final String USER_PROFILE = "profile";
        public static final String USER_PERMISSIONS = "permissions";
        public static final String USER_SETTINGS = "settings";
        public static final String USER_VOTES = "votes";
        public static final String USER_ACTIVITIES = "activities";

        public static final String VOTE_DETAILS = "vote:details";
        public static final String VOTE_RESULTS = "vote:results";
        public static final String VOTE_STATISTICS = "vote:stats";

        public static final String SYSTEM_CONFIG = "system:config";
        public static final String RATE_LIMIT = "rate:limit";
    }
}
