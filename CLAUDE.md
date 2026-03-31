# Claude 開發指引

## 🚨 關鍵規則（絕對遵守）

### 代碼演進原則
- **永遠不要刪除或大幅重構現有代碼**，除非明確要求
- **優先順序：新增 > 修改 > 刪除**
- **先提出計劃，再執行**：收到任務時，先列出要改哪些檔案 + 理由，等確認後再動手
- **不要修改**：資料庫 schema、API 合約、外部依賴（除非明確要求）

### 提交準則
- **每次 commit 必須**：
  - 編譯成功
  - 通過所有現有測試
  - 包含新功能的測試
  - 符合專案格式規範
- **Commit message 格式**：`type(scope): description`
  - 範例：`feat(user): add email verification`
  - 類型：feat, fix, refactor, perf, test, docs, chore

### 測試要求
- **所有變更都要有單元測試**，覆蓋率至少 80%
- **永遠不要停用測試**，而是修復它們
- 測試命名：`shouldXxxWhenYyy` 或 `應該在Yyy情況下執行Xxx`

---

## 🎯 專案資訊

### 技術棧
| 類型 | 技術/版本                             |
|------|-----------------------------------|
| 語言 | Java 21                           |
| Framework | Spring Boot 3.4.8+                |
| Build | Maven                             |
| 資料庫 | Postgre                           |

### 專案結構（DDD 三層架構）
```
src/main/java/com/vomattapi/
├── application/
│   ├── controller/
│   │   ├── AuthController.java
│   │   ├── UserController.java
│   │   ├── VoteController.java
│   │   ├── VoteCommentController.java
│   │   ├── TagController.java
│   │   └── AdminTagController.java
│   ├── dto/
│   │   ├── auth/              # JwtResponse, SigninRequest, SignupRequest, PreSignupRequest/Response, TokenRefresh*
│   │   ├── common/            # ApiResponse, BaseResponse, ErrorResponse, ErrorType, MessageResponse
│   │   ├── tag/               # TagDto, CreateTagRequest, UpdateTagRequest
│   │   ├── user/              # UserDto, UserProfileResponse, UpdateProfileRequest
│   │   └── vote/              # VoteRequest/Response/ResultResponse, CommentDto, CreateVoteRequest, UserVoteStatusResponse
│   ├── exception/
│   │   ├── GlobalExceptionHandler.java
│   │   ├── BusinessRuleViolationException.java
│   │   ├── EntityNotFoundException.java
│   │   ├── InvalidVerificationCodeException.java
│   │   ├── ResourceConflictException.java
│   │   ├── TokenRefreshException.java
│   │   ├── UnauthorizedOperationException.java
│   │   └── VotingNotAllowedException.java
│   ├── mapper/
│   │   ├── VoteMapper.java
│   │   ├── CommentMapper.java
│   │   └── UserMapper.java
│   └── service/
│       ├── auth/              # AuthService, AuthSessionService, PreSignupService, SignupService,
│       │                      # RefreshTokenService, JwtBlacklistService, ValidationService, VerificationCodeService
│       ├── shared/            # EmailService/Impl/NoOp, SmsService/NoOp, LocaleService
│       ├── tag/               # TagService, TagServiceImpl
│       ├── user/              # UserService, UserServiceImpl
│       └── vote/              # VoteService/Impl, VoteCommentService/Impl, VoteEventListener
├── domain/
│   ├── common/
│   │   └── BaseEntity.java    # UUID v7 主鍵
│   ├── user/
│   │   ├── User.java
│   │   ├── Role.java
│   │   ├── ERole.java
│   │   ├── RefreshToken.java
│   │   ├── UserActivity.java
│   │   ├── UserPreference.java
│   │   └── repository/        # UserRepository, RoleRepository, RefreshTokenRepository, UserActivityRepository
│   └── vote/
│       ├── Vote.java
│       ├── VoteOption.java
│       ├── VoteType.java      # STANDARD, IMAGE, RANKING, RATING
│       ├── UserVote.java
│       ├── VoteComment.java
│       ├── CommentLike.java
│       ├── Tag.java
│       ├── event/             # VoteEvent, VoteCastEvent, VoteCreatedEvent, VoteDeactivatedEvent
│       └── repository/        # VoteRepository, VoteOptionRepository, UserVoteRepository,
│                              # VoteCommentRepository, CommentLikeRepository, TagRepository
└── infrastructure/
    ├── audit/                 # Auditable, AuditAspect (AOP), AuditLog, AuditLogRepository, AuditService
    ├── config/                # ApplicationConfigurationProperties, SecurityConfigurationProperties,
    │                          # VoteConfigurationProperties, AsyncConfig, RedisConfig,
    │                          # OpenAPIConfig, ThymeleafConfig, InternationalizationConfig
    ├── constants/             # CacheConstants
    ├── generator/             # UUIDv7Generator
    ├── redis/                 # RedisService/Impl, CacheUtil, CacheKeyUtil
    └── security/
        ├── WebSecurityConfig.java
        ├── jwt/               # JwtUtils, AuthTokenFilter, AuthEntryPointJwt
        └── services/          # UserDetailsImpl, UserDetailsServiceImpl
```

