# Phase 2: 更多投票類型 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 擴充投票系統，支援 5 種投票類型（STANDARD, IMAGE, RANKING, YES_NO, RATING），各有專屬的投票方式、結果計算、安全驗證。

**Architecture:** 新增 `VoteType` 列舉，Vote 實體加 `voteType` 欄位（向後相容 DEFAULT 'STANDARD'）。RANKING / RATING 各有獨立實體（`UserRanking`, `UserRating`）與 Repository。各類型透過 VoteService 中的 Strategy 分流處理投票與結果計算。`@ValidImageUrl` 自定義驗證器防止 SSRF。

**Tech Stack:** Java 21, Spring Boot 3.4.8+, Spring Data JPA, PostgreSQL, Flyway, JUnit 5, Mockito, AssertJ

**Spec Reference:** `docs/superpowers/specs/2026-03-21-vote-experience-enhancement-design.md` (Phase 2 section, lines 134-218)

---

## File Structure

### 新增檔案

| 路徑 | 職責 |
|------|------|
| `src/main/resources/db/migration/V10__add_vote_types.sql` | Migration：votes 加 vote_type、vote_options 加 image_url、user_rankings 表、user_ratings 表 |
| `src/main/java/com/vomattapi/domain/vote/VoteType.java` | 投票類型列舉 |
| `src/main/java/com/vomattapi/domain/vote/UserRanking.java` | RANKING 類型：使用者排名記錄實體 |
| `src/main/java/com/vomattapi/domain/vote/UserRating.java` | RATING 類型：使用者評分記錄實體 |
| `src/main/java/com/vomattapi/domain/vote/repository/UserRankingRepository.java` | UserRanking 資料存取 |
| `src/main/java/com/vomattapi/domain/vote/repository/UserRatingRepository.java` | UserRating 資料存取 |
| `src/main/java/com/vomattapi/application/validation/ValidImageUrl.java` | 自定義圖片 URL 驗證註解 |
| `src/main/java/com/vomattapi/application/validation/ImageUrlValidator.java` | 圖片 URL 驗證器（SSRF 防護） |
| `src/main/java/com/vomattapi/application/dto/request/RankRequest.java` | RANKING 投票請求 DTO |
| `src/main/java/com/vomattapi/application/dto/request/RateRequest.java` | RATING 投票請求 DTO |
| `src/test/java/com/vomattapi/validation/ImageUrlValidatorTest.java` | 圖片 URL 驗證器測試 |
| `src/test/java/com/vomattapi/service/RankingServiceTest.java` | RANKING 投票邏輯測試 |
| `src/test/java/com/vomattapi/service/RatingServiceTest.java` | RATING 投票邏輯測試 |

### 修改檔案

| 路徑 | 變動 |
|------|------|
| `src/main/java/com/vomattapi/domain/vote/Vote.java` | 新增 `voteType` 欄位 |
| `src/main/java/com/vomattapi/domain/vote/VoteOption.java` | 新增 `imageUrl` 欄位 |
| `src/main/java/com/vomattapi/application/dto/request/CreateVoteRequest.java` | 新增 `voteType`、VoteOptionRequest 新增 `imageUrl` |
| `src/main/java/com/vomattapi/application/dto/response/VoteResponse.java` | 新增 `voteType`、VoteOptionResponse 新增 `imageUrl` |
| `src/main/java/com/vomattapi/application/dto/response/VoteResultResponse.java` | 擴充支援 RANKING/RATING 結果格式 |
| `src/main/java/com/vomattapi/application/dto/response/ErrorType.java` | 新增投票類型相關錯誤碼 |
| `src/main/java/com/vomattapi/application/service/VoteService.java` | 新增 `submitRanking()`, `submitRating()` 方法 |
| `src/main/java/com/vomattapi/application/service/impl/VoteServiceImpl.java` | 實作新方法 + 類型驗證邏輯 |
| `src/main/java/com/vomattapi/application/mapper/VoteMapper.java` | 支援 voteType/imageUrl 映射 + 多類型結果 |
| `src/main/java/com/vomattapi/application/controller/VoteController.java` | 新增 rank/rate 端點 |
| `src/test/java/com/vomattapi/service/VoteServiceImplTest.java` | 新增類型驗證測試 |
| `src/test/java/com/vomattapi/controller/VoteControllerTest.java` | 新增端點測試 |

---

### Task 1: Database Migration

**Files:**
- Create: `src/main/resources/db/migration/V10__add_vote_types.sql`

- [ ] **Step 1: 建立 Migration 檔案**

```sql
-- Phase 2: 更多投票類型

-- votes 表新增 vote_type 欄位（現有投票預設為 STANDARD）
ALTER TABLE votes ADD COLUMN vote_type VARCHAR(20) NOT NULL DEFAULT 'STANDARD';

-- vote_options 表新增 image_url 欄位（IMAGE 類型使用）
ALTER TABLE vote_options ADD COLUMN image_url VARCHAR(500);

-- 建立 user_rankings 表（RANKING 類型專用）
CREATE TABLE IF NOT EXISTS user_rankings (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    vote_id VARCHAR(36) NOT NULL,
    option_id VARCHAR(36) NOT NULL,
    rank_position INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    FOREIGN KEY (vote_id) REFERENCES votes (id) ON DELETE CASCADE,
    FOREIGN KEY (option_id) REFERENCES vote_options (id) ON DELETE CASCADE,
    UNIQUE(user_id, vote_id, option_id)
);

-- 建立 user_ratings 表（RATING 類型專用）
CREATE TABLE IF NOT EXISTS user_ratings (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    vote_id VARCHAR(36) NOT NULL,
    score INTEGER NOT NULL CHECK (score >= 1 AND score <= 5),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    FOREIGN KEY (vote_id) REFERENCES votes (id) ON DELETE CASCADE,
    UNIQUE(user_id, vote_id)
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_votes_vote_type ON votes(vote_type);
CREATE INDEX IF NOT EXISTS idx_user_rankings_user_vote ON user_rankings(user_id, vote_id);
CREATE INDEX IF NOT EXISTS idx_user_rankings_vote_id ON user_rankings(vote_id);
CREATE INDEX IF NOT EXISTS idx_user_ratings_user_vote ON user_ratings(user_id, vote_id);
CREATE INDEX IF NOT EXISTS idx_user_ratings_vote_id ON user_ratings(vote_id);
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/db/migration/V10__add_vote_types.sql
git commit -m "feat(migration): V10 新增投票類型相關資料表

- votes 新增 vote_type 欄位 (DEFAULT 'STANDARD')
- vote_options 新增 image_url 欄位
- 建立 user_rankings 表 (RANKING 專用)
- 建立 user_ratings 表 (RATING 專用，score CHECK 1~5)"
```

---

### Task 2: VoteType Enum + Entity Modifications

**Files:**
- Create: `src/main/java/com/vomattapi/domain/vote/VoteType.java`
- Modify: `src/main/java/com/vomattapi/domain/vote/Vote.java`
- Modify: `src/main/java/com/vomattapi/domain/vote/VoteOption.java`

- [ ] **Step 1: 建立 VoteType 列舉**

```java
package com.vomattapi.domain.vote;

public enum VoteType {
    STANDARD,   // 標準單選/多選
    IMAGE,      // 選項附帶圖片
    RANKING,    // 拖拉排序
    YES_NO,     // 贊成/反對
    RATING      // 1~5 星評分
}
```

- [ ] **Step 2: Vote 實體新增 voteType 欄位**

在 `Vote.java` 中新增：

```java
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

// 在 maxChoices 欄位下方加入
@Enumerated(EnumType.STRING)
@Column(name = "vote_type", nullable = false)
private VoteType voteType = VoteType.STANDARD;
```

- [ ] **Step 3: VoteOption 實體新增 imageUrl 欄位**

在 `VoteOption.java` 中，`displayOrder` 欄位下方新增：

