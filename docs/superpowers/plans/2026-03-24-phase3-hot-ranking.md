# Phase 3: 熱門排行 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Vote 實體新增快取計數欄位與熱度分數，並提供 `/api/v1/votes/trending` 和 `/api/v1/votes/top` 兩個公開排行端點。

**Architecture:** 於 votes 表新增 `total_vote_count`、`total_comment_count`、`hot_score` 三個快取欄位。每次投票/撤票、留言/刪留言時，透過原子式 native SQL UPDATE 同步更新計數與重算熱度分數；另設每小時排程任務批次處理活躍投票的時間衰減，每批 500 筆。

**Tech Stack:** Java 21, Spring Boot 3.x, Spring Data JPA (`@Modifying` native query), Spring Scheduling (`@Scheduled`), JUnit 5, Mockito, PostgreSQL

**Spec Reference:** `docs/superpowers/specs/2026-03-21-vote-experience-enhancement-design.md` Phase 3 章節

---

## File Map

**Create:**
- `src/main/resources/db/migration/V11__add_vote_hot_score.sql`
- `src/main/java/com/vomattapi/application/service/HotScoreScheduler.java`
- `src/test/java/com/vomattapi/service/HotScoreSchedulerTest.java`

**Modify:**
- `src/main/java/com/vomattapi/domain/vote/Vote.java` — 新增 3 個快取欄位
- `src/main/java/com/vomattapi/domain/vote/repository/VoteRepository.java` — 新增 6 個 query method
- `src/main/java/com/vomattapi/application/service/VoteService.java` — 新增 2 個介面方法
- `src/main/java/com/vomattapi/application/service/impl/VoteServiceImpl.java` — vote()/removeVote() 更新計數、實作 getTrendingVotes/getTopVotes
- `src/main/java/com/vomattapi/application/service/impl/VoteCommentServiceImpl.java` — createComment()/deleteComment() 更新留言計數
- `src/main/java/com/vomattapi/application/controller/VoteController.java` — 新增 2 個端點
- `src/main/java/com/vomattapi/application/security/WebSecurityConfig.java` — 新增公開路由
- `src/test/java/com/vomattapi/service/VoteServiceImplTest.java` — 新增熱門排行測試

---

## Hot Score Formula

```
hotScore = (totalVoteCount * 2 + totalCommentCount * 3) / (timeSinceCreatedHours + 1)^1.2
```

在 PostgreSQL native query 中：
```sql
(total_vote_count * 2 + total_comment_count * 3) /
POWER(EXTRACT(EPOCH FROM (NOW() - created_at)) / 3600 + 1, 1.2)
```

---

## Task 1: Database Migration V11

**Files:**
- Create: `src/main/resources/db/migration/V11__add_vote_hot_score.sql`

- [ ] **Step 1: 建立 migration 檔案**

```sql
-- Phase 3: 熱門排行
-- votes 表新增快取計數欄位與熱度分數

ALTER TABLE votes ADD COLUMN total_vote_count INT NOT NULL DEFAULT 0;
ALTER TABLE votes ADD COLUMN total_comment_count INT NOT NULL DEFAULT 0;
ALTER TABLE votes ADD COLUMN hot_score DOUBLE PRECISION NOT NULL DEFAULT 0.0;

-- 初始化現有資料（根據現有 user_votes 計算 total_vote_count）
UPDATE votes v
SET total_vote_count = (
    SELECT COUNT(*) FROM user_votes uv WHERE uv.vote_id = v.id
);

-- 初始化現有資料（根據現有 vote_comments 計算 total_comment_count，排除軟刪除）
UPDATE votes v
SET total_comment_count = (
    SELECT COUNT(*) FROM vote_comments vc WHERE vc.vote_id = v.id AND vc.is_deleted = FALSE
);

-- 初始化 hot_score（已有資料）
UPDATE votes
SET hot_score = (total_vote_count * 2 + total_comment_count * 3) /
    POWER(EXTRACT(EPOCH FROM (NOW() - created_at)) / 3600 + 1, 1.2)
WHERE total_vote_count > 0 OR total_comment_count > 0;

-- 索引：加速 trending 排序
CREATE INDEX IF NOT EXISTS idx_votes_hot_score ON votes(hot_score DESC);

-- 複合索引：加速 top 查詢（period 篩選 + 排序）
CREATE INDEX IF NOT EXISTS idx_votes_active_created_vote_count
    ON votes(is_active, created_at, total_vote_count DESC);
```

- [ ] **Step 2: 確認 V10 migration 存在（前置條件）**

```bash
ls src/main/resources/db/migration/V10__add_vote_types.sql
```