### 命名規範
- **類別**：駝峰式 (PascalCase)
  - Controller：`UserController`
  - Service：`UserService` / `UserServiceImpl`
  - Repository：`UserRepository`
  - Entity：`User`
- **方法/變數**：駝峰式 (camelCase)
- **常量**：大寫底線 (UPPER_SNAKE_CASE)
- **模組**：`ibpaas-common-*` 系列

---

## 🏗️ 開發哲學

### 核心信念
- **漸進式進步勝過大爆炸式改動** - 小步快跑，每次改動都能編譯和測試
- **從現有代碼學習** - 先研究再實作，遵循專案既有模式
- **務實勝過教條** - 適應專案實際情況
- **清晰意圖勝過聰明代碼** - 選擇無聊但明顯的解決方案

### 簡單意味著
- 每個函數/類別單一職責
- 避免過早抽象（YAGNI - You Aren't Gonna Need It）
- 沒有聰明的技巧 - 選擇無聊的方案
- 如果需要解釋，就太複雜了（KISS - Keep It Simple, Stupid）

---

## 📋 工作流程

### 1. 計劃與分階段

將複雜工作拆分成 3-5 個階段，記錄在 `IMPLEMENTATION_PLAN.md`：

```markdown
## 階段 N：[名稱]
**目標**：[具體交付成果]
**成功標準**：[可測試的結果]
**測試**：[具體測試案例]
**狀態**：[未開始|進行中|已完成]
**修改檔案**：
- `src/.../UserService.java` - 新增郵件驗證方法
- `src/.../UserController.java` - 新增驗證端點
```

- 隨著進度更新狀態
- 所有階段完成後刪除此檔案

### 2. 實作流程

1. **理解** - 研究代碼庫中的現有模式
   - 找 3 個類似的功能/組件
   - 識別共同模式和慣例
   - 盡可能使用相同的函式庫/工具

2. **測試** - 先寫測試（紅燈）
   ```java
   @Test
   @DisplayName("應該在郵件格式正確時成功創建用戶")
   void shouldCreateUserWhenEmailIsValid() {
       // Given
       UserCreateRequest request = new UserCreateRequest();
       request.setEmail("test@example.com");
       
       // When
       User user = userService.createUser(request);
       
       // Then
       assertNotNull(user.getId());
       assertEquals("test@example.com", user.getEmail());
   }
   ```

3. **實作** - 最小代碼讓測試通過（綠燈）
   ```java
   @Service
   public class UserServiceImpl implements UserService {
       public User createUser(UserCreateRequest request) {
           // 簡單直接的實作
           User user = new User();
           user.setEmail(request.getEmail());
           return userRepository.save(user);
       }
   }
   ```

4. **重構** - 在測試通過的情況下清理代碼
   ```java
   @Service
   public class UserServiceImpl implements UserService {
       private final UserRepository userRepository;
       private final UserMapper userMapper;
       
       public User createUser(UserCreateRequest request) {
           validateEmail(request.getEmail());
           User user = userMapper.toEntity(request);
           return userRepository.save(user);
       }
       
       private void validateEmail(String email) {
           if (!email.contains("@")) {
               throw new ValidationException("Invalid email format");
           }
       }
   }
   ```

5. **提交** - 包含清晰訊息並連結到計劃
   ```bash
   git add .
   git commit -m "feat(user): add email validation in user creation
   
   - Validate email format before saving user
   - Add unit tests for email validation
   - Refs: IMPLEMENTATION_PLAN.md Stage 2"
   ```

### 3. 遇到問題時（3 次嘗試後）

**關鍵**：每個問題最多嘗試 3 次，然後停止。

1. **記錄失敗內容**：
   - 嘗試了什麼
   - 具體錯誤訊息
   - 為什麼認為失敗了

2. **研究替代方案**：
   - 找 2-3 個類似的實作
   - 注意使用的不同方法

3. **質疑基本假設**：
   - 這是正確的抽象層級嗎？
   - 能拆分成更小的問題嗎？
   - 有完全更簡單的方法嗎？

4. **嘗試不同角度**：
   - 不同的函式庫/框架功能？
   - 不同的架構模式？
   - 移除抽象而不是增加？