```java
@Size(max = 500)
@Column(name = "image_url")
private String imageUrl;
```

- [ ] **Step 4: 確認編譯通過**

Run: `mvn compile -q`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vomattapi/domain/vote/VoteType.java \
        src/main/java/com/vomattapi/domain/vote/Vote.java \
        src/main/java/com/vomattapi/domain/vote/VoteOption.java
git commit -m "feat(entity): 新增 VoteType 列舉，Vote/VoteOption 擴充欄位

- VoteType: STANDARD, IMAGE, RANKING, YES_NO, RATING
- Vote 新增 voteType 欄位 (預設 STANDARD)
- VoteOption 新增 imageUrl 欄位"
```

---

### Task 3: UserRanking & UserRating Entities + Repositories

**Files:**
- Create: `src/main/java/com/vomattapi/domain/vote/UserRanking.java`
- Create: `src/main/java/com/vomattapi/domain/vote/UserRating.java`
- Create: `src/main/java/com/vomattapi/domain/vote/repository/UserRankingRepository.java`
- Create: `src/main/java/com/vomattapi/domain/vote/repository/UserRatingRepository.java`

- [ ] **Step 1: 建立 UserRanking 實體**

```java
package com.vomattapi.domain.vote;

import com.vomattapi.domain.common.BaseEntity;
import com.vomattapi.domain.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity
@Table(name = "user_rankings", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "vote_id", "option_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"user", "vote", "option"})
public class UserRanking extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vote_id")
    private Vote vote;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "option_id")
    private VoteOption option;

    @NotNull
    @Column(name = "rank_position")
    private Integer rankPosition;
}
```

- [ ] **Step 2: 建立 UserRating 實體**

```java
package com.vomattapi.domain.vote;

import com.vomattapi.domain.common.BaseEntity;
import com.vomattapi.domain.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity
@Table(name = "user_ratings", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "vote_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"user", "vote"})
public class UserRating extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vote_id")
    private Vote vote;

    @NotNull
    @Min(1)
    @Max(5)
    @Column(name = "score")
    private Integer score;
}
```

- [ ] **Step 3: 建立 UserRankingRepository**

```java
package com.vomattapi.domain.vote.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.vomattapi.domain.vote.UserRanking;

@Repository
public interface UserRankingRepository extends JpaRepository<UserRanking, UUID> {

    List<UserRanking> findByUserIdAndVoteId(UUID userId, UUID voteId);

    boolean existsByUserIdAndVoteId(UUID userId, UUID voteId);

    @Transactional
    void deleteByUserIdAndVoteId(UUID userId, UUID voteId);

    @Query("SELECT COUNT(DISTINCT ur.user.id) FROM UserRanking ur WHERE ur.vote.id = :voteId")
    long countDistinctUserByVoteId(@Param("voteId") UUID voteId);

    @Query("SELECT ur.option.id, AVG(ur.rankPosition) FROM UserRanking ur " +
           "WHERE ur.vote.id = :voteId GROUP BY ur.option.id")
    List<Object[]> avgRankByOptionForVote(@Param("voteId") UUID voteId);
}
```

- [ ] **Step 4: 建立 UserRatingRepository**

```java
package com.vomattapi.domain.vote.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.vomattapi.domain.vote.UserRating;

@Repository
public interface UserRatingRepository extends JpaRepository<UserRating, UUID> {

    Optional<UserRating> findByUserIdAndVoteId(UUID userId, UUID voteId);

    boolean existsByUserIdAndVoteId(UUID userId, UUID voteId);

    @Query("SELECT COUNT(ur) FROM UserRating ur WHERE ur.vote.id = :voteId")
    long countByVoteId(@Param("voteId") UUID voteId);

    @Query("SELECT AVG(ur.score) FROM UserRating ur WHERE ur.vote.id = :voteId")
    Double avgScoreByVoteId(@Param("voteId") UUID voteId);

    @Query("SELECT ur.score, COUNT(ur) FROM UserRating ur " +
           "WHERE ur.vote.id = :voteId GROUP BY ur.score ORDER BY ur.score")
    List<Object[]> scoreDistributionByVoteId(@Param("voteId") UUID voteId);
}
```

- [ ] **Step 5: VoteServiceImpl 注入新 Repository**

在 `VoteServiceImpl.java` 的 `tagRepository` 下方新增：

```java
private final UserRankingRepository userRankingRepository;
private final UserRatingRepository userRatingRepository;
```

新增 import：

```java
import com.vomattapi.domain.vote.repository.UserRankingRepository;
import com.vomattapi.domain.vote.repository.UserRatingRepository;
```

**重要**：此時只注入，不新增方法。這確保後續 Task 的 `@InjectMocks` 不會因缺少依賴而失敗。

- [ ] **Step 6: VoteServiceImplTest 新增 @Mock 欄位**

在 `VoteServiceImplTest.java` 的 `@Mock TagRepository tagRepository;` 下方新增：

```java
@Mock UserRankingRepository userRankingRepository;
@Mock UserRatingRepository userRatingRepository;
```

新增 import：

```java
import com.vomattapi.domain.vote.repository.UserRankingRepository;
import com.vomattapi.domain.vote.repository.UserRatingRepository;
```

- [ ] **Step 7: 確認編譯通過 + 現有測試不受影響**

Run: `mvn clean test -q`
Expected: BUILD SUCCESS, 所有現有測試通過

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/vomattapi/domain/vote/UserRanking.java \
        src/main/java/com/vomattapi/domain/vote/UserRating.java \
        src/main/java/com/vomattapi/domain/vote/repository/UserRankingRepository.java \
        src/main/java/com/vomattapi/domain/vote/repository/UserRatingRepository.java \
        src/main/java/com/vomattapi/application/service/impl/VoteServiceImpl.java \
        src/test/java/com/vomattapi/service/VoteServiceImplTest.java
git commit -m "feat(entity): 新增 UserRanking、UserRating 實體與 Repository

- UserRanking: RANKING 類型的排名記錄 (user, vote, option, rankPosition)
- UserRating: RATING 類型的評分記錄 (user, vote, score 1~5)
- Repository 包含聚合查詢 (平均排名、評分分佈)
- VoteServiceImpl 注入新 Repository（為後續 Task 做準備）
- VoteServiceImplTest 新增對應 @Mock 欄位"
```

---

### Task 4: @ValidImageUrl 自定義驗證器 (TDD)

**Files:**
- Create: `src/test/java/com/vomattapi/validation/ImageUrlValidatorTest.java`
- Create: `src/main/java/com/vomattapi/application/validation/ValidImageUrl.java`
- Create: `src/main/java/com/vomattapi/application/validation/ImageUrlValidator.java`

- [ ] **Step 1: 寫失敗測試**

