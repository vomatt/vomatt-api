# Redis 整合指南

本文檔說明如何在 Vomatt API 中使用 Redis 緩存功能，包括單筆和批量操作，以及高性能序列化配置。

## 目錄

1. [配置說明](#配置說明)
2. [Redis 服務接口](#redis-服務接口)
3. [基本使用方法](#基本使用方法)
4. [批量操作](#批量操作)
5. [緩存工具類](#緩存工具類)
6. [示例服務](#示例服務)
7. [健康檢查與監控](#健康檢查與監控)
8. [REST API 接口](#rest-api-接口)
9. [最佳實踐](#最佳實踐)

## 配置說明

### 1. Maven 依賴

已添加以下 Redis 相關依賴到 `pom.xml`：

```xml
<!-- Redis -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>redis.clients</groupId>
    <artifactId>jedis</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-pool2</artifactId>
</dependency>
```

### 2. 應用程式配置

在 `application.properties` 中添加了 Redis 配置：

```properties
# Redis 配置
spring.data.redis.host=${REDIS_HOST:localhost}
spring.data.redis.port=${REDIS_PORT:6379}
spring.data.redis.password=${REDIS_PASSWORD:}
spring.data.redis.database=${REDIS_DATABASE:0}
spring.data.redis.timeout=${REDIS_TIMEOUT:60000}
spring.data.redis.jedis.pool.max-active=${REDIS_POOL_MAX_ACTIVE:50}
spring.data.redis.jedis.pool.max-idle=${REDIS_POOL_MAX_IDLE:20}
spring.data.redis.jedis.pool.min-idle=${REDIS_POOL_MIN_IDLE:5}
spring.data.redis.jedis.pool.max-wait=${REDIS_POOL_MAX_WAIT:3000}
```

### 3. Redis 配置類

`RedisConfig.java` 提供了高性能的 JSON 序列化配置：

- 使用 Jackson 進行 JSON 序列化，支持 Java 8 時間類型
- 配置連接池以提升性能
- 支持事務操作

## Redis 服務接口

### RedisService 介面

提供了完整的 Redis 操作接口，包括：

- **基本操作**: `set()`, `get()`, `delete()`, `hasKey()`, `expire()`
- **批量操作**: `multiSet()`, `multiGet()`, `multiDelete()`
- **Hash 操作**: `hSet()`, `hGet()`, `hMultiSet()`, `hMultiGet()`
- **List 操作**: `leftPush()`, `rightPush()`, `leftPop()`, `rightPop()`
- **Set 操作**: `setAdd()`, `setRemove()`, `setMembers()`
- **計數器操作**: `increment()`, `decrement()`
- **模式匹配**: `keys()`, `deleteByPattern()`

## 基本使用方法

### 1. 注入 Redis 服務

```java
@Autowired
private RedisService redisService;
```

### 2. 基本讀寫操作

```java
// 儲存數據
redisService.set("user:123", userObject);

// 儲存數據並設置過期時間
redisService.set("session:abc", sessionData, Duration.ofHours(24));

// 讀取數據
User user = redisService.get("user:123", User.class);

// 刪除數據
boolean deleted = redisService.delete("user:123");

// 檢查鍵是否存在
boolean exists = redisService.hasKey("user:123");
```

### 3. Hash 操作

```java
// Hash 儲存用戶偏好設置
redisService.hSet("user:settings:123", "theme", "dark");
redisService.hSet("user:settings:123", "language", "zh-TW");

// 批量儲存 Hash 數據
Map<String, Object> settings = Map.of(
    "theme", "dark",
    "language", "zh-TW",
    "notifications", true
);
redisService.hMultiSet("user:settings:123", settings);

// 獲取 Hash 數據
String theme = redisService.hGet("user:settings:123", "theme", String.class);

// 獲取所有 Hash 數據
Map<String, Object> allSettings = redisService.hGetAll("user:settings:123", Object.class);
```

## 批量操作

Redis 支持高效的批量操作，可以顯著提升性能：

### 1. 批量設置

```java
// 批量儲存用戶數據
Map<String, Object> userData = Map.of(
    "user:123", user1,
    "user:124", user2,
    "user:125", user3
);
redisService.multiSet(userData, Duration.ofMinutes(30));
```

### 2. 批量獲取

```java
// 批量獲取用戶數據
Set<String> userKeys = Set.of("user:123", "user:124", "user:125");
Map<String, User> users = redisService.multiGet(userKeys, User.class);
```

### 3. 批量刪除

```java
// 批量刪除用戶數據
Set<String> keysToDelete = Set.of("user:123", "user:124", "user:125");
long deletedCount = redisService.multiDelete(keysToDelete);
```

## 緩存工具類

### CacheUtil 提供便利的緩存操作

```java
@Autowired
private CacheUtil cacheUtil;

// 獲取或設置緩存（如果不存在則執行供應商函數）
User user = cacheUtil.getOrSet("user:123", User.class, 
    () -> userRepository.findById("123").orElse(null));

// 緩存用戶數據（短時間）
UserProfile profile = cacheUtil.cacheUserData("123", "profile", UserProfile.class,
    () -> profileService.getUserProfile("123"));

// 緩存配置數據（長時間）
AppConfig config = cacheUtil.cacheConfigData("app.settings", AppConfig.class,
    () -> configService.getAppConfig());

// 清除用戶所有相關緩存
long evictedCount = cacheUtil.evictUserCache("123");
```

## 示例服務

### RedisExampleService 演示進階用法

#### 1. 用戶會話管理

```java
// 儲存用戶會話
UserSession session = new UserSession("123", "john", LocalDateTime.now(), "127.0.0.1", "Browser");
redisExampleService.storeUserSession("session_abc", session);

// 批量儲存用戶會話
Map<String, UserSession> sessions = Map.of(
    "session_1", session1,
    "session_2", session2
);
redisExampleService.batchStoreUserSessions(sessions);

// 批量獲取用戶會話
Set<String> sessionIds = Set.of("session_1", "session_2");
Map<String, UserSession> userSessions = redisExampleService.batchGetUserSessions(sessionIds);
```

#### 2. 活躍度統計

```java
// 記錄用戶活動
redisExampleService.recordUserActivity("123", "login");
redisExampleService.recordUserActivity("123", "vote");

// 獲取用戶活動統計
Map<String, Long> stats = redisExampleService.getUserActivityStats("123");
// 結果: {"login": 1, "vote": 1}
```

#### 3. 計數器功能

```java
// 遞增頁面瀏覽量
long views = redisExampleService.incrementPageViews("page_home");

// 批量遞增
Map<String, Long> pageViews = Map.of("page_home", 5L, "page_about", 3L);
redisExampleService.batchIncrementPageViews(pageViews);
```

## 健康檢查與監控

### RedisHealthService 提供監控功能

```java
@Autowired
private RedisHealthService redisHealthService;

// 檢查 Redis 健康狀態
Health health = redisHealthService.health();

// 獲取 Redis 統計信息
RedisStats stats = redisHealthService.getRedisStats();

// 獲取緩存使用情況報告
CacheUsageReport report = redisHealthService.getCacheUsageReport();

// 清理測試數據
long cleaned = redisHealthService.cleanupTestData();
```

## REST API 接口

Redis 管理 API 提供了 REST 接口，可通過 HTTP 請求管理緩存：

### 基本操作

```bash
# 設置緩存
POST /api/redis/cache/user:123
Content-Type: application/json
{
  "username": "john",
  "email": "john@example.com"
}

# 獲取緩存
GET /api/redis/cache/user:123

# 刪除緩存
DELETE /api/redis/cache/user:123

# 搜索緩存鍵
GET /api/redis/keys?pattern=user:*
```

### 批量操作

```bash
# 批量設置緩存
POST /api/redis/cache/batch
Content-Type: application/json
{
  "user:123": {"name": "John"},
  "user:124": {"name": "Jane"}
}

# 批量獲取緩存
POST /api/redis/cache/batch/get
Content-Type: application/json
["user:123", "user:124"]

# 批量刪除緩存
DELETE /api/redis/cache/batch
Content-Type: application/json
["user:123", "user:124"]
```

### 監控接口

```bash
# Redis 健康檢查
GET /api/redis/health

# 獲取統計信息
GET /api/redis/stats

# 獲取使用情況
GET /api/redis/usage
```

## 最佳實踐

### 1. 鍵命名規範

```java
// 推薦的鍵命名格式
"user:{userId}:profile"           // 用戶資料
"session:{sessionId}"             // 用戶會話
"vote:{voteId}:result"           // 投票結果
"config:{configKey}"             // 配置數據
"cache:{queryHash}"              // 查詢緩存
```

### 2. 過期時間設置

```java
// 不同數據類型的建議過期時間
Duration.ofMinutes(5)     // 用戶相關緩存（短期）
Duration.ofMinutes(30)    // 查詢結果緩存（中期）
Duration.ofHours(2)       // 配置數據緩存（長期）
Duration.ofHours(24)      // 用戶會話（一天）
```

### 3. 序列化優化

- 使用 JSON 序列化提供更好的可讀性和兼容性
- 避免儲存過大的物件，考慮分割或壓縮
- 使用批量操作减少網路開銷

### 4. 錯誤處理

```java
try {
    User user = redisService.get("user:123", User.class);
    if (user == null) {
        // 緩存未命中，從資料庫查詢
        user = userRepository.findById("123").orElse(null);
        if (user != null) {
            redisService.set("user:123", user, Duration.ofMinutes(30));
        }
    }
    return user;
} catch (Exception e) {
    log.error("Redis operation failed", e);
    // 降級到資料庫查詢
    return userRepository.findById("123").orElse(null);
}
```

### 5. 記憶體管理

```java
// 定期清理過期數據
@Scheduled(fixedRate = 3600000) // 每小時執行一次
public void cleanupExpiredData() {
    redisExampleService.cleanupExpiredSessions();
    redisHealthService.cleanupTestData();
}

// 按模式批量清理
long cleaned = redisService.deleteByPattern("temp:*");
```

## 啟動 Redis

### 使用 Docker 啟動 Redis

```bash
# 啟動 Redis 容器
docker run -d --name redis-server -p 6379:6379 redis:latest

# 或使用 Docker Compose
version: '3.8'
services:
  redis:
    image: redis:latest
    ports:
      - "6379:6379"
    command: redis-server --appendonly yes
    volumes:
      - redis-data:/data

volumes:
  redis-data:
```

### 本地安裝 Redis

```bash
# macOS
brew install redis
brew services start redis

# Ubuntu
sudo apt update
sudo apt install redis-server
sudo systemctl start redis
```

## 測試 Redis 功能

### 1. 健康檢查

```bash
curl http://localhost:8080/api/redis/health
```

### 2. 設置和獲取數據

```bash
# 設置數據
curl -X POST http://localhost:8080/api/redis/cache/test \
  -H "Content-Type: application/json" \
  -d '{"message": "Hello Redis"}'

# 獲取數據
curl http://localhost:8080/api/redis/cache/test
```

### 3. 查看統計信息

```bash
curl http://localhost:8080/api/redis/stats
curl http://localhost:8080/api/redis/usage
```

這個 Redis 整合提供了完整的緩存解決方案，支援高性能序列化、批量操作、健康監控和 REST API 管理，可以顯著提升應用程式的性能和擴展性。
