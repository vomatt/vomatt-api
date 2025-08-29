package com.vomattapi.application.controller;

import java.util.Map;
import java.util.Set;

import com.vomattapi.infrastructure.redis.CacheKeyUtil;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class RedisController {
    private final RedisService redisService;
    private final RedisHealthService redisHealthService;

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
}
