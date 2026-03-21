# Phase 1: 標籤系統 實作計劃

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> **IMPORTANT:** 使用 worktree 進行隔離開發。實作時需要套件文件請搭配 Context7 MCP。

**Goal:** 為投票系統新增系統預設標籤功能，讓管理員管理標籤、用戶在建立投票時可選擇標籤、並支援依標籤篩選投票。

**Architecture:** 新增 Tag 實體與 Vote 的 @ManyToMany 關聯，遵循現有的分層架構（domain -> application -> infrastructure）。ADMIN 管理標籤，用戶從預設清單中選擇。usageCount 使用原子操作更新。

**Tech Stack:** Java 21, Spring Boot 3.5.10, Spring Data JPA, PostgreSQL, Flyway, JUnit 5 + Mockito

**Spec:** `docs/superpowers/specs/2026-03-21-vote-experience-enhancement-design.md` — Phase 1 章節

---

## File Structure

### 新建檔案

| 檔案 | 職責 |
|------|------|
| `src/main/java/com/vomattapi/domain/vote/Tag.java` | Tag 實體 |
| `src/main/java/com/vomattapi/domain/vote/repository/TagRepository.java` | Tag 資料存取 |
| `src/main/java/com/vomattapi/application/dto/request/CreateTagRequest.java` | 建立標籤請求 DTO |
| `src/main/java/com/vomattapi/application/dto/request/UpdateTagRequest.java` | 更新標籤請求 DTO |
| `src/main/java/com/vomattapi/application/dto/response/TagDto.java` | 標籤回應 DTO |
| `src/main/java/com/vomattapi/application/service/TagService.java` | 標籤服務介面 |
| `src/main/java/com/vomattapi/application/service/impl/TagServiceImpl.java` | 標籤服務實作 |
| `src/main/java/com/vomattapi/application/controller/TagController.java` | 公開標籤端點 |
| `src/main/java/com/vomattapi/application/controller/AdminTagController.java` | 管理標籤端點 |
| `src/main/java/com/vomattapi/application/exception/ResourceConflictException.java` | 409 Conflict 異常 |
| `src/main/resources/db/migration/V9__add_tag_system.sql` | 標籤系統資料庫 Migration |
| `src/test/java/com/vomattapi/service/TagServiceImplTest.java` | 標籤服務單元測試 |
| `src/test/java/com/vomattapi/controller/TagControllerTest.java` | 標籤公開端點測試 |
| `src/test/java/com/vomattapi/controller/AdminTagControllerTest.java` | 管理標籤端點測試 |

### 修改檔案

| 檔案 | 變動 |
|------|------|
| `src/main/java/com/vomattapi/domain/vote/Vote.java` | 新增 @ManyToMany tags 關聯 |
| `src/main/java/com/vomattapi/application/dto/request/CreateVoteRequest.java` | 新增 tagIds 欄位 |
| `src/main/java/com/vomattapi/application/dto/response/VoteResponse.java` | 新增 tags 欄位 |
| `src/main/java/com/vomattapi/application/mapper/VoteMapper.java` | 映射 tags 到 response |
| `src/main/java/com/vomattapi/application/service/impl/VoteServiceImpl.java` | createVote 加入標籤處理邏輯 |
| `src/main/java/com/vomattapi/application/service/VoteService.java` | 新增 getActiveVotesByTag 方法 |
| `src/main/java/com/vomattapi/domain/vote/repository/VoteRepository.java` | 新增按標籤查詢 + JOIN FETCH tags |
| `src/main/java/com/vomattapi/application/controller/VoteController.java` | getActiveVotes 支援 tag 參數 |
| `src/main/java/com/vomattapi/application/security/WebSecurityConfig.java` | 開放 /api/v1/tags/** GET、保護 /api/v1/admin/** |
| `src/main/java/com/vomattapi/application/exception/GlobalExceptionHandler.java` | 新增 ResourceConflictException -> 409、DataIntegrityViolationException 兜底 |
| `src/test/java/com/vomattapi/service/VoteServiceImplTest.java` | 新增標籤相關測試案例 |

---

## Task 1: Database Migration

**Files:**
- Create: `src/main/resources/db/migration/V9__add_tag_system.sql`

- [ ] **Step 1: 建立 Flyway migration 檔案**

```sql
-- V9__add_tag_system.sql
-- 標籤系統：tags 表 + vote_tags 關聯表

CREATE TABLE IF NOT EXISTS tags (
    id UUID PRIMARY KEY,
    name VARCHAR(30) NOT NULL,
    slug VARCHAR(50) NOT NULL,
    description VARCHAR(200),
    display_order INT NOT NULL DEFAULT 0,
    usage_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_tags_name UNIQUE (name),
    CONSTRAINT uk_tags_slug UNIQUE (slug)
);

CREATE INDEX idx_tags_usage_count ON tags (usage_count DESC);

CREATE TABLE IF NOT EXISTS vote_tags (
    vote_id UUID NOT NULL,
    tag_id UUID NOT NULL,
    PRIMARY KEY (vote_id, tag_id),
    CONSTRAINT fk_vote_tags_vote FOREIGN KEY (vote_id) REFERENCES votes (id) ON DELETE CASCADE,
    CONSTRAINT fk_vote_tags_tag FOREIGN KEY (tag_id) REFERENCES tags (id) ON DELETE RESTRICT
);

CREATE INDEX idx_vote_tags_tag_id ON vote_tags (tag_id);
```

- [ ] **Step 2: 驗證 migration 可執行**

Run: `./mvnw flyway:migrate` 或啟動應用程式讓 Spring Boot 自動執行。
Expected: Migration V9 成功執行，無錯誤。

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V9__add_tag_system.sql
git commit -m "feat(tag): add V9 migration for tag system tables"
```

---

## Task 2: Tag Entity + Repository + 409 Exception

**Files:**
- Create: `src/main/java/com/vomattapi/domain/vote/Tag.java`
- Create: `src/main/java/com/vomattapi/domain/vote/repository/TagRepository.java`
- Create: `src/main/java/com/vomattapi/application/exception/ResourceConflictException.java`
- Modify: `src/main/java/com/vomattapi/application/exception/GlobalExceptionHandler.java`

- [ ] **Step 1: 建立 Tag 實體**

遵循專案慣例：使用 `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @ToString`，繼承 BaseEntity（自動取得 UUID v7 ID + createdAt/updatedAt）。

```java
package com.vomattapi.domain.vote;

import com.vomattapi.domain.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "tags")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class Tag extends BaseEntity {

    @Column(name = "name", nullable = false, length = 30, unique = true)
    private String name;

    @Column(name = "slug", nullable = false, length = 50, unique = true)
    private String slug;

    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "usage_count", nullable = false)
    private int usageCount;

    public Tag(String name, String slug, String description, int displayOrder) {
        this.name = name.trim();
        this.slug = slug;
        this.description = description;
        this.displayOrder = displayOrder;
        this.usageCount = 0;
    }

    public void update(String name, String slug, String description, int displayOrder) {
        this.name = name.trim();
        this.slug = slug;
        this.description = description;
        this.displayOrder = displayOrder;
    }

    /**
     * Slug 自動生成：英文轉小寫 + 空格轉連字號，中日韓字符保留
     * 如果生成結果為空則拋出 IllegalArgumentException
     */
    public static String generateSlug(String name) {
        String slug = name.trim()
                .toLowerCase()
                .replaceAll("\\s+", "-")
                .replaceAll("[^a-z0-9\\-\\u4e00-\\u9fff\\u3040-\\u309f\\u30a0-\\u30ff]", "");
        if (slug.isBlank()) {
            throw new IllegalArgumentException("無法從名稱生成有效的 slug: " + name);
        }
        return slug;
    }
}
```

- [ ] **Step 2: 建立 TagRepository**

```java
package com.vomattapi.domain.vote.repository;