```java
package com.vomattapi.validation;

import com.vomattapi.application.validation.ImageUrlValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("ImageUrlValidator")
class ImageUrlValidatorTest {

    private ImageUrlValidator validator;

    @Mock
    private ConstraintValidatorContext context;

    @BeforeEach
    void setUp() {
        validator = new ImageUrlValidator();
    }

    @Nested
    @DisplayName("合法 URL")
    class ValidUrls {
        @Test
        @DisplayName("應該在 null 時通過（optional 欄位）")
        void shouldPassWhenNull() {
            assertThat(validator.isValid(null, context)).isTrue();
        }

        @Test
        @DisplayName("應該在空字串時通過")
        void shouldPassWhenEmpty() {
            assertThat(validator.isValid("", context)).isTrue();
        }

        @Test
        @DisplayName("應該在合法 HTTPS URL 時通過")
        void shouldPassWhenValidHttpsUrl() {
            assertThat(validator.isValid("https://example.com/image.jpg", context)).isTrue();
        }

        @Test
        @DisplayName("應該在含路徑的 HTTPS URL 時通過")
        void shouldPassWhenHttpsUrlWithPath() {
            assertThat(validator.isValid("https://cdn.example.com/images/photo.png", context)).isTrue();
        }
    }

    @Nested
    @DisplayName("非法 URL")
    class InvalidUrls {
        @Test
        @DisplayName("應該在 HTTP URL 時拒絕")
        void shouldRejectHttpUrl() {
            assertThat(validator.isValid("http://example.com/image.jpg", context)).isFalse();
        }

        @Test
        @DisplayName("應該在 javascript: URL 時拒絕")
        void shouldRejectJavascriptUrl() {
            assertThat(validator.isValid("javascript:alert(1)", context)).isFalse();
        }

        @Test
        @DisplayName("應該在 data: URL 時拒絕")
        void shouldRejectDataUrl() {
            assertThat(validator.isValid("data:image/png;base64,abc", context)).isFalse();
        }

        @Test
        @DisplayName("應該在超過 500 字元時拒絕")
        void shouldRejectUrlExceeding500Chars() {
            String longUrl = "https://example.com/" + "a".repeat(481);
            assertThat(longUrl.length()).isGreaterThan(500);
            assertThat(validator.isValid(longUrl, context)).isFalse();
        }
    }

    @Nested
    @DisplayName("SSRF 防護 - 內部 IP")
    class SsrfProtection {
        @Test
        @DisplayName("應該拒絕 127.x.x.x (loopback)")
        void shouldRejectLoopback() {
            assertThat(validator.isValid("https://127.0.0.1/image.jpg", context)).isFalse();
        }

        @Test
        @DisplayName("應該拒絕 10.x.x.x (private)")
        void shouldRejectPrivate10() {
            assertThat(validator.isValid("https://10.0.0.1/image.jpg", context)).isFalse();
        }

        @Test
        @DisplayName("應該拒絕 172.16~31.x.x (private)")
        void shouldRejectPrivate172() {
            assertThat(validator.isValid("https://172.16.0.1/image.jpg", context)).isFalse();
        }

        @Test
        @DisplayName("應該拒絕 192.168.x.x (private)")
        void shouldRejectPrivate192() {
            assertThat(validator.isValid("https://192.168.1.1/image.jpg", context)).isFalse();
        }

        @Test
        @DisplayName("應該拒絕 169.254.x.x (link-local)")
        void shouldRejectLinkLocal() {
            assertThat(validator.isValid("https://169.254.169.254/metadata", context)).isFalse();
        }

        @Test
        @DisplayName("應該拒絕 localhost")
        void shouldRejectLocalhost() {
            assertThat(validator.isValid("https://localhost/image.jpg", context)).isFalse();
        }
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -pl . -Dtest="com.vomattapi.validation.ImageUrlValidatorTest" -q`
Expected: FAIL（類別不存在）

- [ ] **Step 3: 建立 @ValidImageUrl 註解**

```java
package com.vomattapi.application.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

@Documented
@Constraint(validatedBy = ImageUrlValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidImageUrl {
    String message() default "Invalid image URL: must be HTTPS and not point to internal networks";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
```

- [ ] **Step 4: 實作 ImageUrlValidator**

```java
package com.vomattapi.application.validation;

import java.net.URI;
import java.util.regex.Pattern;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ImageUrlValidator implements ConstraintValidator<ValidImageUrl, String> {

    private static final int MAX_LENGTH = 500;

    // 匹配內部 IP 範圍
    private static final Pattern INTERNAL_IP_PATTERN = Pattern.compile(
        "^(127\\.)|(10\\.)|(172\\.(1[6-9]|2[0-9]|3[01])\\.)|(192\\.168\\.)|(169\\.254\\.)"
    );

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // null 或空字串視為合法（optional 欄位，由 @NotBlank 等其他註解控制必填）
        if (value == null || value.isBlank()) {
            return true;
        }

        // 長度檢查
        if (value.length() > MAX_LENGTH) {
            return false;
        }

        // 必須以 https:// 開頭
        if (!value.toLowerCase().startsWith("https://")) {
            return false;
        }

        // 解析 URI 取得 host
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (host == null) {
                return false;
            }

            // 禁止 localhost
            if ("localhost".equalsIgnoreCase(host)) {
                return false;
            }

            // 禁止內部 IP
            if (INTERNAL_IP_PATTERN.matcher(host).find()) {
                return false;
            }

            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
```

- [ ] **Step 5: 執行測試確認通過**

Run: `mvn test -pl . -Dtest="com.vomattapi.validation.ImageUrlValidatorTest" -q`
Expected: ALL PASS（14 tests）

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vomattapi/application/validation/ \
        src/test/java/com/vomattapi/validation/ImageUrlValidatorTest.java
git commit -m "feat(validation): 新增 @ValidImageUrl 驗證器（SSRF 防護）

- 強制 HTTPS、禁止內部 IP 範圍
- 禁止 localhost、javascript:、data: 協議
- 長度限制 500 字元
- 14 個單元測試全部通過"
```

---

### Task 5: Request & Response DTOs

**Files:**
- Create: `src/main/java/com/vomattapi/application/dto/request/RankRequest.java`
- Create: `src/main/java/com/vomattapi/application/dto/request/RateRequest.java`
- Modify: `src/main/java/com/vomattapi/application/dto/request/CreateVoteRequest.java`
- Modify: `src/main/java/com/vomattapi/application/dto/response/VoteResponse.java`
- Modify: `src/main/java/com/vomattapi/application/dto/response/VoteResultResponse.java`
- Modify: `src/main/java/com/vomattapi/application/dto/response/ErrorType.java`

- [ ] **Step 1: 建立 RankRequest DTO**

```java
package com.vomattapi.application.dto.request;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class RankRequest {

    @NotEmpty(message = "Rankings cannot be empty")
    @Valid
    private List<RankEntry> rankings;

    @Data
    public static class RankEntry {
        @NotNull(message = "Option ID is required")
        private UUID optionId;

        @NotNull(message = "Position is required")
        @Min(value = 1, message = "Position must be at least 1")
        private Integer position;
    }
}
```

- [ ] **Step 2: 建立 RateRequest DTO**

```java
package com.vomattapi.application.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RateRequest {

    @NotNull(message = "Score is required")
    @Min(value = 1, message = "Score must be between 1 and 5")
    @Max(value = 5, message = "Score must be between 1 and 5")
    private Integer score;
}
```

- [ ] **Step 3: 修改 CreateVoteRequest**

在 `CreateVoteRequest.java` 的 `isAnonymous` 欄位下方新增：

```java
private String voteType;  // optional，預設 STANDARD
```

在內部類 `VoteOptionRequest` 的 `displayOrder` 欄位下方新增：

```java
@com.vomattapi.application.validation.ValidImageUrl
private String imageUrl;
```

- [ ] **Step 4: 修改 VoteResponse**

在 `VoteResponse.java` 的 `isAnonymous` 欄位下方新增：

```java
private String voteType;
```

在內部類 `VoteOptionResponse` 的 `description` 欄位下方新增：

```java
private String imageUrl;
```

- [ ] **Step 5: 修改 VoteResultResponse**

在 `VoteResultResponse.java` 新增欄位與內部類以支援多類型結果：

```java
// VoteResultResponse 類別中，isVotingActive 下方新增
private String voteType;