Expected: 檔案存在

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V11__add_vote_hot_score.sql
git commit -m "feat(migration): V11 新增投票熱度分數欄位與索引"
```

---

## Task 2: Vote Entity — 新增快取欄位

**Files:**
- Modify: `src/main/java/com/vomattapi/domain/vote/Vote.java`

- [ ] **Step 1: 閱讀 Vote.java 現有內容**

確認現有欄位，找到適合插入位置（建議在 `voteType` 欄位後面）。

- [ ] **Step 2: 在 Vote.java 新增三個欄位**

在 `voteType` 欄位宣告之後，新增以下程式碼：

```java
@Column(name = "total_vote_count", nullable = false)
private int totalVoteCount = 0;

@Column(name = "total_comment_count", nullable = false)
private int totalCommentCount = 0;

@Column(name = "hot_score", nullable = false)
private double hotScore = 0.0;
```

- [ ] **Step 3: 驗證編譯**

```bash
./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/vomattapi/domain/vote/Vote.java
git commit -m "feat(entity): Vote 新增 totalVoteCount、totalCommentCount、hotScore 快取欄位"
```

---

## Task 3: VoteRepository — 新增 Query Methods

**Files:**
- Modify: `src/main/java/com/vomattapi/domain/vote/repository/VoteRepository.java`

- [ ] **Step 1: 閱讀 VoteRepository.java**

確認現有 query 結構。

- [ ] **Step 2: 新增 6 個 query method**

在 `VoteRepository` 介面中新增以下方法：

```java
// --- Hot Score 計數更新 ---

/**
 * 原子更新投票計數與熱度分數（投票/撤票時呼叫）
 * @param delta +1（投票）或 -1（撤票）
 */
@Modifying(clearAutomatically = true)
@Query(value = """
    UPDATE votes
    SET total_vote_count = GREATEST(0, total_vote_count + :delta),
        hot_score = (GREATEST(0, total_vote_count + :delta) * 2 + total_comment_count * 3) /
                    POWER(EXTRACT(EPOCH FROM (NOW() - created_at)) / 3600 + 1, 1.2)
    WHERE id = :voteId
    """, nativeQuery = true)
void updateVoteCountAndHotScore(@Param("voteId") UUID voteId, @Param("delta") int delta);

/**
 * 原子更新留言計數與熱度分數（留言/刪留言時呼叫）
 * @param delta +1（新留言）或 -1（刪留言）
 */
@Modifying(clearAutomatically = true)
@Query(value = """
    UPDATE votes
    SET total_comment_count = GREATEST(0, total_comment_count + :delta),
        hot_score = (total_vote_count * 2 + GREATEST(0, total_comment_count + :delta) * 3) /
                    POWER(EXTRACT(EPOCH FROM (NOW() - created_at)) / 3600 + 1, 1.2)
    WHERE id = :voteId
    """, nativeQuery = true)
void updateCommentCountAndHotScore(@Param("voteId") UUID voteId, @Param("delta") int delta);

// --- 排程器批次重算 ---

/**
 * 分批查詢需重算熱度的活躍投票 ID（最近 30 天，固定排序確保分頁穩定）
 * 注意：votes.id 是 VARCHAR(36)，native query 回傳 String
 */
@Query(value = """
    SELECT id FROM votes
    WHERE is_active = true AND created_at > :since
    ORDER BY id
    LIMIT :limit OFFSET :offset
    """, nativeQuery = true)
List<String> findActiveVoteIdsForHotScoreRecalculation(
    @Param("since") LocalDateTime since,
    @Param("limit") int limit,
    @Param("offset") int offset);

/**
 * 批次重算指定 ID 清單的熱度分數
 * 注意：votes.id 是 VARCHAR(36)，ids 用 List<String>
 */
@Modifying(clearAutomatically = true)
@Query(value = """
    UPDATE votes
    SET hot_score = (total_vote_count * 2 + total_comment_count * 3) /
                    POWER(EXTRACT(EPOCH FROM (NOW() - created_at)) / 3600 + 1, 1.2)
    WHERE id IN :ids
    """, nativeQuery = true)
int recalculateHotScoreForIds(@Param("ids") List<String> ids);

// --- 排行查詢 ---

/**
 * Trending：依 hotScore DESC 排序活躍投票
 */
@Query("SELECT v FROM Vote v WHERE v.isActive = true ORDER BY v.hotScore DESC")
Page<Vote> findTrendingVotes(Pageable pageable);

/**
 * Top：指定期間內依 totalVoteCount DESC 排序活躍投票
 */
@Query("SELECT v FROM Vote v WHERE v.isActive = true AND v.createdAt >= :since ORDER BY v.totalVoteCount DESC")
Page<Vote> findTopVotesByPeriod(@Param("since") LocalDateTime since, Pageable pageable);
```

同時需補充 import（若尚未存在）：
```java
import java.time.LocalDateTime;
import java.util.List;
```

- [ ] **Step 3: 驗證編譯**

```bash
./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/vomattapi/domain/vote/repository/VoteRepository.java
git commit -m "feat(repository): VoteRepository 新增熱度分數更新與排行查詢 methods"
```

---

## Task 4: VoteServiceImpl — vote/removeVote 時更新計數

**Files:**
- Modify: `src/main/java/com/vomattapi/application/service/VoteService.java`
- Modify: `src/main/java/com/vomattapi/application/service/impl/VoteServiceImpl.java`
- Modify: `src/test/java/com/vomattapi/service/VoteServiceImplTest.java`

### Step 1-4: 先寫測試（TDD）

- [ ] **Step 1: 在 VoteServiceImplTest 新增 hotScore 更新相關測試**

在 `VoteServiceImplTest.java` 現有測試結構中，找到或新增 vote 投票測試的 Nested class，加入以下測試：

```java
@Nested
@DisplayName("vote() - 熱度分數更新")
class VoteHotScoreUpdate {