import com.vomattapi.domain.vote.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface TagRepository extends JpaRepository<Tag, UUID> {

    Optional<Tag> findBySlug(String slug);

    Optional<Tag> findByName(String name);

    boolean existsByName(String name);

    boolean existsBySlug(String slug);

    boolean existsByNameAndIdNot(String name, UUID id);

    boolean existsBySlugAndIdNot(String slug, UUID id);

    List<Tag> findAllByOrderByDisplayOrderAsc();

    Page<Tag> findAllByOrderByUsageCountDesc(Pageable pageable);

    List<Tag> findAllByIdIn(Set<UUID> ids);

    @Modifying
    @Query("UPDATE Tag t SET t.usageCount = t.usageCount + 1 WHERE t.id IN :tagIds")
    void incrementUsageCount(@Param("tagIds") Set<UUID> tagIds);

    @Modifying
    @Query("UPDATE Tag t SET t.usageCount = t.usageCount - 1 WHERE t.id IN :tagIds AND t.usageCount > 0")
    void decrementUsageCount(@Param("tagIds") Set<UUID> tagIds);

    @Query("SELECT CASE WHEN COUNT(v) > 0 THEN true ELSE false END FROM Vote v JOIN v.tags t WHERE t.id = :tagId")
    boolean isTagReferencedByVotes(@Param("tagId") UUID tagId);
}
```

- [ ] **Step 3: 建立 ResourceConflictException**

```java
package com.vomattapi.application.exception;

