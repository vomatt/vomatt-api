# P1 - 短期改進計劃

**日期**: 2026-03-27
**優先級**: 🟡 High — 影響可維護性與穩定性，建議在下一個 Sprint 處理

---

## 任務 1：拆分 VoteServiceImpl 責任

### 問題描述
`VoteServiceImpl`（318 行）混雜多個職責：
- 投票建立與驗證
- 標籤關聯管理
- 選項建立
- 結果轉換
- 事件發佈

違反單一職責原則，導致難以測試和維護。

### 修復方案

#### 1.1 提取 VoteFactory（負責建立 Vote entity）
```java
// application/service/vote/VoteFactory.java
@Component
public class VoteFactory {

    public Vote createVote(CreateVoteRequest request, User creator) {
        Vote vote = new Vote();
        vote.setTitle(request.getTitle());
        vote.setDescription(request.getDescription());
        vote.setVoteType(request.getVoteType());
        vote.setIsPublic(request.getIsPublic());
        vote.setIsAnonymous(request.getIsAnonymous());
        vote.setCreator(creator);
        vote.setStartTime(resolveStartTime(request));
        vote.setEndTime(request.getEndTime());
        return vote;
    }

    private LocalDateTime resolveStartTime(CreateVoteRequest request) {
        return request.getStartTime() != null
            ? request.getStartTime()
            : LocalDateTime.now();
    }
}
```

#### 1.2 提取 VoteTagAssociationService（負責標籤關聯）
```java
// application/service/vote/VoteTagAssociationService.java
@Service
@RequiredArgsConstructor
public class VoteTagAssociationService {

    private final TagRepository tagRepository;

    public void associateTags(Vote vote, List<UUID> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) return;

        Set<UUID> tagUUIDs = new HashSet<>(tagIds);
        List<Tag> tags = tagRepository.findAllByIdIn(tagUUIDs);

        if (tags.size() != tagUUIDs.size()) {
            throw new BusinessRuleViolationException("部分標籤 ID 不存在");
        }
        tags.forEach(vote::addTag);
    }
}
```

#### 1.3 簡化 VoteServiceImpl.createVote()
```java
// 修改後
@Transactional
public VoteResponse createVote(CreateVoteRequest request, String creatorId) {
    User creator = userRepository.findById(UUID.fromString(creatorId))
        .orElseThrow(() -> new EntityNotFoundException("User not found"));

    Vote vote = voteFactory.createVote(request, creator);
    vote = voteRepository.save(vote);

    voteTagAssociationService.associateTags(vote, request.getTagIds());
    createVoteOptions(vote, request.getOptions());
    vote = voteRepository.save(vote);

    eventPublisher.publishEvent(new VoteCreatedEvent(this, vote.getId(), creator.getId()));
    return convertToVoteResponse(vote);
}
```

### 影響檔案
- `application/service/vote/VoteServiceImpl.java`（修改）
- `application/service/vote/VoteFactory.java`（新增）
- `application/service/vote/VoteTagAssociationService.java`（新增）

---

## 任務 2：新增投票結果與標籤快取

### 問題描述
高頻讀取的投票結果和標籤列表缺少快取，每次請求都查詢資料庫。

### 修復方案

#### 2.1 新增快取常數
```java
// infrastructure/constants/CacheConstants.java（已有此檔）
public static final String VOTE_RESULT = "vote:result";
public static final String VOTE_DETAIL = "vote:detail";
public static final String TAG_LIST = "tag:list";
```

#### 2.2 VoteService — 快取投票結果
```java
// VoteServiceImpl.java
@Cacheable(value = CacheConstants.VOTE_RESULT, key = "#voteId")
public VoteResultResponse getVoteResults(String voteId) {
    // 現有邏輯
}

// 投票後清除快取
@CacheEvict(value = {CacheConstants.VOTE_RESULT, CacheConstants.VOTE_DETAIL}, key = "#voteId")
public VoteResponse vote(String voteId, VoteRequest request, String userId) {
    // 現有邏輯
}
```

#### 2.3 TagService — 快取標籤列表
```java
// TagServiceImpl.java
@Cacheable(value = CacheConstants.TAG_LIST)
public List<TagDto> getAllTags() {
    // 現有邏輯
}

// 建立/更新/刪除標籤時清除快取
@CacheEvict(value = CacheConstants.TAG_LIST, allEntries = true)
public TagDto createTag(CreateTagRequest request) {
    // 現有邏輯
}
```

#### 2.4 RedisConfig 新增快取 TTL 設定
```java
// infrastructure/config/RedisConfig.java
@Bean
public RedisCacheConfiguration voteResultCacheConfig() {
    return RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(Duration.ofMinutes(5))  // 投票結果 5 分鐘 TTL
        .serializeValuesWith(...);
}

@Bean
public RedisCacheConfiguration tagListCacheConfig() {
    return RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(Duration.ofHours(1));   // 標籤列表 1 小時 TTL
}
```