    @Test
    @DisplayName("應該在投票後更新 totalVoteCount（+1）")
    void shouldUpdateVoteCountWhenVoteCast() {
        // Given
        UUID voteId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID optionId = UUID.randomUUID();

        Vote vote = new Vote();
        vote.setActive(true);
        // 設定 startTime 確保 isVotingActive() == true
        try {
            var field = com.vomattapi.domain.common.BaseEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(vote, voteId);
        } catch (Exception e) { throw new RuntimeException(e); }

        VoteOption option = new VoteOption();
        try {
            var field = com.vomattapi.domain.common.BaseEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(option, optionId);
            var voteField = VoteOption.class.getDeclaredField("vote");
            voteField.setAccessible(true);
            voteField.set(option, vote);
        } catch (Exception e) { throw new RuntimeException(e); }

        User user = new User();
        VoteRequest request = new VoteRequest();
        request.setOptionIds(List.of(optionId.toString()));

        when(voteRepository.findByIdAndIsActiveTrue(voteId)).thenReturn(Optional.of(vote));
        when(userRepository.findById(UUID.fromString(userId.toString()))).thenReturn(Optional.of(user));
        when(voteOptionRepository.findById(optionId)).thenReturn(Optional.of(option));
        when(userVoteRepository.existsByUserIdAndVoteIdAndOptionId(any(), any(), any())).thenReturn(false);
        when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));
        when(voteOptionRepository.findByVoteIdOrderByDisplayOrder(voteId)).thenReturn(List.of());
        when(userVoteRepository.countByVoteId(voteId)).thenReturn(0L);
        when(userVoteRepository.countByOptionGroupedForVote(voteId)).thenReturn(List.of());

        // When
        voteService.vote(voteId.toString(), request, userId.toString(), "127.0.0.1");

        // Then
        verify(voteRepository).updateVoteCountAndHotScore(voteId, 1);
    }

    @Test
    @DisplayName("應該在撤票後更新 totalVoteCount（-1）")
    void shouldUpdateVoteCountWhenVoteRemoved() {
        // Given
        UUID voteId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID optionId = UUID.randomUUID();

        Vote vote = new Vote();
        vote.setActive(true);

        when(voteRepository.findByIdAndIsActiveTrue(voteId)).thenReturn(Optional.of(vote));
        when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));
        when(voteOptionRepository.findByVoteIdOrderByDisplayOrder(voteId)).thenReturn(List.of());
        when(userVoteRepository.countByVoteId(voteId)).thenReturn(0L);
        when(userVoteRepository.countByOptionGroupedForVote(voteId)).thenReturn(List.of());

        // When
        voteService.removeVote(voteId.toString(), optionId.toString(), userId.toString());

        // Then
        verify(voteRepository).updateVoteCountAndHotScore(voteId, -1);
    }
}
```

- [ ] **Step 2: 執行測試確認失敗（RED）**

```bash
./mvnw test -pl . -Dtest=VoteServiceImplTest#VoteHotScoreUpdate -q 2>&1 | tail -20
```

Expected: FAIL（因為 `updateVoteCountAndHotScore` 尚未被呼叫）

### Step 3-5: 實作

- [ ] **Step 3: 在 VoteServiceImpl.vote() 中新增計數更新**

在 `vote()` 方法的 `eventPublisher.publishEvent(...)` 之前，加入：

```java
// 更新投票計數與熱度分數
voteRepository.updateVoteCountAndHotScore(voteUuid, 1);
```

- [ ] **Step 4: 在 VoteServiceImpl.removeVote() 中新增計數更新**

在 `userVoteRepository.deleteByUserIdAndVoteIdAndOptionId(...)` 之後，加入：

```java
// 更新投票計數與熱度分數
voteRepository.updateVoteCountAndHotScore(UUID.fromString(voteId), -1);
```

- [ ] **Step 5: 執行測試確認通過（GREEN）**

```bash
./mvnw test -pl . -Dtest=VoteServiceImplTest -q 2>&1 | tail -20
```

Expected: BUILD SUCCESS, 所有測試 PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vomattapi/application/service/impl/VoteServiceImpl.java \
        src/test/java/com/vomattapi/service/VoteServiceImplTest.java
git commit -m "feat(service): 投票/撤票時原子更新 totalVoteCount 與 hotScore"
```