public class ResourceConflictException extends RuntimeException {
    public ResourceConflictException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: 在 GlobalExceptionHandler 新增 409 處理**

在 `GlobalExceptionHandler.java` 中新增：

```java
@ExceptionHandler(ResourceConflictException.class)
public ResponseEntity<ApiResponse<Void>> handleResourceConflict(ResourceConflictException ex, HttpServletRequest request) {
    log.warn("資源衝突: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiResponse.error(ErrorType.BUSINESS_RULE_VIOLATION, ex.getMessage()));
}

@ExceptionHandler(DataIntegrityViolationException.class)
public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest request) {
    log.error("資料完整性衝突: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiResponse.error(ErrorType.BUSINESS_RULE_VIOLATION, "資料操作衝突，請檢查是否有關聯資料"));
}
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vomattapi/domain/vote/Tag.java \
       src/main/java/com/vomattapi/domain/vote/repository/TagRepository.java \
       src/main/java/com/vomattapi/application/exception/ResourceConflictException.java \
       src/main/java/com/vomattapi/application/exception/GlobalExceptionHandler.java
git commit -m "feat(tag): add Tag entity, TagRepository, and 409 Conflict exception"
```

---

## Task 3: Tag DTOs

**Files:**
- Create: `src/main/java/com/vomattapi/application/dto/response/TagDto.java`
- Create: `src/main/java/com/vomattapi/application/dto/request/CreateTagRequest.java`
- Create: `src/main/java/com/vomattapi/application/dto/request/UpdateTagRequest.java`

- [ ] **Step 1: 建立 TagDto**

```java
package com.vomattapi.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TagDto {
    private String id;
    private String name;
    private String slug;
    private String description;
    private int displayOrder;
    private int usageCount;
}
```

- [ ] **Step 2: 建立 CreateTagRequest**

slug 欄位加入 `@Pattern` 驗證合法字符（小寫英文、數字、連字號、中日韓字符）。

```java
package com.vomattapi.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateTagRequest {

    @NotBlank(message = "標籤名稱不可為空")
    @Size(max = 30, message = "標籤名稱最長 30 字")
    private String name;

    @Size(max = 50, message = "Slug 最長 50 字")
    @Pattern(regexp = "^[a-z0-9\\-\\u4e00-\\u9fff\\u3040-\\u309f\\u30a0-\\u30ff]*$",
             message = "Slug 只允許小寫英文、數字、連字號和中日韓字符")
    private String slug;

    @Size(max = 200, message = "標籤說明最長 200 字")
    private String description;

    private int displayOrder = 0;
}
```

- [ ] **Step 3: 建立 UpdateTagRequest**

```java
package com.vomattapi.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateTagRequest {

    @NotBlank(message = "標籤名稱不可為空")
    @Size(max = 30, message = "標籤名稱最長 30 字")
    private String name;

    @Size(max = 50, message = "Slug 最長 50 字")
    @Pattern(regexp = "^[a-z0-9\\-\\u4e00-\\u9fff\\u3040-\\u309f\\u30a0-\\u30ff]*$",
             message = "Slug 只允許小寫英文、數字、連字號和中日韓字符")
    private String slug;

    @Size(max = 200, message = "標籤說明最長 200 字")
    private String description;

    private int displayOrder = 0;
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/vomattapi/application/dto/response/TagDto.java \
       src/main/java/com/vomattapi/application/dto/request/CreateTagRequest.java \
       src/main/java/com/vomattapi/application/dto/request/UpdateTagRequest.java
git commit -m "feat(tag): add Tag DTOs with slug pattern validation"
```

---

## Task 4: TagService 介面 + 實作（TDD）

**Files:**
- Create: `src/main/java/com/vomattapi/application/service/TagService.java`
- Create: `src/main/java/com/vomattapi/application/service/impl/TagServiceImpl.java`
- Create: `src/test/java/com/vomattapi/service/TagServiceImplTest.java`

- [ ] **Step 1: 建立 TagService 介面**

```java
package com.vomattapi.application.service;

import com.vomattapi.application.dto.request.CreateTagRequest;
import com.vomattapi.application.dto.request.UpdateTagRequest;
import com.vomattapi.application.dto.response.TagDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface TagService {

    TagDto createTag(CreateTagRequest request);

    TagDto updateTag(String tagId, UpdateTagRequest request);

    void deleteTag(String tagId);

    List<TagDto> getAllTags();

    Page<TagDto> getPopularTags(Pageable pageable);
}
```

- [ ] **Step 2: 寫測試（RED）**

建立 `src/test/java/com/vomattapi/service/TagServiceImplTest.java`。

**重要**：由於 mock 環境中 `tagRepository.save()` 回傳的物件沒有被 JPA 設定 ID，測試的 mock setup 需要手動設定 ID：

```java
package com.vomattapi.service;

import com.vomattapi.application.dto.request.CreateTagRequest;
import com.vomattapi.application.dto.request.UpdateTagRequest;
import com.vomattapi.application.dto.response.TagDto;
import com.vomattapi.application.exception.BusinessRuleViolationException;
import com.vomattapi.application.exception.EntityNotFoundException;
import com.vomattapi.application.exception.ResourceConflictException;
import com.vomattapi.application.service.impl.TagServiceImpl;
import com.vomattapi.domain.vote.Tag;
import com.vomattapi.domain.vote.repository.TagRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TagServiceImplTest {

    @Mock
    private TagRepository tagRepository;

    @InjectMocks
    private TagServiceImpl tagService;

    // Helper: 建立帶 ID 的 Tag（模擬持久化後的實體）
    private Tag createTagWithId(String name, String slug, String description, int displayOrder) {
        Tag tag = new Tag(name, slug, description, displayOrder);
        tag.setId(UUID.randomUUID());
        return tag;
    }

    @Nested
    @DisplayName("createTag")
    class CreateTag {

        @Test
        @DisplayName("應該成功建立標籤並自動生成 slug")
        void shouldCreateTagWithAutoGeneratedSlug() {
            CreateTagRequest request = new CreateTagRequest();
            request.setName("Tech News");
            request.setDescription("科技新聞");
            request.setDisplayOrder(1);

            when(tagRepository.existsByName("Tech News")).thenReturn(false);
            when(tagRepository.existsBySlug("tech-news")).thenReturn(false);
            when(tagRepository.save(any(Tag.class))).thenAnswer(inv -> {
                Tag tag = inv.getArgument(0);
                tag.setId(UUID.randomUUID());
                return tag;
            });

            TagDto result = tagService.createTag(request);

            assertThat(result.getName()).isEqualTo("Tech News");
            assertThat(result.getSlug()).isEqualTo("tech-news");
            assertThat(result.getDescription()).isEqualTo("科技新聞");
            assertThat(result.getId()).isNotNull();
            verify(tagRepository).save(any(Tag.class));
        }

        @Test
        @DisplayName("應該使用手動指定的 slug")
        void shouldUseProvidedSlug() {
            CreateTagRequest request = new CreateTagRequest();
            request.setName("美食");
            request.setSlug("food");
            request.setDisplayOrder(2);

            when(tagRepository.existsByName("美食")).thenReturn(false);
            when(tagRepository.existsBySlug("food")).thenReturn(false);
            when(tagRepository.save(any(Tag.class))).thenAnswer(inv -> {
                Tag tag = inv.getArgument(0);
                tag.setId(UUID.randomUUID());
                return tag;
            });

            TagDto result = tagService.createTag(request);

            assertThat(result.getSlug()).isEqualTo("food");
        }

        @Test
        @DisplayName("應該在標籤名稱重複時拋出異常")
        void shouldThrowWhenNameDuplicate() {
            CreateTagRequest request = new CreateTagRequest();
            request.setName("美食");

            when(tagRepository.existsByName("美食")).thenReturn(true);

            assertThatThrownBy(() -> tagService.createTag(request))
                    .isInstanceOf(BusinessRuleViolationException.class);
        }

        @Test
        @DisplayName("應該在 slug 重複時拋出異常")
        void shouldThrowWhenSlugDuplicate() {
            CreateTagRequest request = new CreateTagRequest();
            request.setName("Tech");
            request.setSlug("tech-news");

            when(tagRepository.existsByName("Tech")).thenReturn(false);
            when(tagRepository.existsBySlug("tech-news")).thenReturn(true);

            assertThatThrownBy(() -> tagService.createTag(request))
                    .isInstanceOf(BusinessRuleViolationException.class);
        }
    }

    @Nested
    @DisplayName("updateTag")
    class UpdateTag {

        @Test
        @DisplayName("應該成功更新標籤")
        void shouldUpdateTag() {
            UUID tagId = UUID.randomUUID();
            Tag existingTag = createTagWithId("舊名稱", "old-slug", "舊說明", 0);
            existingTag.setId(tagId);

            UpdateTagRequest request = new UpdateTagRequest();
            request.setName("新名稱");
            request.setSlug("new-slug");
            request.setDescription("新說明");
            request.setDisplayOrder(1);

            when(tagRepository.findById(tagId)).thenReturn(Optional.of(existingTag));
            when(tagRepository.existsByNameAndIdNot("新名稱", tagId)).thenReturn(false);
            when(tagRepository.existsBySlugAndIdNot("new-slug", tagId)).thenReturn(false);
            when(tagRepository.save(any(Tag.class))).thenAnswer(inv -> inv.getArgument(0));

            TagDto result = tagService.updateTag(tagId.toString(), request);

            assertThat(result.getName()).isEqualTo("新名稱");
            assertThat(result.getSlug()).isEqualTo("new-slug");
        }

        @Test
        @DisplayName("應該在標籤不存在時拋出異常")
        void shouldThrowWhenTagNotFound() {
            String tagId = UUID.randomUUID().toString();
            UpdateTagRequest request = new UpdateTagRequest();
            request.setName("新名稱");
            request.setSlug("new-slug");

            when(tagRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> tagService.updateTag(tagId, request))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("應該在更新後名稱與其他標籤重複時拋出異常")
        void shouldThrowWhenUpdatedNameConflicts() {
            UUID tagId = UUID.randomUUID();
            Tag existingTag = createTagWithId("原名稱", "original", null, 0);
            existingTag.setId(tagId);

            UpdateTagRequest request = new UpdateTagRequest();
            request.setName("已存在的名稱");
            request.setSlug("new-slug");

            when(tagRepository.findById(tagId)).thenReturn(Optional.of(existingTag));
            when(tagRepository.existsByNameAndIdNot("已存在的名稱", tagId)).thenReturn(true);

            assertThatThrownBy(() -> tagService.updateTag(tagId.toString(), request))
                    .isInstanceOf(BusinessRuleViolationException.class);
        }
    }

    @Nested
    @DisplayName("deleteTag")
    class DeleteTag {

        @Test
        @DisplayName("應該成功刪除沒有被引用的標籤")
        void shouldDeleteUnreferencedTag() {
            UUID tagId = UUID.randomUUID();
            Tag tag = createTagWithId("刪除我", "delete-me", null, 0);
            tag.setId(tagId);

            when(tagRepository.findById(tagId)).thenReturn(Optional.of(tag));
            when(tagRepository.isTagReferencedByVotes(tagId)).thenReturn(false);

            tagService.deleteTag(tagId.toString());

            verify(tagRepository).delete(tag);
        }

        @Test
        @DisplayName("應該在標籤被投票引用時拋出 ResourceConflictException")
        void shouldThrowConflictWhenTagIsReferenced() {
            UUID tagId = UUID.randomUUID();
            Tag tag = createTagWithId("被引用", "referenced", null, 0);
            tag.setId(tagId);

            when(tagRepository.findById(tagId)).thenReturn(Optional.of(tag));
            when(tagRepository.isTagReferencedByVotes(tagId)).thenReturn(true);

            assertThatThrownBy(() -> tagService.deleteTag(tagId.toString()))
                    .isInstanceOf(ResourceConflictException.class);
        }
    }

    @Nested
    @DisplayName("getAllTags")
    class GetAllTags {

        @Test
        @DisplayName("應該回傳依 displayOrder 排序的所有標籤")
        void shouldReturnAllTagsOrderedByDisplayOrder() {
            Tag tag1 = createTagWithId("美食", "food", null, 1);
            Tag tag2 = createTagWithId("科技", "tech", null, 2);
            when(tagRepository.findAllByOrderByDisplayOrderAsc()).thenReturn(List.of(tag1, tag2));

            List<TagDto> result = tagService.getAllTags();

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getName()).isEqualTo("美食");
            assertThat(result.get(1).getName()).isEqualTo("科技");
        }
    }

    @Nested
    @DisplayName("getPopularTags")
    class GetPopularTags {

        @Test
        @DisplayName("應該回傳依 usageCount 排序的分頁標籤")
        void shouldReturnPagedTagsOrderedByUsageCount() {
            Pageable pageable = PageRequest.of(0, 10);
            Tag tag = createTagWithId("熱門", "popular", null, 0);
            Page<Tag> page = new PageImpl<>(List.of(tag), pageable, 1);
            when(tagRepository.findAllByOrderByUsageCountDesc(pageable)).thenReturn(page);

            Page<TagDto> result = tagService.getPopularTags(pageable);

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getName()).isEqualTo("熱門");
        }
    }
}
```

- [ ] **Step 3: 執行測試確認失敗**

Run: `./mvnw test -pl . -Dtest="TagServiceImplTest" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 編譯失敗（TagServiceImpl 尚未建立）

- [ ] **Step 4: 實作 TagServiceImpl（GREEN）**

注意：`deleteTag` 使用 `ResourceConflictException`（映射到 409），而非 `BusinessRuleViolationException`（映射到 400）。`updateTag` 包含排除自身的名稱/slug 重複性檢查。

```java
package com.vomattapi.application.service.impl;

import com.vomattapi.application.dto.request.CreateTagRequest;
import com.vomattapi.application.dto.request.UpdateTagRequest;
import com.vomattapi.application.dto.response.TagDto;
import com.vomattapi.application.exception.BusinessRuleViolationException;
import com.vomattapi.application.exception.EntityNotFoundException;
import com.vomattapi.application.exception.ResourceConflictException;
import com.vomattapi.application.service.TagService;
import com.vomattapi.domain.vote.Tag;
import com.vomattapi.domain.vote.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TagServiceImpl implements TagService {

    private final TagRepository tagRepository;

    @Override
    @Transactional
    public TagDto createTag(CreateTagRequest request) {
        String name = request.getName().trim();

        if (tagRepository.existsByName(name)) {
            throw new BusinessRuleViolationException("標籤名稱已存在: " + name);
        }

        String slug = (request.getSlug() != null && !request.getSlug().isBlank())
                ? request.getSlug().trim()
                : Tag.generateSlug(name);

        if (tagRepository.existsBySlug(slug)) {
            throw new BusinessRuleViolationException("Slug 已存在: " + slug);
        }

        Tag tag = new Tag(name, slug, request.getDescription(), request.getDisplayOrder());
        Tag saved = tagRepository.save(tag);

        log.info("標籤已建立: name={}, slug={}", saved.getName(), saved.getSlug());
        return toDto(saved);
    }

    @Override
    @Transactional
    public TagDto updateTag(String tagId, UpdateTagRequest request) {
        UUID id = UUID.fromString(tagId);
        Tag tag = tagRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("標籤不存在: " + tagId));

        String name = request.getName().trim();
        String slug = (request.getSlug() != null && !request.getSlug().isBlank())
                ? request.getSlug().trim()
                : Tag.generateSlug(name);

        // 排除自身的重複性檢查
        if (tagRepository.existsByNameAndIdNot(name, id)) {
            throw new BusinessRuleViolationException("標籤名稱已存在: " + name);
        }
        if (tagRepository.existsBySlugAndIdNot(slug, id)) {
            throw new BusinessRuleViolationException("Slug 已存在: " + slug);
        }

        tag.update(name, slug, request.getDescription(), request.getDisplayOrder());
        Tag saved = tagRepository.save(tag);

        log.info("標籤已更新: id={}, name={}", tagId, saved.getName());
        return toDto(saved);
    }