---

## 💻 技術標準

### 架構原則
- **組合優於繼承** - 使用依賴注入
  ```java
  // ✓ 好：使用組合
  @Service
  public class OrderService {
      private final PaymentService paymentService;
      private final NotificationService notificationService;
      
      public OrderService(PaymentService paymentService, 
                         NotificationService notificationService) {
          this.paymentService = paymentService;
          this.notificationService = notificationService;
      }
  }
  
  // ✗ 避免：複雜的繼承
  public class OrderService extends BaseService implements Auditable {}
  ```

- **介面優於單例** - 啟用測試和靈活性
- **顯式優於隱式** - 清晰的資料流和依賴
- **盡可能測試驅動** - 永遠不要停用測試，要修復它們

### 代碼品質

#### 代碼風格
- **註解**：
  - Javadoc 只寫在 public 方法
  - private 方法用簡短的行內註解
  - 代碼應該自我解釋，需要註解表示太複雜
  
- **錯誤處理**：
  - 用 `@ControllerAdvice` 統一處理
  - 快速失敗並提供描述性訊息
  - 包含除錯上下文
  - 在適當層級處理錯誤
  - 永遠不要默默吞下異常
  - 永遠不要拋 `NullPointerException`

- **日誌**：
  - 使用 `@Slf4j`
  - INFO 級別記錄關鍵業務操作
  - ERROR 級別記錄異常（包含堆疊）
  - DEBUG 級別記錄詳細調試信息

#### JPA 最佳實踐
- 實體使用 `UUID` 或 `Long` 作為主鍵
- 大量資料考慮使用 UUID v7（效能優化）
- `@Transactional` 永遠在 Service 層
- 批次處理：1000 筆為一批，使用 `flush()` 和 `clear()`

#### 提交前檢查
- 執行格式化工具/Linter
- 自我審查變更
- 確保 commit 訊息解釋「為什麼」
- 執行 `./mvnw clean test` 確保測試通過

---

## 🎨 決策框架

當存在多個有效方法時，基於以下順序選擇：

1. **可測試性** - 我能輕鬆測試這個嗎？
2. **可讀性** - 6 個月後有人能理解嗎？
3. **一致性** - 這符合專案模式嗎？
4. **簡單性** - 這是最簡單可行的方案嗎？
5. **可逆性** - 之後改變有多困難？

### 效能 vs 可讀性
```java
// 情境：過濾和轉換資料

// 選項 1：可讀性優先（資料量 < 10,000 筆）
public List<UserDTO> getActiveUsers() {
    return userRepository.findAll().stream()
        .filter(User::isActive)
        .map(userMapper::toDTO)
        .collect(Collectors.toList());
}

// 選項 2：效能優先（資料量 > 100,000 筆）
public List<UserDTO> getActiveUsers() {
    // 在資料庫層過濾，減少記憶體使用
    return userRepository.findActiveUsersProjection();
}

// 決策準則：
// - 預期資料量 < 10K：可讀性優先
// - 預期資料量 10K-100K：平衡考慮，效能測試決定
// - 預期資料量 > 100K：效能優先，必須優化
```

---

## ✅ 完成定義

每個任務完成前檢查：

- [ ] 測試編寫完成且通過
- [ ] 代碼遵循專案慣例
- [ ] 無 Linter/格式化警告
- [ ] Commit 訊息清晰
- [ ] 實作符合計劃
- [ ] 無 TODO（除非有 issue 編號）
- [ ] 已更新計劃文件狀態

---

## 🚫 重要提醒

### 永遠不要
- 使用 `--no-verify` 繞過 commit hooks
- 停用測試而不是修復它們
- 提交無法編譯的代碼
- 做假設 - 用現有代碼驗證
- 修改外部依賴或 API 合約（除非明確要求）

### 永遠要
- 增量提交可運作的代碼
- 隨時更新計劃文件
- 從現有實作學習
- 3 次失敗嘗試後停止並重新評估
- 有問題先問，不要猜測業務邏輯

---

## 💬 溝通偏好

- **回答語言**：繁體中文
- **代碼註解**：繁體中文
- **每次改動後**：顯示 `git diff --cached`
- **有疑問時**：先提問再行動

---

## 📚 學習代碼庫

開始新任務時：
1. 找 3 個類似的功能/組件
2. 識別共同模式和慣例
3. 盡可能使用相同的函式庫/工具
4. 遵循現有的測試模式
5. 使用專案現有的建置系統和工具

記住：**從現有代碼學習 > 引入新模式**

當我需要函式庫/API 文件、程式碼產生、設定或設定步驟時，始終使用 Context7 MCP，無需我明確要求。