---

## Task 5: VoteCommentServiceImpl — 留言時更新計數

**Files:**
- Modify: `src/main/java/com/vomattapi/application/service/impl/VoteCommentServiceImpl.java`
- Create: `src/test/java/com/vomattapi/service/VoteCommentServiceImplHotScoreTest.java`

### Step 1-2: 先寫測試

- [ ] **Step 1: 建立測試檔案**

```java
package com.vomattapi.service;

import com.vomattapi.application.dto.request.CreateCommentRequest;
import com.vomattapi.application.mapper.CommentMapper;
import com.vomattapi.application.service.impl.VoteCommentServiceImpl;
import com.vomattapi.domain.user.User;
import com.vomattapi.domain.user.repository.UserRepository;
import com.vomattapi.domain.vote.Vote;
import com.vomattapi.domain.vote.VoteComment;
import com.vomattapi.domain.vote.repository.CommentLikeRepository;
import com.vomattapi.domain.vote.repository.VoteCommentRepository;
import com.vomattapi.domain.vote.repository.VoteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("VoteCommentServiceImpl - 熱度分數更新")
class VoteCommentServiceImplHotScoreTest {

    @Mock VoteCommentRepository commentRepository;
    @Mock VoteRepository voteRepository;
    @Mock UserRepository userRepository;
    @Mock CommentLikeRepository commentLikeRepository;
    @Mock CommentMapper commentMapper;

    @InjectMocks
    VoteCommentServiceImpl commentService;

    @Test
    @DisplayName("應該在新增留言後更新 totalCommentCount（+1）")
    void shouldUpdateCommentCountWhenCommentCreated() {
        // Given
        UUID voteId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Vote vote = new Vote();
        User user = new User();
        VoteComment comment = mock(VoteComment.class);
        CreateCommentRequest request = new CreateCommentRequest();
        request.setText("test comment");

        when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(commentRepository.save(any())).thenReturn(comment);
        when(commentMapper.toDto(any(), anyLong(), anyBoolean())).thenReturn(null);

        // When
        commentService.createComment(voteId.toString(), userId.toString(), request);

        // Then
        verify(voteRepository).updateCommentCountAndHotScore(voteId, 1);
    }

    @Test
    @DisplayName("應該在刪除留言後更新 totalCommentCount（-1）")
    void shouldUpdateCommentCountWhenCommentDeleted() {
        // Given
        UUID commentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID voteId = UUID.randomUUID();
        Vote vote = mock(Vote.class);
        VoteComment comment = mock(VoteComment.class);

        when(comment.canBeDeletedBy(userId.toString())).thenReturn(true);
        when(comment.getVote()).thenReturn(vote);
        when(vote.getId()).thenReturn(voteId);
        when(commentRepository.findByIdAndNotDeleted(commentId)).thenReturn(Optional.of(comment));

        // When
        commentService.deleteComment(commentId, userId.toString());

        // Then
        verify(voteRepository).updateCommentCountAndHotScore(voteId, -1);
    }
}
```

- [ ] **Step 2: 執行測試確認失敗（RED）**

```bash
./mvnw test -pl . -Dtest=VoteCommentServiceImplHotScoreTest -q 2>&1 | tail -20
```

Expected: FAIL

### Step 3-5: 實作

- [ ] **Step 3: 修改 VoteCommentServiceImpl.createComment()**

在 `commentRepository.save(comment)` 之後、`log.info(...)` 之前，加入：

```java
// 更新留言計數與熱度分數
voteRepository.updateCommentCountAndHotScore(vote.getId(), 1);
```

- [ ] **Step 4: 修改 VoteCommentServiceImpl.deleteComment()**

在 `commentRepository.save(comment)` 之後、`log.info(...)` 之前，加入：

```java
// 更新留言計數與熱度分數
voteRepository.updateCommentCountAndHotScore(comment.getVote().getId(), -1);
```

> **注意：** `deleteComment()` 中的 `comment` 物件需要能存取其 `vote`。由於 `VoteComment` 已有 `@ManyToOne vote` 欄位，直接呼叫 `comment.getVote().getId()` 即可。若 lazy loading 有問題，改用 `commentRepository.findByIdWithVote(commentId)` 或確保在同一個 `@Transactional` 事務中存取。

- [ ] **Step 5: 執行測試確認通過（GREEN）**

```bash
./mvnw test -pl . -Dtest=VoteCommentServiceImplHotScoreTest -q 2>&1 | tail -20
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vomattapi/application/service/impl/VoteCommentServiceImpl.java \
        src/test/java/com/vomattapi/service/VoteCommentServiceImplHotScoreTest.java
git commit -m "feat(service): 新增/刪除留言時原子更新 totalCommentCount 與 hotScore"
```

---

## Task 6: HotScoreScheduler — 每小時批次重算