    @Override
    @Transactional
    public void deleteTag(String tagId) {
        UUID id = UUID.fromString(tagId);
        Tag tag = tagRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("標籤不存在: " + tagId));

        if (tagRepository.isTagReferencedByVotes(id)) {
            throw new ResourceConflictException("標籤仍有投票引用，無法刪除: " + tag.getName());
        }

        tagRepository.delete(tag);
        log.info("標籤已刪除: id={}, name={}", tagId, tag.getName());
    }

    @Override
    @Transactional(readOnly = true)
    public List<TagDto> getAllTags() {
        return tagRepository.findAllByOrderByDisplayOrderAsc()
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TagDto> getPopularTags(Pageable pageable) {
        return tagRepository.findAllByOrderByUsageCountDesc(pageable)
                .map(this::toDto);
    }

    private TagDto toDto(Tag tag) {
        return new TagDto(
                tag.getId() != null ? tag.getId().toString() : null,
                tag.getName(),
                tag.getSlug(),
                tag.getDescription(),
                tag.getDisplayOrder(),
                tag.getUsageCount()
        );
    }
}
```

- [ ] **Step 5: 執行測試確認通過**

Run: `./mvnw test -pl . -Dtest="TagServiceImplTest"`
Expected: 所有測試通過

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vomattapi/application/service/TagService.java \
       src/main/java/com/vomattapi/application/service/impl/TagServiceImpl.java \
       src/test/java/com/vomattapi/service/TagServiceImplTest.java
git commit -m "feat(tag): add TagService with TDD tests"
```

