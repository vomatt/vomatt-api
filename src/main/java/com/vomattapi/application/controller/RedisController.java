package com.vomattapi.application.controller;

import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vomattapi.application.service.RedisExampleService;
import com.vomattapi.infrastructure.redis.RedisHealthService;
import com.vomattapi.infrastructure.redis.RedisService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis 管理控制器
 * 提供 Redis 緩存管理和監控的 REST API
 */
@Slf4j
@RestController
@RequestMapping("/api/redis")
@Tag(name = "Redis Management", description = "Redis 緩存管理和監控 API")
public class RedisController {

    @Autowired
    private RedisService redisService;

    @Autowired
    private RedisExampleService redisExampleService;

    @Autowired
    private RedisHealthService redisHealthService;

    // ==================== 健康檢查和監控 ====================

    @GetMapping("/health")
    @Operation(summary = "Redis 健康檢查", description = "檢查 Redis 連接狀態和基本功能")
    public ResponseEntity<Object> getHealth() {
        try {
            return ResponseEntity.ok(redisHealthService.health());
        } catch (Exception e) {
            log.error("Redis health check failed", e);
            return ResponseEntity.internalServerError().body("Health check failed: " + e.getMessage());
        }
    }

    @GetMapping("/stats")
    @Operation(summary = "Redis 統計信息", description = "獲取 Redis 性能統計和使用情況")
    public ResponseEntity<RedisHealthService.RedisStats> getStats() {
        try {
            return ResponseEntity.ok(redisHealthService.getRedisStats());
        } catch (Exception e) {
            log.error("Failed to get Redis stats", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/usage")
    @Operation(summary = "緩存使用情況", description = "獲取各類型緩存的使用統計")
    public ResponseEntity<RedisHealthService.CacheUsageReport> getUsage() {
        try {
            return ResponseEntity.ok(redisHealthService.getCacheUsageReport());
        } catch (Exception e) {
            log.error("Failed to get cache usage report", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== 基本緩存操作 ====================

    @PostMapping("/cache/{key}")
    @Operation(summary = "設置緩存", description = "設置指定鍵的緩存值")
    public ResponseEntity<String> setCache(
            @Parameter(description = "緩存鍵") @PathVariable String key,
            @Parameter(description = "緩存值") @RequestBody Object value,
            @Parameter(description = "過期時間(秒)", required = false) @RequestParam(required = false) Integer ttlSeconds) {
        try {
            if (ttlSeconds != null && ttlSeconds > 0) {
                redisService.set("cache", key, value, java.time.Duration.ofSeconds(ttlSeconds));
            } else {
                redisService.set("cache", key, value);
            }
            return ResponseEntity.ok("Cache set successfully");
        } catch (Exception e) {
            log.error("Failed to set cache: key={}", key, e);
            return ResponseEntity.internalServerError().body("Failed to set cache: " + e.getMessage());
        }
    }

    @GetMapping("/cache/{key}")
    @Operation(summary = "獲取緩存", description = "獲取指定鍵的緩存值")
    public ResponseEntity<Object> getCache(@Parameter(description = "緩存鍵") @PathVariable String key) {
        try {
            Object value = redisService.get("cache", key, Object.class);
            if (value != null) {
                return ResponseEntity.ok(value);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            log.error("Failed to get cache: key={}", key, e);
            return ResponseEntity.internalServerError().body("Failed to get cache: " + e.getMessage());
        }
    }

    @DeleteMapping("/cache/{key}")
    @Operation(summary = "刪除緩存", description = "刪除指定鍵的緩存")
    public ResponseEntity<String> deleteCache(@Parameter(description = "緩存鍵") @PathVariable String key) {
        try {
            boolean deleted = redisService.delete("cache", key);
            if (deleted) {
                return ResponseEntity.ok("Cache deleted successfully");
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            log.error("Failed to delete cache: key={}", key, e);
            return ResponseEntity.internalServerError().body("Failed to delete cache: " + e.getMessage());
        }
    }

    @GetMapping("/keys")
    @Operation(summary = "搜索緩存鍵", description = "根據模式搜索緩存鍵")
    public ResponseEntity<Set<String>> searchKeys(
            @Parameter(description = "搜索模式", example = "user:*") @RequestParam String pattern) {
        try {
            Set<String> keys = redisService.keys("cache", pattern);
            return ResponseEntity.ok(keys);
        } catch (Exception e) {
            log.error("Failed to search keys: pattern={}", pattern, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== 批量操作 ====================

    @PostMapping("/cache/batch")
    @Operation(summary = "批量設置緩存", description = "批量設置多個緩存鍵值對")
    public ResponseEntity<String> setBatchCache(
            @Parameter(description = "緩存鍵值對") @RequestBody Map<String, Object> keyValueMap,
            @Parameter(description = "過期時間(秒)", required = false) @RequestParam(required = false) Integer ttlSeconds) {
        try {
            if (ttlSeconds != null && ttlSeconds > 0) {
                redisService.multiSet("cache", keyValueMap, java.time.Duration.ofSeconds(ttlSeconds));
            } else {
                redisService.multiSet("cache", keyValueMap);
            }
            return ResponseEntity.ok("Batch cache set successfully");
        } catch (Exception e) {
            log.error("Failed to set batch cache: count={}", keyValueMap.size(), e);
            return ResponseEntity.internalServerError().body("Failed to set batch cache: " + e.getMessage());
        }
    }

    @PostMapping("/cache/batch/get")
    @Operation(summary = "批量獲取緩存", description = "批量獲取多個緩存值")
    public ResponseEntity<Map<String, Object>> getBatchCache(
            @Parameter(description = "緩存鍵集合") @RequestBody Set<String> keys) {
        try {
            Map<String, Object> values = redisService.multiGet("cache", keys, Object.class);
            return ResponseEntity.ok(values);
        } catch (Exception e) {
            log.error("Failed to get batch cache: count={}", keys.size(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @DeleteMapping("/cache/batch")
    @Operation(summary = "批量刪除緩存", description = "批量刪除多個緩存鍵")
    public ResponseEntity<String> deleteBatchCache(
            @Parameter(description = "緩存鍵集合") @RequestBody Set<String> keys) {
        try {
            long deletedCount = redisService.multiDelete("cache", keys);
            return ResponseEntity.ok("Deleted " + deletedCount + " cache entries");
        } catch (Exception e) {
            log.error("Failed to delete batch cache: count={}", keys.size(), e);
            return ResponseEntity.internalServerError().body("Failed to delete batch cache: " + e.getMessage());
        }
    }

    // ==================== 示例功能 ====================

    @PostMapping("/example/session")
    @Operation(summary = "創建用戶會話", description = "創建並緩存用戶會話信息")
    public ResponseEntity<String> createUserSession(
            @Parameter(description = "會話ID") @RequestParam String sessionId,
            @Parameter(description = "用戶ID") @RequestParam String userId,
            @Parameter(description = "用戶名") @RequestParam String username) {
        try {
            RedisExampleService.UserSession session = new RedisExampleService.UserSession(
                userId, username, java.time.LocalDateTime.now(), "127.0.0.1", "Test-Agent");
            redisExampleService.storeUserSession(sessionId, session);
            return ResponseEntity.ok("User session created successfully");
        } catch (Exception e) {
            log.error("Failed to create user session: sessionId={}", sessionId, e);
            return ResponseEntity.internalServerError().body("Failed to create user session: " + e.getMessage());
        }
    }

    @GetMapping("/example/session/{sessionId}")
    @Operation(summary = "獲取用戶會話", description = "獲取緩存的用戶會話信息")
    public ResponseEntity<RedisExampleService.UserSession> getUserSession(
            @Parameter(description = "會話ID") @PathVariable String sessionId) {
        try {
            RedisExampleService.UserSession session = redisExampleService.getUserSession(sessionId);
            if (session != null) {
                return ResponseEntity.ok(session);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            log.error("Failed to get user session: sessionId={}", sessionId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/example/activity")
    @Operation(summary = "記錄用戶活動", description = "記錄用戶活動到 Redis")
    public ResponseEntity<String> recordActivity(
            @Parameter(description = "用戶ID") @RequestParam String userId,
            @Parameter(description = "活動類型") @RequestParam String activity) {
        try {
            redisExampleService.recordUserActivity(userId, activity);
            return ResponseEntity.ok("Activity recorded successfully");
        } catch (Exception e) {
            log.error("Failed to record activity: userId={}, activity={}", userId, activity, e);
            return ResponseEntity.internalServerError().body("Failed to record activity: " + e.getMessage());
        }
    }

    @GetMapping("/example/activity/{userId}")
    @Operation(summary = "獲取用戶活動統計", description = "獲取用戶的活動統計信息")
    public ResponseEntity<Map<String, Long>> getUserActivityStats(
            @Parameter(description = "用戶ID") @PathVariable String userId) {
        try {
            Map<String, Long> stats = redisExampleService.getUserActivityStats(userId);
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("Failed to get user activity stats: userId={}", userId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== 清理功能 ====================

    @DeleteMapping("/cleanup/test")
    @Operation(summary = "清理測試數據", description = "清理所有測試相關的緩存數據")
    public ResponseEntity<String> cleanupTestData() {
        try {
            long cleaned = redisHealthService.cleanupTestData();
            return ResponseEntity.ok("Cleaned up " + cleaned + " test cache entries");
        } catch (Exception e) {
            log.error("Failed to cleanup test data", e);
            return ResponseEntity.internalServerError().body("Failed to cleanup test data: " + e.getMessage());
        }
    }

    @DeleteMapping("/cleanup/user/{userId}")
    @Operation(summary = "清理用戶緩存", description = "清理指定用戶的所有緩存數據")
    public ResponseEntity<String> cleanupUserCache(@Parameter(description = "用戶ID") @PathVariable String userId) {
        try {
            long cleaned = redisExampleService.cleanupUserCache(userId);
            return ResponseEntity.ok("Cleaned up " + cleaned + " cache entries for user: " + userId);
        } catch (Exception e) {
            log.error("Failed to cleanup user cache: userId={}", userId, e);
            return ResponseEntity.internalServerError().body("Failed to cleanup user cache: " + e.getMessage());
        }
    }

    @DeleteMapping("/cleanup/pattern")
    @Operation(summary = "按模式清理緩存", description = "根據指定模式清理緩存數據")
    public ResponseEntity<String> cleanupByPattern(
            @Parameter(description = "清理模式", example = "temp:*") @RequestParam String pattern) {
        try {
            long cleaned = redisService.deleteByPattern("cache", pattern);
            return ResponseEntity.ok("Cleaned up " + cleaned + " cache entries matching pattern: " + pattern);
        } catch (Exception e) {
            log.error("Failed to cleanup by pattern: pattern={}", pattern, e);
            return ResponseEntity.internalServerError().body("Failed to cleanup by pattern: " + e.getMessage());
        }
    }
}