**Files:**
- Create: `src/main/java/com/vomattapi/application/service/HotScoreScheduler.java`
- Create: `src/test/java/com/vomattapi/service/HotScoreSchedulerTest.java`

### Step 1-2: 先寫測試

- [ ] **Step 1: 建立測試檔案**

```java
package com.vomattapi.service;

import com.vomattapi.application.service.HotScoreScheduler;
import com.vomattapi.domain.vote.repository.VoteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("HotScoreScheduler")
class HotScoreSchedulerTest {

    @Mock VoteRepository voteRepository;
    @InjectMocks HotScoreScheduler scheduler;

    @Test
    @DisplayName("應該在無活躍投票時不呼叫重算")
    void shouldNotRecalculateWhenNoActiveVotes() {
        // Given
        when(voteRepository.findActiveVoteIdsForHotScoreRecalculation(any(), anyInt(), anyInt()))
            .thenReturn(Collections.emptyList());

        // When
        scheduler.recalculateHotScores();

        // Then
        verify(voteRepository, never()).recalculateHotScoreForIds(any());
    }

    @Test
    @DisplayName("應該分批處理活躍投票（每批 500 筆）")
    void shouldProcessInBatchesOf500() {
        // Given：第一批 500 筆，第二批 3 筆（代表最後一批）
        // votes.id 是 VARCHAR(36)，使用 List<String>
        List<String> batch1 = Collections.nCopies(500, java.util.UUID.randomUUID().toString());
        List<String> batch2 = List.of(java.util.UUID.randomUUID().toString(),
            java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString());

        when(voteRepository.findActiveVoteIdsForHotScoreRecalculation(any(), eq(500), eq(0)))
            .thenReturn(batch1);
        when(voteRepository.findActiveVoteIdsForHotScoreRecalculation(any(), eq(500), eq(500)))
            .thenReturn(batch2);
        when(voteRepository.findActiveVoteIdsForHotScoreRecalculation(any(), eq(500), eq(1000)))
            .thenReturn(Collections.emptyList());

        // When
        scheduler.recalculateHotScores();

        // Then
        verify(voteRepository, times(1)).recalculateHotScoreForIds(batch1);
        verify(voteRepository, times(1)).recalculateHotScoreForIds(batch2);
        verify(voteRepository, times(3))
            .findActiveVoteIdsForHotScoreRecalculation(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("應該只處理 30 天內建立的活躍投票")
    void shouldOnlyProcessVotesWithin30Days() {
        // Given
        when(voteRepository.findActiveVoteIdsForHotScoreRecalculation(any(), anyInt(), anyInt()))
            .thenReturn(Collections.emptyList());

        // When
        scheduler.recalculateHotScores();

        // Then：確認有傳入 since 參數（約 30 天前）
        verify(voteRepository).findActiveVoteIdsForHotScoreRecalculation(
            argThat(since -> since.isBefore(java.time.LocalDateTime.now().minusDays(29))),
            eq(500), eq(0)
        );
    }
}
```

- [ ] **Step 2: 執行測試確認失敗（RED）**

```bash
./mvnw test -pl . -Dtest=HotScoreSchedulerTest -q 2>&1 | tail -20
```

Expected: FAIL（HotScoreScheduler 類別不存在）

### Step 3-5: 實作

- [ ] **Step 3: 建立 HotScoreScheduler.java**

```java
package com.vomattapi.application.service;

import com.vomattapi.domain.vote.repository.VoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class HotScoreScheduler {

    private static final int BATCH_SIZE = 500;
    private static final int RECALCULATION_DAYS = 30;

    private final VoteRepository voteRepository;

    /**
     * 每小時重算活躍投票的熱度分數，處理時間衰減效果。
     * 僅處理最近 30 天內建立的活躍投票，每批 500 筆。
     * votes.id 是 VARCHAR(36)，batchIds 使用 List<String>。
     */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void recalculateHotScores() {
        LocalDateTime since = LocalDateTime.now().minusDays(RECALCULATION_DAYS);
        int offset = 0;
        int totalUpdated = 0;

        log.info("開始批次重算熱度分數，範圍：{} 之後建立的活躍投票", since);

        List<String> batchIds;
        do {
            batchIds = voteRepository.findActiveVoteIdsForHotScoreRecalculation(since, BATCH_SIZE, offset);
            if (!batchIds.isEmpty()) {
                int updated = voteRepository.recalculateHotScoreForIds(batchIds);
                totalUpdated += updated;
                offset += batchIds.size();
                log.debug("已重算 {} 筆投票的熱度分數（累計 {}）", batchIds.size(), totalUpdated);
            }
        } while (!batchIds.isEmpty() && batchIds.size() == BATCH_SIZE);

        log.info("熱度分數批次重算完成，共更新 {} 筆", totalUpdated);
    }
}
```

- [ ] **Step 4: 確認 Spring Scheduling 已啟用**

檢查 `VomattApiApplication.java` 是否有 `@EnableScheduling`：