---

## Task 5: Tag Controllers + Tests

**Files:**
- Create: `src/main/java/com/vomattapi/application/controller/TagController.java`
- Create: `src/main/java/com/vomattapi/application/controller/AdminTagController.java`
- Create: `src/test/java/com/vomattapi/controller/TagControllerTest.java`
- Create: `src/test/java/com/vomattapi/controller/AdminTagControllerTest.java`

- [ ] **Step 1: 寫 TagController 測試（RED）**

參照 `VoteControllerTest` 的 `TestSecurityConfig` 配置。需要 `@Import(TestSecurityConfig.class)` 來正確處理安全性。

```java
package com.vomattapi.controller;

import com.vomattapi.application.controller.TagController;
import com.vomattapi.application.dto.response.TagDto;
import com.vomattapi.application.service.TagService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TagController.class)
@Import(TagControllerTest.TestSecurityConfig.class)
@ActiveProfiles("test")
class TagControllerTest {

    @org.springframework.boot.test.context.TestConfiguration
    static class TestSecurityConfig {
        @Bean
        public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TagService tagService;

    @Test
    @DisplayName("GET /api/v1/tags 應該回傳所有標籤")
    void shouldReturnAllTags() throws Exception {
        TagDto tag = new TagDto("id1", "美食", "food", "美食相關", 1, 10);
        when(tagService.getAllTags()).thenReturn(List.of(tag));

        mockMvc.perform(get("/api/v1/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].name").value("美食"));
    }

    @Test
    @DisplayName("GET /api/v1/tags/popular 應該回傳熱門標籤")
    void shouldReturnPopularTags() throws Exception {
        TagDto tag = new TagDto("id1", "科技", "tech", null, 0, 50);
        when(tagService.getPopularTags(any())).thenReturn(
                new PageImpl<>(List.of(tag), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/api/v1/tags/popular"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].name").value("科技"));
    }
}
```

