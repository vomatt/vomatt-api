package com.vomattapi.infrastructure.redis;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Redis 健康檢查和監控服務
 * 提供 Redis 連接狀態監控和性能指標
 */
@Slf4j
@Component
public class RedisHealthService implements HealthIndicator {

    @Autowired
    private RedisService redisService;

    private static final String HEALTH_CHECK_KEY = "health:check";
    private static final String HEALTH_CHECK_VALUE = "ok";

    @Override
    public Health health() {
        try {
            // 執行基本的讀寫測試
            String testKey = HEALTH_CHECK_KEY + ":" + System.currentTimeMillis();

            // 測試寫入
            redisService.set(testKey, HEALTH_CHECK_VALUE, Duration.ofSeconds(10));

            // 測試讀取
            String value = redisService.get(testKey, String.class);

            // 清理測試數據
            redisService.delete(testKey);

            if (HEALTH_CHECK_VALUE.equals(value)) {
                return Health.up()
                    .withDetail("redis", "Available")
                    .withDetail("timestamp", LocalDateTime.now())
                    .build();
            } else {
                return Health.down()
                    .withDetail("redis", "Read/Write test failed")
                    .withDetail("expected", HEALTH_CHECK_VALUE)
                    .withDetail("actual", value)
                    .build();
            }
        } catch (Exception e) {
            log.error("Redis health check failed", e);
            return Health.down()
                .withDetail("redis", "Unavailable")
                .withDetail("error", e.getMessage())
                .withDetail("timestamp", LocalDateTime.now())
                .build();
        }
    }

    /**
     * 獲取 Redis 統計信息
     */
    public RedisStats getRedisStats() {
        try {
            RedisStats stats = new RedisStats();

            // 測試各種操作的響應時間
            long startTime = System.currentTimeMillis();

            // 測試基本操作
            String testKey = "stats:test:" + System.currentTimeMillis();
            redisService.set(testKey, "test", Duration.ofSeconds(5));
            redisService.get(testKey, String.class);
            redisService.delete(testKey);

            long basicOpTime = System.currentTimeMillis() - startTime;
            stats.setBasicOperationTime(basicOpTime);

            // 測試批量操作
            startTime = System.currentTimeMillis();
            Map<String, Object> batchData = new HashMap<>();
            for (int i = 0; i < 10; i++) {
                batchData.put("batch:test:" + i, "value" + i);
            }
            redisService.multiSet(batchData, Duration.ofSeconds(5));
            redisService.multiDelete(batchData.keySet());

            long batchOpTime = System.currentTimeMillis() - startTime;
            stats.setBatchOperationTime(batchOpTime);

            // 獲取緩存鍵的數量統計
            stats.setUserCacheCount(countKeysByPattern("user:*"));
            stats.setSessionCacheCount(countKeysByPattern("session:*"));
            stats.setVoteCacheCount(countKeysByPattern("vote:*"));
            stats.setTotalCacheCount(countKeysByPattern("*"));

            stats.setHealthy(true);
            stats.setTimestamp(LocalDateTime.now());

            return stats;
        } catch (Exception e) {
            log.error("Failed to get Redis stats", e);
            RedisStats errorStats = new RedisStats();
            errorStats.setHealthy(false);
            errorStats.setErrorMessage(e.getMessage());
            errorStats.setTimestamp(LocalDateTime.now());
            return errorStats;
        }
    }

    /**
     * 清理測試數據
     */
    public long cleanupTestData() {
        try {
            long cleaned = 0;
            cleaned += redisService.deleteByPattern("test:*");
            cleaned += redisService.deleteByPattern("health:check:*");
            cleaned += redisService.deleteByPattern("stats:test:*");
            cleaned += redisService.deleteByPattern("batch:test:*");

            log.info("Cleaned up {} test cache entries", cleaned);
            return cleaned;
        } catch (Exception e) {
            log.error("Failed to cleanup test data", e);
            return 0;
        }
    }

    /**
     * 獲取緩存使用情況報告
     */
    public CacheUsageReport getCacheUsageReport() {
        try {
            CacheUsageReport report = new CacheUsageReport();

            // 統計各類型緩存的使用情況
            Map<String, Long> usageByType = new HashMap<>();
            usageByType.put("user", countKeysByPattern("user:*"));
            usageByType.put("session", countKeysByPattern("session:*"));
            usageByType.put("vote", countKeysByPattern("vote:*"));
            usageByType.put("config", countKeysByPattern("config:*"));
            usageByType.put("query", countKeysByPattern("query:*"));
            usageByType.put("page", countKeysByPattern("page:*"));
            usageByType.put("leaderboard", countKeysByPattern("leaderboard:*"));

            report.setUsageByType(usageByType);
            report.setTotalKeys(countKeysByPattern("*"));
            report.setGeneratedAt(LocalDateTime.now());

            return report;
        } catch (Exception e) {
            log.error("Failed to generate cache usage report", e);
            CacheUsageReport errorReport = new CacheUsageReport();
            errorReport.setErrorMessage(e.getMessage());
            errorReport.setGeneratedAt(LocalDateTime.now());
            return errorReport;
        }
    }

    private long countKeysByPattern(String pattern) {
        try {
            Set<String> keys = redisService.keys(pattern);
            return keys.size();
        } catch (Exception e) {
            log.warn("Failed to count keys for pattern: {}", pattern, e);
            return 0;
        }
    }

    /**
     * Redis 統計信息
     */
    public static class RedisStats {
        private boolean healthy;
        private long basicOperationTime;
        private long batchOperationTime;
        private long userCacheCount;
        private long sessionCacheCount;
        private long voteCacheCount;
        private long totalCacheCount;
        private String errorMessage;
        private LocalDateTime timestamp;

        // Getters and Setters
        public boolean isHealthy() { return healthy; }
        public void setHealthy(boolean healthy) { this.healthy = healthy; }

        public long getBasicOperationTime() { return basicOperationTime; }
        public void setBasicOperationTime(long basicOperationTime) { this.basicOperationTime = basicOperationTime; }

        public long getBatchOperationTime() { return batchOperationTime; }
        public void setBatchOperationTime(long batchOperationTime) { this.batchOperationTime = batchOperationTime; }

        public long getUserCacheCount() { return userCacheCount; }
        public void setUserCacheCount(long userCacheCount) { this.userCacheCount = userCacheCount; }

        public long getSessionCacheCount() { return sessionCacheCount; }
        public void setSessionCacheCount(long sessionCacheCount) { this.sessionCacheCount = sessionCacheCount; }

        public long getVoteCacheCount() { return voteCacheCount; }
        public void setVoteCacheCount(long voteCacheCount) { this.voteCacheCount = voteCacheCount; }

        public long getTotalCacheCount() { return totalCacheCount; }
        public void setTotalCacheCount(long totalCacheCount) { this.totalCacheCount = totalCacheCount; }

        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

        public LocalDateTime getTimestamp() { return timestamp; }
        public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
    }

    /**
     * 緩存使用情況報告
     */
    public static class CacheUsageReport {
        private Map<String, Long> usageByType;
        private long totalKeys;
        private String errorMessage;
        private LocalDateTime generatedAt;

        // Getters and Setters
        public Map<String, Long> getUsageByType() { return usageByType; }
        public void setUsageByType(Map<String, Long> usageByType) { this.usageByType = usageByType; }

        public long getTotalKeys() { return totalKeys; }
        public void setTotalKeys(long totalKeys) { this.totalKeys = totalKeys; }

        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

        public LocalDateTime getGeneratedAt() { return generatedAt; }
        public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }
    }
}