// RATING 類型專用欄位
private Double averageScore;
private Map<Integer, Long> scoreDistribution;
```

在 `VoteOptionResultResponse` 內部類中新增：

```java
// RANKING 類型專用欄位
private Double averageRank;
private Long bordaScore;
```

注意：需要在 VoteResultResponse 頂部加入 `import java.util.Map;`。

- [ ] **Step 6: 修改 ErrorType**

在 `ErrorType.java` 的 `VOTE_OPTION_MISMATCH` 下方新增：

```java
// 投票類型相關
INVALID_VOTE_TYPE("INVALID_VOTE_TYPE", "Invalid vote type"),
VOTE_TYPE_MISMATCH("VOTE_TYPE_MISMATCH", "Operation not supported for this vote type"),
YES_NO_REQUIRES_TWO_OPTIONS("YES_NO_REQUIRES_TWO_OPTIONS", "YES_NO vote type requires exactly 2 options"),
IMAGE_REQUIRES_IMAGE_URL("IMAGE_REQUIRES_IMAGE_URL", "IMAGE vote type requires imageUrl for all options"),
RANKING_INCOMPLETE("RANKING_INCOMPLETE", "All options must be ranked"),
RATING_DUPLICATE("RATING_DUPLICATE", "User has already rated this vote");
```

- [ ] **Step 7: 確認編譯通過**

Run: `mvn compile -q`

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/vomattapi/application/dto/request/RankRequest.java \
        src/main/java/com/vomattapi/application/dto/request/RateRequest.java \
        src/main/java/com/vomattapi/application/dto/request/CreateVoteRequest.java \
        src/main/java/com/vomattapi/application/dto/response/VoteResponse.java \
        src/main/java/com/vomattapi/application/dto/response/VoteResultResponse.java \
        src/main/java/com/vomattapi/application/dto/response/ErrorType.java
git commit -m "feat(dto): 新增投票類型相關 DTO

- RankRequest: RANKING 投票請求（optionId + position 清單）
- RateRequest: RATING 投票請求（score 1~5）
- CreateVoteRequest: 新增 voteType、imageUrl
- VoteResponse/VoteResultResponse: 支援多類型回應格式
- ErrorType: 新增 6 個投票類型錯誤碼"
```

---

### Task 6: VoteService — CreateVote 類型驗證 (TDD)

**Files:**
- Modify: `src/test/java/com/vomattapi/service/VoteServiceImplTest.java`
- Modify: `src/main/java/com/vomattapi/application/service/impl/VoteServiceImpl.java`

本 Task 新增建立投票時的類型驗證邏輯，不包含 rank/rate 端點。

- [ ] **Step 1: 寫失敗測試 — 類型驗證**

在 `VoteServiceImplTest.java` 中新增 `@Nested` class：

```java
@Nested
@DisplayName("CreateVote 類型驗證")
class CreateVoteTypeValidation {

    @BeforeEach
    void setUp() {
        lenient().when(voteConfig.getMinOptionsPerVote()).thenReturn(2);
        lenient().when(voteConfig.getMaxOptionsPerVote()).thenReturn(20);
    }

    @Test
    @DisplayName("應該在 voteType 為 null 時預設為 STANDARD")
    void shouldDefaultToStandardWhenVoteTypeNull() {
        CreateVoteRequest request = createBaseRequest(3);
        request.setVoteType(null);

        when(userRepository.findById(any(UUID.class))).thenReturn(Optional.of(user));
        when(voteRepository.save(any(Vote.class))).thenAnswer(inv -> {
            Vote v = inv.getArgument(0);
            v.setId(UUID.randomUUID());
            return v;
        });
        when(voteOptionRepository.findByVoteIdOrderByDisplayOrder(any())).thenReturn(List.of());
        when(userVoteRepository.countByVoteId(any())).thenReturn(0L);
        when(voteMapper.toResponse(any(), any(), any(), anyLong())).thenReturn(new VoteResponse());

        voteService.createVote(request, userId.toString());

        verify(voteRepository, atLeastOnce()).save(argThat(v ->
            v.getVoteType() == VoteType.STANDARD
        ));
    }

    @Test
    @DisplayName("應該在 YES_NO 類型選項數不等於 2 時拋出異常")
    void shouldThrowWhenYesNoNotTwoOptions() {
        CreateVoteRequest request = createBaseRequest(3);
        request.setVoteType("YES_NO");

        when(userRepository.findById(any(UUID.class))).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> voteService.createVote(request, userId.toString()))
            .isInstanceOf(BusinessRuleViolationException.class)
            .hasMessageContaining("2");
    }

    @Test
    @DisplayName("應該在 YES_NO 類型恰好 2 個選項時成功")
    void shouldSucceedWhenYesNoWithTwoOptions() {
        CreateVoteRequest request = createBaseRequest(2);
        request.setVoteType("YES_NO");

        when(userRepository.findById(any(UUID.class))).thenReturn(Optional.of(user));
        when(voteRepository.save(any(Vote.class))).thenAnswer(inv -> {
            Vote v = inv.getArgument(0);
            v.setId(UUID.randomUUID());
            return v;
        });
        when(voteOptionRepository.findByVoteIdOrderByDisplayOrder(any())).thenReturn(List.of());
        when(userVoteRepository.countByVoteId(any())).thenReturn(0L);
        when(voteMapper.toResponse(any(), any(), any(), anyLong())).thenReturn(new VoteResponse());

        voteService.createVote(request, userId.toString());

        verify(voteRepository, atLeastOnce()).save(argThat(v ->
            v.getVoteType() == VoteType.YES_NO
        ));
    }

    @Test
    @DisplayName("應該在 IMAGE 類型選項缺少 imageUrl 時拋出異常")
    void shouldThrowWhenImageOptionMissingUrl() {
        CreateVoteRequest request = createBaseRequest(2);
        request.setVoteType("IMAGE");
        // options 沒有設定 imageUrl

        when(userRepository.findById(any(UUID.class))).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> voteService.createVote(request, userId.toString()))
            .isInstanceOf(BusinessRuleViolationException.class)
            .hasMessageContaining("imageUrl");
    }

    @Test
    @DisplayName("應該在無效 voteType 時拋出異常")
    void shouldThrowWhenInvalidVoteType() {
        CreateVoteRequest request = createBaseRequest(2);
        request.setVoteType("INVALID");

        when(userRepository.findById(any(UUID.class))).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> voteService.createVote(request, userId.toString()))
            .isInstanceOf(BusinessRuleViolationException.class)
            .hasMessageContaining("投票類型");
    }

    private CreateVoteRequest createBaseRequest(int optionCount) {
        CreateVoteRequest request = new CreateVoteRequest();
        request.setTitle("Test Vote");
        request.setDescription("Test");
        List<CreateVoteRequest.VoteOptionRequest> options = new java.util.ArrayList<>();
        for (int i = 0; i < optionCount; i++) {
            CreateVoteRequest.VoteOptionRequest opt = new CreateVoteRequest.VoteOptionRequest();
            opt.setText("Option " + (i + 1));
            options.add(opt);
        }
        request.setOptions(options);
        return request;
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -pl . -Dtest="com.vomattapi.service.VoteServiceImplTest#CreateVote*" -q`
Expected: FAIL

- [ ] **Step 3: 修改 VoteServiceImpl — createVote 方法**

在 `VoteServiceImpl.java` 的 `createVote` 方法中，`validateCreateVoteRequest(request)` 之後加入：

```java
VoteType voteType = resolveVoteType(request.getVoteType());
validateVoteTypeConstraints(voteType, request);
```

設定 voteType，在 `vote.setAnonymous(...)` 之後加入：

```java
vote.setVoteType(voteType);
```

在建立 VoteOption 時加入 imageUrl：

```java
// 在現有 option.setDisplayOrder(...) 之後加入
if (optionRequest.getImageUrl() != null) {
    option.setImageUrl(optionRequest.getImageUrl());
}
```

新增私有方法：

```java
private VoteType resolveVoteType(String voteTypeStr) {
    if (voteTypeStr == null || voteTypeStr.isBlank()) {
        return VoteType.STANDARD;
    }
    try {
        return VoteType.valueOf(voteTypeStr.toUpperCase());
    } catch (IllegalArgumentException e) {
        throw new BusinessRuleViolationException("無效的投票類型: " + voteTypeStr);
    }
}

private void validateVoteTypeConstraints(VoteType voteType, CreateVoteRequest request) {
    switch (voteType) {
        case YES_NO -> {
            if (request.getOptions().size() != 2) {
                throw new BusinessRuleViolationException("YES_NO 類型必須恰好有 2 個選項");
            }
        }
        case IMAGE -> {
            boolean allHaveImages = request.getOptions().stream()
                .allMatch(opt -> opt.getImageUrl() != null && !opt.getImageUrl().isBlank());
            if (!allHaveImages) {
                throw new BusinessRuleViolationException("IMAGE 類型的所有選項都需要 imageUrl");
            }
        }
        default -> {
            // STANDARD, RANKING, RATING 無額外限制
        }
    }
}
```