- [ ] **Step 2: 寫 AdminTagController 測試（RED）**

包含認證/授權測試：帶 ADMIN 角色的請求應成功，不帶角色的應被拒。

```java
package com.vomattapi.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vomattapi.application.controller.AdminTagController;
import com.vomattapi.application.dto.request.CreateTagRequest;
import com.vomattapi.application.dto.request.UpdateTagRequest;
import com.vomattapi.application.dto.response.TagDto;
import com.vomattapi.application.security.services.UserDetailsImpl;
import com.vomattapi.application.service.TagService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminTagController.class)
@Import(AdminTagControllerTest.TestSecurityConfig.class)
@ActiveProfiles("test")
class AdminTagControllerTest {

    @org.springframework.boot.test.context.TestConfiguration
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
            return http.build();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TagService tagService;

    private UserDetailsImpl adminUser() {
        return new UserDetailsImpl(
                UUID.randomUUID(), "admin", "admin@test.com",
                "credential",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private UserDetailsImpl normalUser() {
        return new UserDetailsImpl(
                UUID.randomUUID(), "user", "user@test.com",
                "credential",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Nested
    @DisplayName("POST /api/v1/admin/tags")
    class CreateTag {

        @Test
        @DisplayName("ADMIN 應該能建立標籤")
        void adminShouldCreateTag() throws Exception {
            CreateTagRequest request = new CreateTagRequest();
            request.setName("美食");
            request.setDescription("美食相關投票");

            TagDto response = new TagDto("id1", "美食", "美食", "美食相關投票", 0, 0);
            when(tagService.createTag(any())).thenReturn(response);

            mockMvc.perform(post("/api/v1/admin/tags")
                            .with(user(adminUser()))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("美食"));
        }

        @Test
        @DisplayName("一般用戶不應該能建立標籤")
        void normalUserShouldNotCreateTag() throws Exception {
            CreateTagRequest request = new CreateTagRequest();
            request.setName("美食");

            mockMvc.perform(post("/api/v1/admin/tags")
                            .with(user(normalUser()))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/admin/tags/{id}")
    class DeleteTag {

        @Test
        @DisplayName("ADMIN 應該能刪除標籤")
        void adminShouldDeleteTag() throws Exception {
            String tagId = UUID.randomUUID().toString();
            doNothing().when(tagService).deleteTag(tagId);

            mockMvc.perform(delete("/api/v1/admin/tags/" + tagId)
                            .with(user(adminUser()))
                            .with(csrf()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("未認證用戶不應該能刪除標籤")
        void unauthenticatedShouldNotDeleteTag() throws Exception {
            String tagId = UUID.randomUUID().toString();

            mockMvc.perform(delete("/api/v1/admin/tags/" + tagId)
                            .with(csrf()))
                    .andExpect(status().isUnauthorized());
        }
    }
}
```

注意：`UserDetailsImpl` 的建構函式需要參照現有程式碼中的實際簽名。上述為估計，實作時需對照 `src/main/java/com/vomattapi/application/security/services/UserDetailsImpl.java` 調整。

- [ ] **Step 3: 執行測試確認失敗**

Run: `./mvnw test -pl . -Dtest="TagControllerTest,AdminTagControllerTest" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 編譯失敗

- [ ] **Step 4: 實作 TagController**

```java
package com.vomattapi.application.controller;

import com.vomattapi.application.dto.response.ApiResponse;
import com.vomattapi.application.dto.response.TagDto;
import com.vomattapi.application.service.TagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tags")
@RequiredArgsConstructor
@Tag(name = "標籤", description = "標籤公開端點")
public class TagController {