```bash
grep -n "EnableScheduling" src/main/java/com/vomattapi/VomattApiApplication.java
```

若無，在 `VomattApiApplication.java` 的 class 加上 `@EnableScheduling`：

```java
@SpringBootApplication
@EnableScheduling  // 加上這行
public class VomattApiApplication { ... }
```

並加入 import：
```java
import org.springframework.scheduling.annotation.EnableScheduling;
```

- [ ] **Step 5: 執行測試確認通過（GREEN）**

```bash
./mvnw test -pl . -Dtest=HotScoreSchedulerTest -q 2>&1 | tail -20
```

Expected: BUILD SUCCESS

- [ ] **Step 6: 驗證全部測試仍通過**

```bash
./mvnw test -q 2>&1 | tail -20
```

Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/vomattapi/application/service/HotScoreScheduler.java \
        src/main/java/com/vomattapi/VomattApiApplication.java \
        src/test/java/com/vomattapi/service/HotScoreSchedulerTest.java
git commit -m "feat(scheduler): 新增每小時批次重算熱度分數排程器"
```

---

## Task 7: VoteService 介面 + VoteServiceImpl — 排行查詢

**Files:**
- Modify: `src/main/java/com/vomattapi/application/service/VoteService.java`
- Modify: `src/main/java/com/vomattapi/application/service/impl/VoteServiceImpl.java`
- Modify: `src/test/java/com/vomattapi/service/VoteServiceImplTest.java`

- [ ] **Step 1: 在 VoteServiceImplTest 新增排行查詢測試**

在 VoteServiceImplTest 中新增：

```java
@Nested
@DisplayName("getTrendingVotes()")
class GetTrendingVotes {

    @Test
    @DisplayName("應該回傳依 hotScore 排序的活躍投票（分頁）")
    void shouldReturnTrendingVotesPagedByHotScore() {
        // Given
        Pageable pageable = PageRequest.of(0, 10);
        Vote vote = new Vote();
        Page<Vote> votePage = new PageImpl<>(List.of(vote));

        when(voteRepository.findTrendingVotes(pageable)).thenReturn(votePage);
        when(voteOptionRepository.findByVoteIdOrderByDisplayOrder(any())).thenReturn(List.of());
        when(userVoteRepository.countByVoteId(any())).thenReturn(0L);
        when(userVoteRepository.countByOptionGroupedForVote(any())).thenReturn(List.of());

        // When
        Page<VoteResponse> result = voteService.getTrendingVotes(pageable);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(voteRepository).findTrendingVotes(pageable);
    }
}

@Nested
@DisplayName("getTopVotes()")
class GetTopVotes {

    @Test
    @DisplayName("應該在 period=day 時查詢最近 1 天的投票")
    void shouldQueryLast1DayWhenPeriodIsDay() {
        // Given
        Pageable pageable = PageRequest.of(0, 10);
        when(voteRepository.findTopVotesByPeriod(any(), eq(pageable))).thenReturn(Page.empty());

        // When
        voteService.getTopVotes("day", pageable);

        // Then
        verify(voteRepository).findTopVotesByPeriod(
            argThat(since -> since.isAfter(LocalDateTime.now().minusDays(2))),
            eq(pageable)
        );
    }

    @Test
    @DisplayName("應該在 period=week 時查詢最近 7 天的投票")
    void shouldQueryLast7DaysWhenPeriodIsWeek() {
        // Given
        Pageable pageable = PageRequest.of(0, 10);
        when(voteRepository.findTopVotesByPeriod(any(), eq(pageable))).thenReturn(Page.empty());

        // When
        voteService.getTopVotes("week", pageable);

        // Then
        verify(voteRepository).findTopVotesByPeriod(
            argThat(since -> since.isAfter(LocalDateTime.now().minusDays(8))),
            eq(pageable)
        );
    }

    @Test
    @DisplayName("應該在 period=month 時查詢最近 30 天的投票")
    void shouldQueryLast30DaysWhenPeriodIsMonth() {
        // Given
        Pageable pageable = PageRequest.of(0, 10);
        when(voteRepository.findTopVotesByPeriod(any(), eq(pageable))).thenReturn(Page.empty());

        // When
        voteService.getTopVotes("month", pageable);

        // Then
        verify(voteRepository).findTopVotesByPeriod(
            argThat(since -> since.isAfter(LocalDateTime.now().minusDays(31))),
            eq(pageable)
        );
    }

    @Test
    @DisplayName("應該在 period 無效時拋出 IllegalArgumentException")
    void shouldThrowWhenPeriodIsInvalid() {
        // Given
        Pageable pageable = PageRequest.of(0, 10);

        // When & Then
        assertThatThrownBy(() -> voteService.getTopVotes("invalid", pageable))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid");
    }
}
```

需補充的 import：
```java
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
```

- [ ] **Step 2: 執行測試確認失敗（RED）**

```bash
./mvnw test -pl . -Dtest=VoteServiceImplTest#GetTrendingVotes+GetTopVotes -q 2>&1 | tail -20
```

Expected: FAIL（方法不存在）

- [ ] **Step 3: 在 VoteService 介面新增方法**

```java
Page<VoteResponse> getTrendingVotes(Pageable pageable);