### 影響檔案
- `infrastructure/constants/CacheConstants.java`
- `infrastructure/config/RedisConfig.java`
- `application/service/vote/VoteServiceImpl.java`
- `application/service/tag/TagServiceImpl.java`

---

## 任務 3：改善異常處理

### 問題描述
`SignupService` 使用 `catch (Exception e)` 捕捉所有異常，導致：
- 業務異常與系統異常混為一談
- 所有錯誤回傳 `INTERNAL_ERROR`，客戶端無法判斷錯誤類型
- 潛在的重要業務錯誤被隱藏

### 修復方案

#### 3.1 細化 SignupService 異常處理
```java
// SignupService.java
@Transactional
public SignupResult processSignup(SignupRequest request) {
    SignupResult verificationResult = verifyPreSignupCode(request);
    if (!verificationResult.isSuccess()) return verificationResult;

    ValidationResult validation = validateSignupData(request);
    if (!validation.isValid()) {
        return SignupResult.failure(ErrorType.VALIDATION_ERROR, validation.getMessage());
    }

    try {
        User user = createUserFromRequest(request);
        assignRolesToUser(user);
        User savedUser = userRepository.save(user);
        clearPreSignupCache(request.getEmail(), request.getUsername());
        emailService.sendWelcomeEmail(savedUser.getEmail(), savedUser.getUsername());
        return SignupResult.success();
    } catch (ResourceConflictException e) {
        return SignupResult.failure(ErrorType.RESOURCE_CONFLICT, e.getMessage());
    } catch (EntityNotFoundException e) {
        return SignupResult.failure(ErrorType.ENTITY_NOT_FOUND, e.getMessage());
    } catch (Exception e) {
        log.error("Unexpected error during signup for username: {}", request.getUsername(), e);
        return SignupResult.failure(ErrorType.INTERNAL_ERROR, "註冊失敗，請稍後再試");
    }
}
```

#### 3.2 提取 UserServiceImpl 重複快取清除邏輯
```java
// UserServiceImpl.java
// 現在四個地方重複相同的兩行，提取為 helper
private void evictAndLogUserCache(String userId) {
    cacheUtil.evictUserCache(userId);
    log.debug("Evicted cache for user: {}", userId);
}
```

### 影響檔案
- `application/service/auth/SignupService.java`
- `application/service/user/UserServiceImpl.java`

---

## 任務 4：補充測試覆蓋率

### 問題描述
目前只有 13 個測試檔案，JaCoCo 目標 50%（偏低）。關鍵業務邏輯缺少測試：
- `UserServiceImpl` 完整測試
- `VoteCommentServiceImpl` 測試
- 整合測試

### 修復方案

#### 4.1 新增 UserServiceImpl 測試
測試方法命名依照：`shouldXxxWhenYyy`

關鍵測試案例：
- `shouldUpdateProfileWhenUserExists`
- `shouldThrowWhenUserNotFoundOnProfileUpdate`
- `shouldLockAccountWhenMaxAttemptsExceeded`
- `shouldEvictCacheAfterPasswordChange`

#### 4.2 新增 VoteCommentServiceImpl 測試
關鍵測試案例：
- `shouldCreateCommentWhenVoteIsActive`
- `shouldThrowWhenVoteIsInactiveOnComment`
- `shouldToggleLikeWhenCommentExists`

#### 4.3 新增基本整合測試
```java
@SpringBootTest
@Testcontainers
class VoteIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Test
    void shouldCreateAndRetrieveVoteEndToEnd() { ... }
}
```

#### 4.4 調高 JaCoCo 覆蓋率目標
```xml
<!-- pom.xml -->
<configuration>
    <rules>
        <rule>
            <limits>
                <limit>
                    <minimum>0.70</minimum>  <!-- 從 0.50 調高至 0.70 -->
                </limit>
            </limits>
        </rule>
    </rules>
</configuration>
```

### 影響檔案
- `src/test/java/.../service/user/UserServiceImplTest.java`（新增）
- `src/test/java/.../service/vote/VoteCommentServiceImplTest.java`（新增）
- `src/test/java/.../integration/VoteIntegrationTest.java`（新增）
- `pom.xml`

---

## 完成標準

- [ ] `VoteServiceImpl` 拆分，`createVote()` 行數 < 25 行
- [ ] 投票結果與標籤列表有快取，Redis 中可驗證
- [ ] `SignupService` 不再使用廣義 `catch (Exception)`
- [ ] `UserServiceImpl` 測試覆蓋率 ≥ 80%
- [ ] 整合測試通過
- [ ] `./mvnw clean test` 全部通過，覆蓋率 ≥ 70%