    private final TagService tagService;

    @GetMapping
    @Operation(summary = "取得所有標籤", description = "依 displayOrder 排序")
    public ResponseEntity<ApiResponse<List<TagDto>>> getAllTags() {
        List<TagDto> tags = tagService.getAllTags();
        return ResponseEntity.ok(ApiResponse.success(tags));
    }

    @GetMapping("/popular")
    @Operation(summary = "取得熱門標籤", description = "依 usageCount 排序，分頁")
    public ResponseEntity<ApiResponse<Page<TagDto>>> getPopularTags(Pageable pageable) {
        Page<TagDto> tags = tagService.getPopularTags(pageable);
        return ResponseEntity.ok(ApiResponse.success(tags));
    }
}
```

- [ ] **Step 5: 實作 AdminTagController**

```java
package com.vomattapi.application.controller;

import com.vomattapi.application.dto.request.CreateTagRequest;
import com.vomattapi.application.dto.request.UpdateTagRequest;
import com.vomattapi.application.dto.response.ApiResponse;
import com.vomattapi.application.dto.response.TagDto;
import com.vomattapi.application.service.TagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/tags")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "管理-標籤", description = "管理員標籤管理端點")
public class AdminTagController {

    private final TagService tagService;

    @PostMapping
    @Operation(summary = "建立標籤")
    public ResponseEntity<ApiResponse<TagDto>> createTag(@Valid @RequestBody CreateTagRequest request) {
        TagDto tag = tagService.createTag(request);
        return ResponseEntity.ok(ApiResponse.success(tag, "標籤建立成功"));
    }

    @PutMapping("/{tagId}")
    @Operation(summary = "更新標籤")
    public ResponseEntity<ApiResponse<TagDto>> updateTag(
            @PathVariable String tagId,
            @Valid @RequestBody UpdateTagRequest request) {
        TagDto tag = tagService.updateTag(tagId, request);
        return ResponseEntity.ok(ApiResponse.success(tag, "標籤更新成功"));
    }

    @DeleteMapping("/{tagId}")
    @Operation(summary = "刪除標籤")
    public ResponseEntity<ApiResponse<Void>> deleteTag(@PathVariable String tagId) {
        tagService.deleteTag(tagId);
        return ResponseEntity.ok(ApiResponse.success(null, "標籤刪除成功"));
    }
}
```

- [ ] **Step 6: 執行測試確認通過**

Run: `./mvnw test -pl . -Dtest="TagControllerTest,AdminTagControllerTest"`
Expected: 所有測試通過

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/vomattapi/application/controller/TagController.java \
       src/main/java/com/vomattapi/application/controller/AdminTagController.java \
       src/test/java/com/vomattapi/controller/TagControllerTest.java \
       src/test/java/com/vomattapi/controller/AdminTagControllerTest.java
git commit -m "feat(tag): add TagController and AdminTagController with auth tests"
```

---

## Task 6: Vote <-> Tag 關聯整合

**Files:**
- Modify: `src/main/java/com/vomattapi/domain/vote/Vote.java`
- Modify: `src/main/java/com/vomattapi/application/dto/request/CreateVoteRequest.java`
- Modify: `src/main/java/com/vomattapi/application/dto/response/VoteResponse.java`
- Modify: `src/main/java/com/vomattapi/application/mapper/VoteMapper.java`
- Modify: `src/main/java/com/vomattapi/application/service/VoteService.java`
- Modify: `src/main/java/com/vomattapi/application/service/impl/VoteServiceImpl.java`
- Modify: `src/main/java/com/vomattapi/domain/vote/repository/VoteRepository.java`
- Modify: `src/main/java/com/vomattapi/application/controller/VoteController.java`
- Modify: `src/test/java/com/vomattapi/service/VoteServiceImplTest.java`

- [ ] **Step 1: Vote 實體新增 @ManyToMany tags**

在 `Vote.java` 中新增（注意要 import Tag 和 HashSet）：

```java
@ManyToMany(fetch = FetchType.LAZY)
@JoinTable(
    name = "vote_tags",
    joinColumns = @JoinColumn(name = "vote_id"),
    inverseJoinColumns = @JoinColumn(name = "tag_id")
)
private Set<Tag> tags = new HashSet<>();
```

同時更新 `@ToString(exclude = {...})` 加入 `"tags"`。

- [ ] **Step 2: CreateVoteRequest 新增 tagIds**

在 `CreateVoteRequest.java` 中新增：

```java
@Size(max = 5, message = "最多選擇 5 個標籤")
private List<UUID> tagIds;
```

使用 `List<UUID>` 而非 `List<String>`，Jackson 自動處理 UUID 反序列化。

- [ ] **Step 3: VoteResponse 新增 tags**

在 `VoteResponse.java` 中新增：

```java
private List<TagDto> tags;
```

- [ ] **Step 4: VoteMapper 加入 tag 映射**

在 `VoteMapper.java` 的 `toResponse` 方法中，查詢 Vote 時使用 JOIN FETCH 預載入 tags（避免 lazy loading N+1 問題），然後映射：

```java
List<TagDto> tagDtos = (vote.getTags() != null)
        ? vote.getTags().stream()
                .map(tag -> new TagDto(
                        tag.getId().toString(),
                        tag.getName(),
                        tag.getSlug(),
                        tag.getDescription(),
                        tag.getDisplayOrder(),
                        tag.getUsageCount()))
                .toList()
        : List.of();
response.setTags(tagDtos);
```