Page<VoteResponse> getTopVotes(String period, Pageable pageable);
```

- [ ] **Step 4: 在 VoteServiceImpl 實作方法**

```java
@Override
@Transactional(readOnly = true)
public Page<VoteResponse> getTrendingVotes(Pageable pageable) {
    return voteRepository.findTrendingVotes(pageable).map(this::convertToVoteResponse);
}

@Override
@Transactional(readOnly = true)
public Page<VoteResponse> getTopVotes(String period, Pageable pageable) {
    LocalDateTime since = switch (period.toLowerCase()) {
        case "day" -> LocalDateTime.now().minusDays(1);
        case "week" -> LocalDateTime.now().minusDays(7);
        case "month" -> LocalDateTime.now().minusDays(30);
        default -> throw new IllegalArgumentException("Invalid period: " + period + ". Use day, week, or month.");
    };
    return voteRepository.findTopVotesByPeriod(since, pageable).map(this::convertToVoteResponse);
}
```

- [ ] **Step 5: 執行測試確認通過（GREEN）**

```bash
./mvnw test -pl . -Dtest=VoteServiceImplTest -q 2>&1 | tail -20
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vomattapi/application/service/VoteService.java \
        src/main/java/com/vomattapi/application/service/impl/VoteServiceImpl.java \
        src/test/java/com/vomattapi/service/VoteServiceImplTest.java
git commit -m "feat(service): 新增 getTrendingVotes 與 getTopVotes 排行查詢方法"
```

---

## Task 8: VoteController — 新增 API 端點 + Security 設定

**Files:**
- Modify: `src/main/java/com/vomattapi/application/controller/VoteController.java`
- Modify: `src/main/java/com/vomattapi/application/security/WebSecurityConfig.java`
- Modify: `src/test/java/com/vomattapi/controller/VoteControllerTest.java`

### Step 1-2: 先寫 Controller 測試

- [ ] **Step 1: 在 VoteControllerTest 新增排行端點測試**

閱讀現有 `VoteControllerTest.java` 結構，照相同模式新增：

```java
@Nested
@DisplayName("GET /api/v1/votes/trending")
class GetTrendingVotes {

    @Test
    @DisplayName("應該回傳 200 與熱門投票清單（不需認證）")
    void shouldReturn200WithTrendingVotes() throws Exception {
        // Given
        Page<VoteResponse> page = new PageImpl<>(List.of());
        when(voteService.getTrendingVotes(any())).thenReturn(page);

        // When & Then
        mockMvc.perform(get("/api/v1/votes/trending")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }
}

@Nested
@DisplayName("GET /api/v1/votes/top")
class GetTopVotes {

