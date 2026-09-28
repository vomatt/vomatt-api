# P2 - 中期優化計劃

**日期**: 2026-03-27
**優先級**: 🟠 Medium — 提升系統健壯性與可維護性，建議在 P0/P1 完成後處理

---

## 任務 1：補全 Domain Events 監聽器

### 問題描述
`VoteServiceImpl` 已發佈 `VoteCreatedEvent`、`VoteCastEvent` 等事件，但找不到對應的 `@TransactionalEventListener`，事件等同無效。

### 修復方案

#### 1.1 建立 VoteEventListener
```java
// domain/vote/event/VoteEventListener.java
@Component
@RequiredArgsConstructor
@Slf4j
public class VoteEventListener {

    private final UserActivityRepository userActivityRepository;
    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onVoteCreated(VoteCreatedEvent event) {
        log.info("Vote created: voteId={}, creatorId={}", event.getVoteId(), event.getCreatorId());
        // 記錄建立活動
        UserActivity activity = new UserActivity();
        activity.setUserId(event.getCreatorId());
        activity.setActivityType("VOTE_CREATED");
        activity.setReferenceId(event.getVoteId());
        userActivityRepository.save(activity);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onVoteCast(VoteCastEvent event) {
        log.info("Vote cast: voteId={}, userId={}", event.getVoteId(), event.getUserId());
        // 記錄投票活動
        UserActivity activity = new UserActivity();
        activity.setUserId(event.getUserId());
        activity.setActivityType("VOTE_CAST");
        activity.setReferenceId(event.getVoteId());
        userActivityRepository.save(activity);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onVoteDeactivated(VoteDeactivatedEvent event) {
        log.info("Vote deactivated: voteId={}", event.getVoteId());
        // 可在此通知投票參與者、更新統計等
    }
}
```

#### 1.2 確認 AsyncConfig 已設定執行緒池
```java
// infrastructure/config/AsyncConfig.java（已有此檔，確認設定）
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("vote-event-");
        executor.initialize();
        return executor;
    }
}
```

### 影響檔案
- `domain/vote/event/VoteEventListener.java`（已有，補充實作）
- `infrastructure/config/AsyncConfig.java`（確認設定）

---

## 任務 2：投票端點加入限流

### 問題描述
目前只有 Auth 端點有 `@RateLimiter`，投票端點缺少保護，可能遭受大量請求攻擊。

### 修復方案

#### 2.1 新增 Resilience4j 限流設定
```yaml
# application.properties 或 application.yml
resilience4j:
  ratelimiter:
    instances:
      vote:
        limitForPeriod: 10          # 每個週期最多 10 次
        limitRefreshPeriod: 1s      # 每秒刷新
        timeoutDuration: 0          # 立即拒絕超量請求
      createVote:
        limitForPeriod: 5
        limitRefreshPeriod: 1m      # 每分鐘最多建立 5 個投票
        timeoutDuration: 0
```

#### 2.2 VoteController 加入限流
```java
// VoteController.java
@PostMapping("/{voteId}/vote")
@RateLimiter(name = "vote", fallbackMethod = "voteFallback")
public ResponseEntity<ApiResponse<VoteResponse>> castVote(
        @PathVariable String voteId,
        @Valid @RequestBody VoteRequest request,
        Authentication authentication) {
    // 現有邏輯
}

@PostMapping
@RateLimiter(name = "createVote", fallbackMethod = "createVoteFallback")
public ResponseEntity<ApiResponse<VoteResponse>> createVote(...) {
    // 現有邏輯
}

// Fallback 方法
public ResponseEntity<ApiResponse<VoteResponse>> voteFallback(
        String voteId, VoteRequest request, Authentication auth,
        RequestNotPermitted ex) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .body(ApiResponse.error("請求過於頻繁，請稍後再試"));
}
```

### 影響檔案
- `src/main/resources/application.properties`
- `application/controller/VoteController.java`

---

## 任務 3：修復 CORS 設定潛在問題

### 問題描述
`allowCredentials(true)` 搭配動態來源設定，若 `CORS_ALLOWED_ORIGINS` 被錯誤設為 `*`，會造成嚴重安全漏洞。

### 修復方案

#### 3.1 WebSecurityConfig 加入防護驗證
```java
// WebSecurityConfig.java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();

    List<String> allowedOrigins = securityProperties.getCorsAllowedOrigins();

    // 防護：不允許 * 與 allowCredentials 同時使用
    if (allowedOrigins.contains("*")) {
        throw new IllegalStateException(
            "CORS: 不可在 allowCredentials=true 時使用萬用字元 '*' 來源。" +
            "請在 CORS_ALLOWED_ORIGINS 中指定具體的來源網址。"
        );
    }

    configuration.setAllowedOrigins(allowedOrigins);
    configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
    configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-Requested-With"));
    configuration.setAllowCredentials(true);
    configuration.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
}
```