- [ ] **Step 5: VoteRepository 新增 JOIN FETCH tags 查詢**

在 `VoteRepository.java` 中新增：

```java
@Query("SELECT v FROM Vote v LEFT JOIN FETCH v.tags WHERE v.id = :id")
Optional<Vote> findByIdWithTags(@Param("id") UUID id);

@Query("SELECT v FROM Vote v JOIN v.tags t WHERE t.slug = :tagSlug AND v.isActive = true ORDER BY v.createdAt DESC")
Page<Vote> findByTagSlugAndIsActiveTrue(@Param("tagSlug") String tagSlug, Pageable pageable);
```

- [ ] **Step 6: VoteService 介面新增方法**

在 `VoteService.java` 中新增：

```java
Page<VoteResponse> getActiveVotesByTag(String tagSlug, Pageable pageable);
```

- [ ] **Step 7: VoteServiceImpl 加入標籤處理邏輯**

1. 注入 `TagRepository`（新增 constructor 參數）
2. 在 `createVote()` 中，save 之前加入：

```java
if (request.getTagIds() != null && !request.getTagIds().isEmpty()) {
    Set<UUID> tagUUIDs = new HashSet<>(request.getTagIds());
    List<Tag> tags = tagRepository.findAllByIdIn(tagUUIDs);
    if (tags.size() != tagUUIDs.size()) {
        throw new BusinessRuleViolationException("部分標籤 ID 不存在");
    }
    tags.forEach(vote::addTag);
    tagRepository.incrementUsageCount(tagUUIDs);
}
```

3. 新增 `getActiveVotesByTag` 方法：

```java
@Override
@Transactional(readOnly = true)
public Page<VoteResponse> getActiveVotesByTag(String tagSlug, Pageable pageable) {
    return voteRepository.findByTagSlugAndIsActiveTrue(tagSlug, pageable)
            .map(vote -> voteMapper.toResponse(vote, /* 現有的 options/counts 查詢邏輯 */));
}
```

4. 在 `getVote()` 方法中改用 `findByIdWithTags()` 以預載入 tags。

- [ ] **Step 8: VoteController 擴充 tag 篩選參數**

修改 `VoteController.getActiveVotes()`：

```java
@GetMapping
public ResponseEntity<ApiResponse<Page<VoteResponse>>> getActiveVotes(
        Pageable pageable,
        @RequestParam(required = false) String tag) {
    Page<VoteResponse> votes;
    if (tag != null && !tag.isBlank()) {
        votes = voteService.getActiveVotesByTag(tag, pageable);
    } else {
        votes = voteService.getActiveVotes(pageable);
    }
    return ResponseEntity.ok(ApiResponse.success(votes));
}
```

- [ ] **Step 9: 寫 VoteServiceImpl 標籤整合測試**

在 `VoteServiceImplTest.java` 中新增：

```java
@Nested
@DisplayName("createVote with tags")
class CreateVoteWithTags {

    @Test
    @DisplayName("應該在建立投票時關聯標籤並更新 usageCount")
    void shouldCreateVoteWithTags() {
        // Given: request 包含 2 個 tagIds
        // Mock tagRepository.findAllByIdIn 回傳 2 個 tags
        // When: createVote
        // Then: verify tagRepository.incrementUsageCount 被呼叫
    }

    @Test
    @DisplayName("應該在 tagIds 包含不存在的 ID 時拋出異常")
    void shouldThrowWhenTagIdNotFound() {
        // Given: request 包含 2 個 tagIds，但 findAllByIdIn 只回傳 1 個
        // When & Then: 拋出 BusinessRuleViolationException
    }

    @Test
    @DisplayName("應該在不帶 tagIds 時正常建立投票")
    void shouldCreateVoteWithoutTags() {
        // Given: request.tagIds = null
        // When: createVote
        // Then: 正常建立，tagRepository 不被呼叫
    }
}
```

- [ ] **Step 10: 執行所有測試**

Run: `./mvnw test`
Expected: 所有測試通過

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "feat(tag): integrate tags with Vote creation and tag-based filtering"
```

---

## Task 7: Security Configuration

**Files:**
- Modify: `src/main/java/com/vomattapi/application/security/WebSecurityConfig.java`

- [ ] **Step 1: 更新 WebSecurityConfig**

在 SecurityFilterChain 中新增規則（放在現有規則之後、`.anyRequest().authenticated()` 之前）：

```java
// 標籤公開端點
.requestMatchers(HttpMethod.GET, "/api/v1/tags", "/api/v1/tags/**").permitAll()
// 管理端點需要認證（具體授權由 @PreAuthorize 控制）
.requestMatchers("/api/v1/admin/**").authenticated()
```

- [ ] **Step 2: 執行全部測試**

Run: `./mvnw clean test`
Expected: 所有測試通過

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/vomattapi/application/security/WebSecurityConfig.java
git commit -m "feat(tag): update security config for tag and admin endpoints"
```

---

## Task 8: 最終驗證

- [ ] **Step 1: 執行完整測試套件**

Run: `./mvnw clean test`
Expected: BUILD SUCCESS，所有測試通過

- [ ] **Step 2: 確認編譯和覆蓋率**

Run: `./mvnw clean verify`
Expected: 覆蓋率 >= 50%（專案最低要求）

- [ ] **Step 3: 最終 Commit（如有未提交的修正）**

```bash
git add -A
git commit -m "test(tag): finalize tag system tests and coverage"
```