- [ ] **Step 4: 執行測試確認通過**

Run: `mvn test -pl . -Dtest="com.vomattapi.service.VoteServiceImplTest" -q`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vomattapi/application/service/impl/VoteServiceImpl.java \
        src/test/java/com/vomattapi/service/VoteServiceImplTest.java
git commit -m "feat(service): CreateVote 支援投票類型驗證

- voteType null/空值預設 STANDARD
- YES_NO 必須恰好 2 個選項
- IMAGE 所有選項必須有 imageUrl
- 無效 voteType 拋出 BusinessRuleViolationException
- 5 個新增測試全部通過"
```

---

### Task 7: VoteService — Ranking 投票邏輯 (TDD)

**Files:**
- Create: `src/test/java/com/vomattapi/service/RankingServiceTest.java`
- Modify: `src/main/java/com/vomattapi/application/service/VoteService.java`
- Modify: `src/main/java/com/vomattapi/application/service/impl/VoteServiceImpl.java`

- [ ] **Step 1: 寫失敗測試**

```java
package com.vomattapi.service;

import com.vomattapi.application.dto.request.RankRequest;
import com.vomattapi.application.dto.response.VoteResponse;
import com.vomattapi.application.exception.BusinessRuleViolationException;
import com.vomattapi.application.exception.VoteNotFoundException;
import com.vomattapi.application.exception.VotingNotAllowedException;
import com.vomattapi.application.mapper.VoteMapper;
import com.vomattapi.application.service.impl.VoteServiceImpl;
import com.vomattapi.domain.user.User;
import com.vomattapi.domain.user.repository.UserRepository;
import com.vomattapi.domain.vote.*;
import com.vomattapi.domain.vote.repository.*;
import com.vomattapi.infrastructure.config.VoteConfigurationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("VoteService - Ranking 邏輯")
class RankingServiceTest {

    @Mock VoteRepository voteRepository;
    @Mock VoteOptionRepository voteOptionRepository;
    @Mock UserVoteRepository userVoteRepository;
    @Mock UserRepository userRepository;
    @Mock TagRepository tagRepository;
    @Mock UserRankingRepository userRankingRepository;
    @Mock UserRatingRepository userRatingRepository;
    @Mock VoteConfigurationProperties voteConfig;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock VoteMapper voteMapper;

    @InjectMocks
    VoteServiceImpl voteService;

    private UUID userId;
    private UUID voteId;
    private UUID optionId1;
    private UUID optionId2;
    private UUID optionId3;
    private User user;
    private Vote vote;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        voteId = UUID.randomUUID();
        optionId1 = UUID.randomUUID();
        optionId2 = UUID.randomUUID();
        optionId3 = UUID.randomUUID();

        user = new User();
        user.setId(userId);
        user.setUsername("testuser");