#### 3.2 更新 .env.example
```bash
# CORS — 列出允許的來源，逗號分隔，不可使用 *
CORS_ALLOWED_ORIGINS=http://localhost:3000,https://yourdomain.com
```

### 影響檔案
- `infrastructure/security/WebSecurityConfig.java`
- `.env.example`

---

## 任務 4：改善帳戶鎖定機制（分散式安全）

### 問題描述
目前登入嘗試次數儲存在記憶體（`UserServiceImpl` 本地狀態），在多實例部署時各實例計數獨立，無法正確鎖定帳號。

### 修復方案

#### 4.1 改用 Redis 儲存登入嘗試次數
```java
// UserServiceImpl.java
private static final String LOGIN_ATTEMPTS_KEY_PREFIX = "login:attempts:";
private static final String ACCOUNT_LOCK_KEY_PREFIX = "login:locked:";

public void recordFailedLogin(String username) {
    String attemptsKey = LOGIN_ATTEMPTS_KEY_PREFIX + username;
    String lockKey = ACCOUNT_LOCK_KEY_PREFIX + username;

    Long attempts = redisService.increment(attemptsKey);

    // 設定 key 過期時間（第一次失敗時設定）
    if (attempts == 1) {
        redisService.expire(attemptsKey, Duration.ofMinutes(ACCOUNT_LOCK_MINUTES));
    }

    if (attempts >= MAX_LOGIN_ATTEMPTS) {
        redisService.set(lockKey, "locked", Duration.ofMinutes(ACCOUNT_LOCK_MINUTES));
        log.warn("Account locked due to too many failed attempts: {}", username);
    }
}

public boolean isAccountLocked(String username) {
    return redisService.exists(ACCOUNT_LOCK_KEY_PREFIX + username);
}

public void clearLoginAttempts(String username) {
    redisService.delete(LOGIN_ATTEMPTS_KEY_PREFIX + username);
    redisService.delete(ACCOUNT_LOCK_KEY_PREFIX + username);
}
```

### 影響檔案
- `application/service/user/UserServiceImpl.java`
- `infrastructure/redis/RedisService.java`（確認 increment/expire 方法存在）

---

## 任務 5：統一 VoteRepository 查詢方法（Specification 模式）

### 問題描述
`VoteRepository` 有 10+ 個類似查詢方法，每增加一個篩選條件就需新增方法，難以維護。

### 修復方案

#### 5.1 建立 VoteSpecification
```java
// domain/vote/repository/VoteSpecification.java
public class VoteSpecification {

    public static Specification<Vote> isActive() {
        return (root, query, cb) -> cb.isTrue(root.get("isActive"));
    }

    public static Specification<Vote> createdBy(UUID creatorId) {
        return (root, query, cb) -> cb.equal(root.get("creator").get("id"), creatorId);
    }

    public static Specification<Vote> hasTag(UUID tagId) {
        return (root, query, cb) -> {
            Join<Vote, Tag> tags = root.join("tags", JoinType.INNER);
            return cb.equal(tags.get("id"), tagId);
        };
    }

    public static Specification<Vote> startedBefore(LocalDateTime time) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("startTime"), time);
    }

    public static Specification<Vote> endedAfter(LocalDateTime time) {
        return (root, query, cb) -> cb.greaterThan(root.get("endTime"), time);
    }
}
```

#### 5.2 VoteRepository 改繼承 JpaSpecificationExecutor
```java
// VoteRepository.java
public interface VoteRepository extends JpaRepository<Vote, UUID>,
        JpaSpecificationExecutor<Vote> {
    // 保留必要的自定義查詢方法
    // 移除可被 Specification 取代的重複方法
}
```

#### 5.3 使用範例
```java
// 查詢活躍、有特定標籤的投票
Specification<Vote> spec = Specification
    .where(VoteSpecification.isActive())
    .and(VoteSpecification.hasTag(tagId))
    .and(VoteSpecification.startedBefore(LocalDateTime.now()));

Page<Vote> votes = voteRepository.findAll(spec, pageable);
```

### 影響檔案
- `domain/vote/repository/VoteRepository.java`
- `domain/vote/repository/VoteSpecification.java`（新增）
- `application/service/vote/VoteServiceImpl.java`（使用新方法）

---

## 完成標準

- [ ] Domain Events 有對應監聽器，活動記錄正確寫入
- [ ] 投票端點有限流保護，超量請求回傳 429
- [ ] CORS 設定有防護驗證，啟動時若設定錯誤立即報錯
- [ ] 帳戶鎖定機制改用 Redis，多實例下行為一致
- [ ] `VoteRepository` 使用 Specification，移除重複查詢方法
- [ ] `./mvnw clean test` 全部通過