    @Test
    @DisplayName("應該回傳 200 與 period=week 期間最多票投票（不需認證）")
    void shouldReturn200WithTopVotesForWeek() throws Exception {
        // Given
        Page<VoteResponse> page = new PageImpl<>(List.of());
        when(voteService.getTopVotes(eq("week"), any())).thenReturn(page);

        // When & Then
        mockMvc.perform(get("/api/v1/votes/top")
                .param("period", "week")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("應該在 period 無效時回傳 400")
    void shouldReturn400WhenPeriodIsInvalid() throws Exception {
        // Given
        when(voteService.getTopVotes(eq("invalid"), any()))
            .thenThrow(new IllegalArgumentException("Invalid period: invalid"));

        // When & Then
        mockMvc.perform(get("/api/v1/votes/top")
                .param("period", "invalid")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: 執行測試確認失敗（RED）**

```bash
./mvnw test -pl . -Dtest=VoteControllerTest -q 2>&1 | tail -20
```

Expected: FAIL（端點不存在）

### Step 3-6: 實作

- [ ] **Step 3: 在 VoteController.java 新增兩個端點**

在現有 `getActiveVotes` 端點之後，新增：

```java
@GetMapping("/trending")
@Operation(summary = "取得熱門投票", description = "依熱度分數降序回傳活躍投票（分頁）")
@ApiResponses(value = {
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "成功回傳熱門投票")
})
public ResponseEntity<ApiResponse<Page<VoteResponse>>> getTrendingVotes(
        @PageableDefault(size = 20) Pageable pageable) {
    Page<VoteResponse> votes = voteService.getTrendingVotes(pageable);
    return ResponseEntity.ok(ApiResponse.success(votes));
}

@GetMapping("/top")
@Operation(summary = "取得期間熱門投票", description = "指定期間（day/week/month）內依總票數排序的活躍投票（分頁）")
@ApiResponses(value = {
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "成功回傳期間熱門投票"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "無效的 period 參數")
})
public ResponseEntity<ApiResponse<Page<VoteResponse>>> getTopVotes(
        @RequestParam(defaultValue = "week") String period,
        @PageableDefault(size = 20) Pageable pageable) {
    Page<VoteResponse> votes = voteService.getTopVotes(period, pageable);
    return ResponseEntity.ok(ApiResponse.success(votes));
}
```

- [ ] **Step 4: 在 WebSecurityConfig 新增公開路由**

在 `.requestMatchers(HttpMethod.GET, "/api/v1/votes").permitAll()` 之後新增：

```java
// 公開路由：熱門投票排行
.requestMatchers(HttpMethod.GET, "/api/v1/votes/trending", "/api/v1/votes/top").permitAll()
```

- [ ] **Step 5: 確認 GlobalExceptionHandler 能處理 IllegalArgumentException → 400**

```bash
grep -n "IllegalArgumentException" src/main/java/com/vomattapi/application/exception/GlobalExceptionHandler.java 2>/dev/null || \
grep -rn "IllegalArgumentException" src/main/java/com/vomattapi/
```

若不存在 handler，在 GlobalExceptionHandler (或對應的 `@ControllerAdvice` 類別) 中新增：

```java
@ExceptionHandler(IllegalArgumentException.class)
@ResponseStatus(HttpStatus.BAD_REQUEST)
public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
    log.warn("Invalid argument: {}", ex.getMessage());
    return ResponseEntity.badRequest()
        .body(ApiResponse.error(ex.getMessage()));
}
```

> **注意：** 先搜尋現有的 `@ControllerAdvice` 類別，避免新增重複的 handler。

- [ ] **Step 6: 執行測試確認通過（GREEN）**

```bash
./mvnw test -pl . -Dtest=VoteControllerTest -q 2>&1 | tail -20
```

Expected: BUILD SUCCESS

- [ ] **Step 7: 執行全部測試**

```bash
./mvnw test -q 2>&1 | tail -30
```

Expected: BUILD SUCCESS，所有測試通過

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/vomattapi/application/controller/VoteController.java \
        src/main/java/com/vomattapi/application/security/WebSecurityConfig.java \
        src/test/java/com/vomattapi/controller/VoteControllerTest.java
git commit -m "feat(api): 新增 /api/v1/votes/trending 與 /api/v1/votes/top 公開排行端點"
```

---

## Task 9: 最終驗證

- [ ] **Step 1: 執行完整測試套件**

```bash
./mvnw test 2>&1 | tail -40
```

Expected: BUILD SUCCESS，所有測試通過

- [ ] **Step 2: 確認編譯無警告**

```bash
./mvnw compile 2>&1 | grep -i "warning\|error" | head -20
```

Expected: 無 error，warning 可接受

- [ ] **Step 3: 確認所有 migration 檔案版本連續**

```bash
ls -1 src/main/resources/db/migration/V*.sql | sort
```

Expected: V1 ~ V11 連續，無跳號

- [ ] **Step 4: 最終 commit（如有未 commit 的變更）**

```bash
git status
git diff --stat
```

若還有未 commit 的修改，補齊 commit。

- [ ] **Step 5: 完成確認**

Phase 3 熱門排行功能完成：
- [x] V11 migration（3 個新欄位 + 2 個索引）
- [x] Vote entity 新增欄位
- [x] VoteRepository 新增 6 個 query method
- [x] 投票/撤票時原子更新 totalVoteCount + hotScore
- [x] 新增/刪除留言時原子更新 totalCommentCount + hotScore
- [x] HotScoreScheduler 每小時批次重算（500 筆/批）
- [x] `GET /api/v1/votes/trending` 公開端點
- [x] `GET /api/v1/votes/top?period={day,week,month}` 公開端點
- [x] WebSecurityConfig 公開路由更新

---

## 注意事項

### VoteComment 的 vote 關聯載入

`VoteComment.deleteComment()` 中需要存取 `comment.getVote().getId()`。由於 `VoteComment.vote` 是 `@ManyToOne`，在同一個 `@Transactional` 方法中應能正常 lazy load。若遇到 `LazyInitializationException`，改用 native query 直接查詢：

```java
// 替代方案：直接 update，不需載入 Vote
@Modifying
@Query(value = "UPDATE votes SET total_comment_count = GREATEST(0, total_comment_count + :delta), hot_score = ... WHERE id = (SELECT vote_id FROM vote_comments WHERE id = :commentId)", nativeQuery = true)
void updateCommentCountByCommentId(@Param("commentId") UUID commentId, @Param("delta") int delta);
```

### @Transactional 與 @Modifying

`@Modifying` 的 native query 必須在 `@Transactional` 的方法中呼叫。`VoteServiceImpl` 和 `VoteCommentServiceImpl` 都已有 class-level `@Transactional`，應正常運作。

### 排程器 @EnableScheduling

若 `VomattApiApplication.java` 已有其他排程功能，`@EnableScheduling` 可能已存在。先 grep 確認再修改。