        vote = new Vote();
        vote.setId(voteId);
        vote.setActive(true);
        vote.setVoteType(VoteType.RANKING);
    }

    @Nested
    @DisplayName("submitRanking")
    class SubmitRanking {

        @Test
        @DisplayName("應該在投票不存在時拋出 VoteNotFoundException")
        void shouldThrowWhenVoteNotFound() {
            when(voteRepository.findByIdAndIsActiveTrue(voteId)).thenReturn(Optional.empty());

            RankRequest request = createRankRequest();

            assertThatThrownBy(() -> voteService.submitRanking(voteId.toString(), request, userId.toString()))
                .isInstanceOf(VoteNotFoundException.class);
        }

        @Test
        @DisplayName("應該在投票類型不是 RANKING 時拋出 BusinessRuleViolationException")
        void shouldThrowWhenNotRankingType() {
            vote.setVoteType(VoteType.STANDARD);
            when(voteRepository.findByIdAndIsActiveTrue(voteId)).thenReturn(Optional.of(vote));

            RankRequest request = createRankRequest();

            assertThatThrownBy(() -> voteService.submitRanking(voteId.toString(), request, userId.toString()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("RANKING");
        }

        @Test
        @DisplayName("應該在排名不完整時拋出 BusinessRuleViolationException")
        void shouldThrowWhenRankingIncomplete() {
            when(voteRepository.findByIdAndIsActiveTrue(voteId)).thenReturn(Optional.of(vote));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            // 投票有 3 個選項，但只排了 2 個
            VoteOption opt1 = createOption(optionId1);
            VoteOption opt2 = createOption(optionId2);
            VoteOption opt3 = createOption(optionId3);
            when(voteOptionRepository.findByVoteIdOrderByDisplayOrder(voteId))
                .thenReturn(List.of(opt1, opt2, opt3));

            RankRequest request = new RankRequest();
            RankRequest.RankEntry entry1 = new RankRequest.RankEntry();
            entry1.setOptionId(optionId1);
            entry1.setPosition(1);
            RankRequest.RankEntry entry2 = new RankRequest.RankEntry();
            entry2.setOptionId(optionId2);
            entry2.setPosition(2);
            request.setRankings(List.of(entry1, entry2));

            assertThatThrownBy(() -> voteService.submitRanking(voteId.toString(), request, userId.toString()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("所有選項");
        }

        @Test
        @DisplayName("應該在已排名時先刪除舊排名再儲存新排名")
        void shouldReplaceExistingRanking() {
            when(voteRepository.findByIdAndIsActiveTrue(voteId)).thenReturn(Optional.of(vote));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRankingRepository.existsByUserIdAndVoteId(userId, voteId)).thenReturn(true);

            VoteOption opt1 = createOption(optionId1);
            VoteOption opt2 = createOption(optionId2);
            when(voteOptionRepository.findByVoteIdOrderByDisplayOrder(voteId))
                .thenReturn(List.of(opt1, opt2));
            when(voteOptionRepository.findById(optionId1)).thenReturn(Optional.of(opt1));
            when(voteOptionRepository.findById(optionId2)).thenReturn(Optional.of(opt2));
            when(voteMapper.toResponse(any(), any(), any(), anyLong())).thenReturn(new VoteResponse());

            RankRequest request = new RankRequest();
            RankRequest.RankEntry e1 = new RankRequest.RankEntry();
            e1.setOptionId(optionId1);
            e1.setPosition(1);
            RankRequest.RankEntry e2 = new RankRequest.RankEntry();
            e2.setOptionId(optionId2);
            e2.setPosition(2);
            request.setRankings(List.of(e1, e2));

            voteService.submitRanking(voteId.toString(), request, userId.toString());

            verify(userRankingRepository).deleteByUserIdAndVoteId(userId, voteId);
            verify(userRankingRepository, times(2)).save(any(UserRanking.class));
        }

        @Test
        @DisplayName("應該成功提交排名")
        void shouldSubmitRankingSuccessfully() {
            when(voteRepository.findByIdAndIsActiveTrue(voteId)).thenReturn(Optional.of(vote));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRankingRepository.existsByUserIdAndVoteId(userId, voteId)).thenReturn(false);

            VoteOption opt1 = createOption(optionId1);
            VoteOption opt2 = createOption(optionId2);
            when(voteOptionRepository.findByVoteIdOrderByDisplayOrder(voteId))
                .thenReturn(List.of(opt1, opt2));
            when(voteOptionRepository.findById(optionId1)).thenReturn(Optional.of(opt1));
            when(voteOptionRepository.findById(optionId2)).thenReturn(Optional.of(opt2));
            when(voteMapper.toResponse(any(), any(), any(), anyLong())).thenReturn(new VoteResponse());

            RankRequest request = new RankRequest();
            RankRequest.RankEntry e1 = new RankRequest.RankEntry();
            e1.setOptionId(optionId1);
            e1.setPosition(1);
            RankRequest.RankEntry e2 = new RankRequest.RankEntry();
            e2.setOptionId(optionId2);
            e2.setPosition(2);
            request.setRankings(List.of(e1, e2));

            VoteResponse result = voteService.submitRanking(voteId.toString(), request, userId.toString());

            assertThat(result).isNotNull();
            verify(userRankingRepository, times(2)).save(any(UserRanking.class));
            verify(userRankingRepository, never()).deleteByUserIdAndVoteId(any(), any());
        }
    }

    private RankRequest createRankRequest() {
        RankRequest request = new RankRequest();
        RankRequest.RankEntry e1 = new RankRequest.RankEntry();
        e1.setOptionId(optionId1);
        e1.setPosition(1);
        RankRequest.RankEntry e2 = new RankRequest.RankEntry();
        e2.setOptionId(optionId2);
        e2.setPosition(2);
        request.setRankings(List.of(e1, e2));
        return request;
    }

    private VoteOption createOption(UUID id) {
        VoteOption option = new VoteOption();
        option.setId(id);
        option.setVote(vote);
        return option;
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -pl . -Dtest="com.vomattapi.service.RankingServiceTest" -q`
Expected: FAIL（submitRanking 方法不存在）

- [ ] **Step 3: VoteService 介面新增方法**

在 `VoteService.java` 新增：

```java
import com.vomattapi.application.dto.request.RankRequest;
import com.vomattapi.application.dto.request.RateRequest;

VoteResponse submitRanking(String voteId, RankRequest request, String userId);

VoteResponse submitRating(String voteId, RateRequest request, String userId);
```

- [ ] **Step 4: VoteServiceImpl 實作 submitRanking**

注意：`UserRankingRepository` 和 `UserRatingRepository` 已在 Task 3 注入。

實作方法：

```java
@Override
public VoteResponse submitRanking(String voteId, RankRequest request, String userId) {
    UUID voteUuid = UUID.fromString(voteId);
    UUID userUuid = UUID.fromString(userId);

    Vote vote = voteRepository.findByIdAndIsActiveTrue(voteUuid)
        .orElseThrow(() -> new VoteNotFoundException(voteId));

    if (vote.getVoteType() != VoteType.RANKING) {
        throw new BusinessRuleViolationException("此投票不是 RANKING 類型");
    }

    if (!vote.isVotingActive()) {
        throw new VotingNotAllowedException(voteId, "投票期間已結束或尚未開始");
    }

    User user = userRepository.findById(userUuid)
        .orElseThrow(() -> new EntityNotFoundException("User", userId));

    // 驗證排名覆蓋所有選項
    List<VoteOption> options = voteOptionRepository.findByVoteIdOrderByDisplayOrder(voteUuid);
    if (request.getRankings().size() != options.size()) {
        throw new BusinessRuleViolationException("必須對所有選項進行排名");
    }

    // 如果已有排名，先刪除
    if (userRankingRepository.existsByUserIdAndVoteId(userUuid, voteUuid)) {
        userRankingRepository.deleteByUserIdAndVoteId(userUuid, voteUuid);
    }

    // 儲存新排名
    for (RankRequest.RankEntry entry : request.getRankings()) {
        VoteOption option = voteOptionRepository.findById(entry.getOptionId())
            .orElseThrow(() -> new EntityNotFoundException("VoteOption", entry.getOptionId().toString()));

        UserRanking ranking = new UserRanking();
        ranking.setUser(user);
        ranking.setVote(vote);
        ranking.setOption(option);
        ranking.setRankPosition(entry.getPosition());
        userRankingRepository.save(ranking);
    }

    log.info("User {} submitted ranking for vote {}", userId, voteId);
    return convertToVoteResponse(vote);
}
```

同時為 `submitRating` 加入暫時的空實作（下一個 Task 完成）：

```java
@Override
public VoteResponse submitRating(String voteId, RateRequest request, String userId) {
    throw new UnsupportedOperationException("Rating not yet implemented");
}
```

- [ ] **Step 5: 執行測試確認通過**

Run: `mvn test -pl . -Dtest="com.vomattapi.service.RankingServiceTest" -q`
Expected: ALL PASS（5 tests）

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vomattapi/application/service/VoteService.java \
        src/main/java/com/vomattapi/application/service/impl/VoteServiceImpl.java \
        src/test/java/com/vomattapi/service/RankingServiceTest.java
git commit -m "feat(service): 實作 RANKING 投票邏輯

- submitRanking: 驗證類型、選項完整性、替換舊排名
- 拒絕非 RANKING 類型、不完整排名
- 5 個單元測試全部通過"
```

---

### Task 8: VoteService — Rating 投票邏輯 (TDD)

**Files:**
- Create: `src/test/java/com/vomattapi/service/RatingServiceTest.java`
- Modify: `src/main/java/com/vomattapi/application/service/impl/VoteServiceImpl.java`

- [ ] **Step 1: 寫失敗測試**

```java
package com.vomattapi.service;

import com.vomattapi.application.dto.request.RateRequest;
import com.vomattapi.application.dto.response.VoteResponse;
import com.vomattapi.application.exception.BusinessRuleViolationException;
import com.vomattapi.application.exception.VoteNotFoundException;
import com.vomattapi.application.mapper.VoteMapper;
import com.vomattapi.application.service.impl.VoteServiceImpl;
import com.vomattapi.domain.user.User;
import com.vomattapi.domain.user.repository.UserRepository;
import com.vomattapi.domain.vote.*;
import com.vomattapi.domain.vote.repository.*;
import com.vomattapi.infrastructure.config.VoteConfigurationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("VoteService - Rating 邏輯")
class RatingServiceTest {

    @Mock VoteRepository voteRepository;
    @Mock VoteOptionRepository voteOptionRepository;
    @Mock UserVoteRepository userVoteRepository;
    @Mock UserRepository userRepository;
    @Mock TagRepository tagRepository;
    @Mock UserRankingRepository userRankingRepository;
    @Mock UserRatingRepository userRatingRepository;
    @Mock VoteConfigurationProperties voteConfig;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock VoteMapper voteMapper;

    @InjectMocks
    VoteServiceImpl voteService;

    private UUID userId;
    private UUID voteId;
    private User user;
    private Vote vote;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        voteId = UUID.randomUUID();

        user = new User();
        user.setId(userId);
        user.setUsername("testuser");

        vote = new Vote();
        vote.setId(voteId);
        vote.setActive(true);
        vote.setVoteType(VoteType.RATING);
    }

    @Nested
    @DisplayName("submitRating")
    class SubmitRating {

        @Test
        @DisplayName("應該在投票不存在時拋出 VoteNotFoundException")
        void shouldThrowWhenVoteNotFound() {
            when(voteRepository.findByIdAndIsActiveTrue(voteId)).thenReturn(Optional.empty());

            RateRequest request = new RateRequest();
            request.setScore(4);

            assertThatThrownBy(() -> voteService.submitRating(voteId.toString(), request, userId.toString()))
                .isInstanceOf(VoteNotFoundException.class);
        }

        @Test
        @DisplayName("應該在投票類型不是 RATING 時拋出 BusinessRuleViolationException")
        void shouldThrowWhenNotRatingType() {
            vote.setVoteType(VoteType.STANDARD);
            when(voteRepository.findByIdAndIsActiveTrue(voteId)).thenReturn(Optional.of(vote));

            RateRequest request = new RateRequest();
            request.setScore(4);

            assertThatThrownBy(() -> voteService.submitRating(voteId.toString(), request, userId.toString()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("RATING");
        }

        @Test
        @DisplayName("應該在首次評分時成功儲存")
        void shouldSaveRatingSuccessfully() {
            when(voteRepository.findByIdAndIsActiveTrue(voteId)).thenReturn(Optional.of(vote));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRatingRepository.findByUserIdAndVoteId(userId, voteId)).thenReturn(Optional.empty());
            when(voteMapper.toResponse(any(), any(), any(), anyLong())).thenReturn(new VoteResponse());

            RateRequest request = new RateRequest();
            request.setScore(5);

            VoteResponse result = voteService.submitRating(voteId.toString(), request, userId.toString());

            assertThat(result).isNotNull();
            verify(userRatingRepository).save(any(UserRating.class));
        }

        @Test
        @DisplayName("應該在重複評分時更新舊評分")
        void shouldUpdateExistingRating() {
            UserRating existingRating = new UserRating();
            existingRating.setId(UUID.randomUUID());
            existingRating.setUser(user);
            existingRating.setVote(vote);
            existingRating.setScore(3);

            when(voteRepository.findByIdAndIsActiveTrue(voteId)).thenReturn(Optional.of(vote));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRatingRepository.findByUserIdAndVoteId(userId, voteId))
                .thenReturn(Optional.of(existingRating));
            when(voteMapper.toResponse(any(), any(), any(), anyLong())).thenReturn(new VoteResponse());

            RateRequest request = new RateRequest();
            request.setScore(5);

            voteService.submitRating(voteId.toString(), request, userId.toString());

            // 應該更新現有記錄而不是新建
            assertThat(existingRating.getScore()).isEqualTo(5);
            verify(userRatingRepository).save(existingRating);
            verify(userRatingRepository, times(1)).save(any(UserRating.class));
        }
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -pl . -Dtest="com.vomattapi.service.RatingServiceTest" -q`
Expected: FAIL

- [ ] **Step 3: 實作 submitRating**

在 `VoteServiceImpl.java` 替換暫時的 `submitRating` 實作：

```java
@Override
public VoteResponse submitRating(String voteId, RateRequest request, String userId) {
    UUID voteUuid = UUID.fromString(voteId);
    UUID userUuid = UUID.fromString(userId);

    Vote vote = voteRepository.findByIdAndIsActiveTrue(voteUuid)
        .orElseThrow(() -> new VoteNotFoundException(voteId));

    if (vote.getVoteType() != VoteType.RATING) {
        throw new BusinessRuleViolationException("此投票不是 RATING 類型");
    }

    if (!vote.isVotingActive()) {
        throw new VotingNotAllowedException(voteId, "投票期間已結束或尚未開始");
    }

    User user = userRepository.findById(userUuid)
        .orElseThrow(() -> new EntityNotFoundException("User", userId));

    // 更新或新建評分
    Optional<UserRating> existingRating = userRatingRepository.findByUserIdAndVoteId(userUuid, voteUuid);
    if (existingRating.isPresent()) {
        UserRating rating = existingRating.get();
        rating.setScore(request.getScore());
        userRatingRepository.save(rating);
    } else {
        UserRating rating = new UserRating();
        rating.setUser(user);
        rating.setVote(vote);
        rating.setScore(request.getScore());
        userRatingRepository.save(rating);
    }

    log.info("User {} submitted rating {} for vote {}", userId, request.getScore(), voteId);
    return convertToVoteResponse(vote);
}
```

- [ ] **Step 4: 執行測試確認通過**

Run: `mvn test -pl . -Dtest="com.vomattapi.service.RatingServiceTest" -q`
Expected: ALL PASS（4 tests）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vomattapi/application/service/impl/VoteServiceImpl.java \
        src/test/java/com/vomattapi/service/RatingServiceTest.java
git commit -m "feat(service): 實作 RATING 投票邏輯

- submitRating: 首次評分新建、重複評分更新
- 驗證類型必須為 RATING
- 4 個單元測試全部通過"
```

---

### Task 9: VoteMapper 擴充 + Results 多類型格式

**Files:**
- Modify: `src/main/java/com/vomattapi/application/mapper/VoteMapper.java`
- Modify: `src/main/java/com/vomattapi/application/service/impl/VoteServiceImpl.java`
- Modify: `src/main/java/com/vomattapi/application/service/VoteService.java` (如尚未修改)

- [ ] **Step 1: VoteMapper 支援 voteType 與 imageUrl**

在 `VoteMapper.toResponse()` 中，`response.setVotingActive(...)` 之後加入：

```java
response.setVoteType(vote.getVoteType() != null ? vote.getVoteType().name() : "STANDARD");
```

在 `toOptionResponse()` 中，`resp.setCreatedAt(...)` 之後加入：

```java
resp.setImageUrl(option.getImageUrl());
```

- [ ] **Step 2: VoteMapper 支援 RANKING 結果格式**

新增方法：

```java
public VoteResultResponse toRankingResultResponse(Vote vote, List<VoteOption> options,
                                                    long totalParticipants,
                                                    Map<UUID, Double> avgRanks,
                                                    Map<UUID, Long> bordaScores) {
    VoteResultResponse response = new VoteResultResponse();
    response.setId(vote.getId().toString());
    response.setTitle(vote.getTitle());
    response.setDescription(vote.getDescription());
    response.setCreatorId(vote.getCreator().getId().toString());
    response.setCreatorUsername(vote.getCreator().getUsername());
    response.setStartTime(vote.getStartTime());
    response.setEndTime(vote.getEndTime());
    response.setActive(vote.isActive());
    response.setAnonymous(vote.isAnonymous());
    response.setCreatedAt(vote.getCreatedAt());
    response.setVotingActive(vote.isVotingActive());
    response.setVoteType(vote.getVoteType().name());
    response.setTotalVotes(totalParticipants);
    response.setTotalParticipants(totalParticipants);

    List<VoteResultResponse.VoteOptionResultResponse> optionResults = options.stream()
        .map(opt -> {
            VoteResultResponse.VoteOptionResultResponse result = new VoteResultResponse.VoteOptionResultResponse();
            result.setId(opt.getId().toString());
            result.setText(opt.getText());
            result.setDescription(opt.getDescription());
            result.setDisplayOrder(opt.getDisplayOrder());
            result.setAverageRank(avgRanks.getOrDefault(opt.getId(), 0.0));
            result.setBordaScore(bordaScores.getOrDefault(opt.getId(), 0L));
            result.setVoters(List.of());
            return result;
        })
        .toList();
    response.setOptions(optionResults);
    return response;
}
```

- [ ] **Step 3: VoteMapper 支援 RATING 結果格式**

新增方法：

```java
public VoteResultResponse toRatingResultResponse(Vote vote, long totalParticipants,
                                                   Double averageScore,
                                                   Map<Integer, Long> distribution) {
    VoteResultResponse response = new VoteResultResponse();
    response.setId(vote.getId().toString());
    response.setTitle(vote.getTitle());
    response.setDescription(vote.getDescription());
    response.setCreatorId(vote.getCreator().getId().toString());
    response.setCreatorUsername(vote.getCreator().getUsername());
    response.setStartTime(vote.getStartTime());
    response.setEndTime(vote.getEndTime());
    response.setActive(vote.isActive());
    response.setAnonymous(vote.isAnonymous());
    response.setCreatedAt(vote.getCreatedAt());
    response.setVotingActive(vote.isVotingActive());
    response.setVoteType(vote.getVoteType().name());
    response.setTotalVotes(totalParticipants);
    response.setTotalParticipants(totalParticipants);
    response.setAverageScore(averageScore != null ? averageScore : 0.0);
    response.setScoreDistribution(distribution);
    response.setOptions(List.of());
    return response;
}
```

- [ ] **Step 4: VoteMapper.toResultResponse 新增 voteType**

在現有的 `toResultResponse()` 中，`response.setVotingActive(...)` 之後加入：

```java
response.setVoteType(vote.getVoteType() != null ? vote.getVoteType().name() : "STANDARD");
```

- [ ] **Step 5: VoteServiceImpl.getVoteResults 依類型分流**

修改 `VoteServiceImpl.getVoteResults()` 方法，依 voteType 分流：

```java
@Override
@Transactional(readOnly = true)
public VoteResultResponse getVoteResults(String voteId) {
    UUID voteUuid = UUID.fromString(voteId);
    Vote vote = voteRepository.findById(voteUuid)
        .orElseThrow(() -> new VoteNotFoundException(voteId));

    VoteType voteType = vote.getVoteType() != null ? vote.getVoteType() : VoteType.STANDARD;

    return switch (voteType) {
        case RANKING -> buildRankingResults(vote, voteUuid);
        case RATING -> buildRatingResults(vote, voteUuid);
        default -> buildStandardResults(vote, voteUuid);
    };
}

private VoteResultResponse buildStandardResults(Vote vote, UUID voteUuid) {
    int totalParticipants = (int) userVoteRepository.countDistinctUserByVoteId(voteUuid);
    long totalVoteCount = userVoteRepository.countByVoteId(voteUuid);
    Map<UUID, Long> optionCounts = buildOptionCountMap(voteUuid);
    List<VoteOption> options = voteOptionRepository.findByVoteIdOrderByDisplayOrder(voteUuid);
    return voteMapper.toResultResponse(vote, options, totalParticipants, optionCounts, totalVoteCount);
}

private VoteResultResponse buildRankingResults(Vote vote, UUID voteUuid) {
    long totalParticipants = userRankingRepository.countDistinctUserByVoteId(voteUuid);
    List<VoteOption> options = voteOptionRepository.findByVoteIdOrderByDisplayOrder(voteUuid);
    int optionCount = options.size();

    // 計算平均排名
    Map<UUID, Double> avgRanks = new HashMap<>();
    for (Object[] row : userRankingRepository.avgRankByOptionForVote(voteUuid)) {
        avgRanks.put((UUID) row[0], (Double) row[1]);
    }

    // Borda 計分：每位參與者給予排名第 k 的選項 (N-k) 分
    // 等價公式：totalBordaScore = totalParticipants * (optionCount - avgRank)
    // 此公式在所有參與者都對全部選項排名時數學上等價於逐筆加總
    Map<UUID, Long> bordaScores = new HashMap<>();
    for (Map.Entry<UUID, Double> entry : avgRanks.entrySet()) {
        long score = Math.round(totalParticipants * (optionCount - entry.getValue()));
        bordaScores.put(entry.getKey(), score);
    }

    return voteMapper.toRankingResultResponse(vote, options, totalParticipants, avgRanks, bordaScores);
}

private VoteResultResponse buildRatingResults(Vote vote, UUID voteUuid) {
    long totalParticipants = userRatingRepository.countByVoteId(voteUuid);
    Double averageScore = userRatingRepository.avgScoreByVoteId(voteUuid);

    Map<Integer, Long> distribution = new HashMap<>();
    for (int i = 1; i <= 5; i++) {
        distribution.put(i, 0L);
    }
    for (Object[] row : userRatingRepository.scoreDistributionByVoteId(voteUuid)) {
        distribution.put((Integer) row[0], (Long) row[1]);
    }

    return voteMapper.toRatingResultResponse(vote, totalParticipants, averageScore, distribution);
}
```

- [ ] **Step 6: 確認編譯通過 + 所有測試通過**

Run: `mvn clean test -q`

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/vomattapi/application/mapper/VoteMapper.java \
        src/main/java/com/vomattapi/application/service/impl/VoteServiceImpl.java
git commit -m "feat(mapper): VoteMapper 支援多類型結果格式

- VoteResponse/VoteResultResponse 新增 voteType、imageUrl
- RANKING 結果：平均排名 + Borda 計分
- RATING 結果：平均分數 + 分數分佈
- getVoteResults 依 voteType 分流計算"
```

---

### Task 10: Controller 端點 + 整合驗證

**Files:**
- Modify: `src/main/java/com/vomattapi/application/controller/VoteController.java`
- Modify: `src/test/java/com/vomattapi/controller/VoteControllerTest.java`

- [ ] **Step 1: VoteController 新增 rank 端點**

在 `VoteController.java` 的 `deactivateVote` 方法之前新增：

```java
@PostMapping("/{voteId}/rank")
@PreAuthorize("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')")
@Auditable(action = "RANK", resourceType = "VOTE", resourceIdIndex = 0)
@Operation(summary = "Submit ranking", description = "Submit option rankings for a RANKING type vote")
@ApiResponses(value = {
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Ranking submitted successfully"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid ranking or wrong vote type"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Vote not found")
})
public ResponseEntity<ApiResponse<VoteResponse>> submitRanking(
        @Parameter(description = "Vote ID", required = true)
        @PathVariable String voteId,
        @Valid @RequestBody RankRequest request,
        Authentication authentication) {
    UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
    VoteResponse response = voteService.submitRanking(voteId, request, userDetails.getId());
    log.info("User {} submitted ranking for vote {}", userDetails.getUsername(), voteId);
    return ResponseEntity.ok(ApiResponse.success(response, "Ranking submitted successfully"));
}
```

- [ ] **Step 2: VoteController 新增 rate 端點**

```java
@PostMapping("/{voteId}/rate")
@PreAuthorize("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')")
@Auditable(action = "RATE", resourceType = "VOTE", resourceIdIndex = 0)
@Operation(summary = "Submit rating", description = "Submit a 1-5 star rating for a RATING type vote")
@ApiResponses(value = {
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Rating submitted successfully"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid rating or wrong vote type"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Vote not found")
})
public ResponseEntity<ApiResponse<VoteResponse>> submitRating(
        @Parameter(description = "Vote ID", required = true)
        @PathVariable String voteId,
        @Valid @RequestBody RateRequest request,
        Authentication authentication) {
    UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
    VoteResponse response = voteService.submitRating(voteId, request, userDetails.getId());
    log.info("User {} submitted rating for vote {}", userDetails.getUsername(), voteId);
    return ResponseEntity.ok(ApiResponse.success(response, "Rating submitted successfully"));
}
```

新增 import：

```java
import com.vomattapi.application.dto.request.RankRequest;
import com.vomattapi.application.dto.request.RateRequest;
```

- [ ] **Step 3: 確認編譯通過**

Run: `mvn compile -q`

- [ ] **Step 4: 執行全部測試**

Run: `mvn clean test -q`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vomattapi/application/controller/VoteController.java
git commit -m "feat(controller): 新增 rank/rate 端點

- POST /api/v1/votes/{voteId}/rank — RANKING 類型投票
- POST /api/v1/votes/{voteId}/rate — RATING 類型投票
- 需要認證，支援 Swagger 文件"
```

---

### Task 11: 完整驗證 + 最終提交

**Files:** 無新建/修改

- [ ] **Step 1: 完整編譯與測試**

Run: `mvn clean test`
Expected: BUILD SUCCESS, ALL tests pass

- [ ] **Step 2: 檢查所有新增/修改的檔案**

Run: `git diff --stat main`

確認變更符合預期：
- 1 個新 Migration 檔案
- 4 個新 Entity/Repository 檔案 (VoteType, UserRanking, UserRating, repos)
- 2 個新 Validator 檔案 (@ValidImageUrl)
- 2 個新 Request DTO 檔案 (RankRequest, RateRequest)
- 5 個修改的檔案 (Vote, VoteOption, CreateVoteRequest, VoteResponse, VoteResultResponse)
- 3 個修改的 Service 檔案 (VoteService, VoteServiceImpl, VoteMapper)
- 1 個修改的 Controller 檔案
- 3 個新測試檔案 + 1 個修改的測試檔案

- [ ] **Step 3: 確認向後相容性**

驗證項目：
- 現有投票 voteType 預設為 STANDARD（Migration DEFAULT）
- CreateVoteRequest.voteType 為 optional
- VoteResponse 新增欄位不影響前端
- 現有的投票/撤票流程不受影響
