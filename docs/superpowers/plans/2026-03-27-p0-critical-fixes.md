# P0 - 立即修復計劃

**日期**: 2026-03-27
**優先級**: 🔴 Critical — 影響安全性與效能，應立即處理

---

## 任務 1：修復 N+1 查詢問題

### 問題描述
`VoteServiceImpl.convertToVoteResponse()` 每次呼叫觸發 4 次查詢：
1. 主 Vote
2. VoteOption list
3. 各選項票數 (countByOptionGroupedForVote)
4. 總票數 (countByVoteId)

`UserServiceImpl.getUserProfile()` 同樣有 3 次分開查詢。

### 修復方案

#### 1.1 VoteRepository — 新增 JOIN FETCH 查詢
```java
// VoteRepository.java
@Query("SELECT v FROM Vote v LEFT JOIN FETCH v.options WHERE v.id = :id")
Optional<Vote> findByIdWithOptions(@Param("id") UUID id);

@Query("SELECT v FROM Vote v LEFT JOIN FETCH v.options LEFT JOIN FETCH v.tags WHERE v.id = :id")
Optional<Vote> findByIdWithOptionsAndTags(@Param("id") UUID id);
```

#### 1.2 UserVoteRepository — 改用 Projection 替代 Object[]
```java
// 新增 interface
public interface OptionVoteCount {
    UUID getOptionId();
    Long getCount();
}

// UserVoteRepository.java
@Query("SELECT uv.option.id AS optionId, COUNT(uv) AS count " +
       "FROM UserVote uv WHERE uv.vote.id = :voteId GROUP BY uv.option.id")
List<OptionVoteCount> countByOptionGroupedForVote(@Param("voteId") UUID voteId);
```

#### 1.3 VoteServiceImpl — 使用新查詢方法
```java
private VoteResponse convertToVoteResponse(Vote vote) {
    // 改用帶 JOIN FETCH 的查詢
    Vote voteWithOptions = voteRepository.findByIdWithOptions(vote.getId())
        .orElse(vote);

    Map<UUID, Long> optionCounts = userVoteRepository
        .countByOptionGroupedForVote(vote.getId())
        .stream()
        .collect(Collectors.toMap(
            OptionVoteCount::getOptionId,
            OptionVoteCount::getCount
        ));

    long totalVoteCount = optionCounts.values().stream().mapToLong(Long::longValue).sum();

    return voteMapper.toResponse(voteWithOptions, voteWithOptions.getOptions(), optionCounts, totalVoteCount);
}
```

#### 1.4 UserServiceImpl — 合併 getUserProfile 查詢
```java
// UserRepository.java — 新增 Projection
public interface UserProfileProjection {
    UUID getId();
    String getUsername();
    String getEmail();
    // ... 其他欄位
    Long getTotalPolls();
    Long getTotalVotes();
}

@Query("SELECT u.id AS id, u.username AS username, u.email AS email, " +
       "COUNT(DISTINCT v.id) AS totalPolls, COUNT(DISTINCT uv.id) AS totalVotes " +
       "FROM User u " +
       "LEFT JOIN Vote v ON v.creator.id = u.id " +
       "LEFT JOIN UserVote uv ON uv.user.id = u.id " +
       "WHERE u.username = :username GROUP BY u.id")
Optional<UserProfileProjection> findProfileByUsername(@Param("username") String username);
```

### 影響檔案
- `domain/vote/repository/VoteRepository.java`
- `domain/vote/repository/UserVoteRepository.java`
- `domain/user/repository/UserRepository.java`
- `application/service/vote/VoteServiceImpl.java`
- `application/service/user/UserServiceImpl.java`

### 驗證方式
- 啟用 `spring.jpa.show-sql=true` 確認查詢次數減少
- 新增整合測試驗證結果正確性

---

## 任務 2：修復 JWT 密鑰安全問題

### 問題描述
`application.properties` 設有不安全的預設密鑰：
```properties
app.jwt.secret=${JWT_SECRET:testtest}
```
若生產環境未設定 `JWT_SECRET`，將使用 `testtest` 作為 HMAC 密鑰，導致 Token 可被偽造。

### 修復方案

#### 2.1 移除預設值，強制設定環境變數
```properties
# application.properties
app.jwt.secret=${JWT_SECRET}
```

#### 2.2 JwtUtils 加入啟動驗證
```java
// JwtUtils.java
@PostConstruct
public void init() {
    if (jwtSecret == null || jwtSecret.isBlank()) {
        throw new IllegalStateException(
            "JWT_SECRET 環境變數未設定。請設定至少 32 字元的隨機密鑰。"
        );
    }
    if (jwtSecret.length() < 32) {
        throw new IllegalStateException(
            "JWT_SECRET 長度不足，請使用至少 256 bits（32 字元）的密鑰。"
        );
    }
    byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
    this.key = Keys.hmacShaKeyFor(keyBytes);
}
```

#### 2.3 更新開發環境設定
```bash
# .env.example (加入此檔案到版控)
JWT_SECRET=請替換為至少32字元的隨機字串
JWT_EXPIRATION_MS=86400000
JWT_REFRESH_EXPIRATION_MS=604800000
```

### 影響檔案
- `src/main/resources/application.properties`
- `infrastructure/security/jwt/JwtUtils.java`
- `.env.example`（新增）

### 驗證方式
- 不設定 `JWT_SECRET` 時，應用啟動應拋出明確錯誤
- 設定後正常啟動

---

## 任務 3：補充關鍵資料庫索引

### 問題描述
高頻查詢缺少對應索引，導致全表掃描。

### 修復方案

#### 3.1 新增 Migration 檔案
```sql
-- V11__add_performance_indexes.sql

-- 用於檢查使用者是否已對特定投票投票
CREATE INDEX IF NOT EXISTS idx_user_votes_vote_user
    ON user_votes(vote_id, user_id);

-- 用於查詢活躍投票列表
CREATE INDEX IF NOT EXISTS idx_votes_active_time
    ON votes(is_active, start_time, end_time);

-- 用於標籤關聯查詢
CREATE INDEX IF NOT EXISTS idx_vote_tags_tag_vote
    ON vote_tags(tag_id, vote_id);

-- 用於評論查詢（依投票分頁）
CREATE INDEX IF NOT EXISTS idx_vote_comments_vote_created
    ON vote_comments(vote_id, created_at DESC);
```

### 影響檔案
- `src/main/resources/db/migration/V11__add_performance_indexes.sql`（新增）

### 驗證方式
- 執行 `EXPLAIN ANALYZE` 確認查詢使用索引
- 可用 `\d votes` 確認索引建立成功

---

## 完成標準

- [ ] N+1 查詢問題修復，主要端點查詢次數 ≤ 2
- [ ] JWT 密鑰無預設值，未設定時啟動報錯
- [ ] 關鍵索引建立，Migration 執行成功
- [ ] 所有修改有對應單元/整合測試
- [ ] `./mvnw clean test` 全部通過
