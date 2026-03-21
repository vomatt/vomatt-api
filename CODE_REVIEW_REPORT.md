# Vomatt API 全面架構與程式碼審查報告

> 審查日期：2026-03-21
> 審查範圍：完整專案架構、安全性、程式品質、效能、測試
> 審查方式：Superpowers Code Review

---

## 目錄

1. [專案概覽](#1-專案概覽)
2. [嚴重問題 (CRITICAL)](#2-嚴重問題-critical)
3. [重要問題 (HIGH)](#3-重要問題-high)
4. [中等問題 (MEDIUM)](#4-中等問題-medium)
5. [建議改善 (LOW)](#5-建議改善-low)
6. [架構分析](#6-架構分析)
7. [優點](#7-優點)
8. [建議優先處理順序](#8-建議優先處理順序)

---

## 1. 專案概覽

| 項目 | 說明 |
|------|------|
| 技術棧 | Java 21 + Spring Boot 3.5.10 + PostgreSQL + Redis |
| 核心功能 | 投票系統（建立投票、投票、留言、按讚） |
| 認證方式 | JWT + 驗證碼（OTP）無密碼登入 |
| 主要模組 | Auth、User、Vote、Comment |
| 原始碼檔案 | ~90 個 Java 檔案 |
| 測試檔案 | 3 個（嚴重不足） |

### 目前架構

```
com.vomattapi/
├── application/              # 應用層
│   ├── controller/           #   REST API（3 個 Controller）
│   ├── dto/                  #   請求/回應 DTO
│   ├── exception/            #   自定義例外 + GlobalExceptionHandler
│   ├── mapper/               #   手動 Mapper（已引入 MapStruct 但未充分使用）
│   ├── security/             #   JWT + Spring Security 設定
│   └── service/              #   業務邏輯（介面 + impl/）
├── domain/                   # 領域層
│   ├── common/               #   BaseEntity、UuidV7Generator（死碼）
│   ├── user/                 #   User、Role、RefreshToken 等實體 + Repository
│   └── vote/                 #   Vote、VoteOption、UserVote 等實體 + Repository + Event
└── infrastructure/           # 基礎設施層
    ├── audit/                #   AOP 審計日誌
    ├── config/               #   配置類（Security、Redis、OpenAPI 等）
    ├── constants/            #   快取常量
    ├── generator/            #   UUIDv7Generator（實際使用中）
    └── redis/                #   Redis 服務封裝 + CacheUtil
```

---

## 2. 嚴重問題 (CRITICAL)

### C1. AuthTokenFilter 未檢查 JWT 黑名單 — 強制過期機制形同虛設

**檔案**：`application/security/jwt/AuthTokenFilter.java:39`

`forceExpireToken` 端點會將 JWT 加入黑名單（`JwtBlacklistService`），但 `AuthTokenFilter` 驗證 token 時**完全沒有檢查黑名單**，導致被強制過期的 token 在自然過期前仍然有效。

```java
// 目前：只驗證簽章，沒檢查黑名單
if (jwt != null && jwtUtils.validateJwtToken(jwt)) {
    // 直接放行 — 即使 token 已被加入黑名單
}
```

**修復建議**：

```java
if (jwt != null && jwtUtils.validateJwtToken(jwt)
        && !jwtBlacklistService.isBlacklisted(jwt)) {
    // 放行
}
```

---

### C2. 測試覆蓋率嚴重不足 — 遠低於 80% 目標

**目前測試檔案**：僅 3 個

```
src/test/
├── VomattApiApplicationTests.java   // Spring context 載入
├── email/EmailTemplateTest.java     // Email 模板
└── LocaleIntegrationTest.java       // 國際化
```

核心業務邏輯（VoteService、AuthService、UserService、SignupService）、Controller 層、Security 層**完全沒有測試**。

**建議優先補充**：
1. `VoteServiceImpl` — 建立/投票/取消/停用投票邏輯
2. `AuthService` — 驗證碼生成與驗證流程
3. `SignupService` — 註冊流程（含預註冊驗證）
4. `AuthTokenFilter` — JWT 過濾（含黑名單檢查）
5. Controller 整合測試（MockMvc）

---

### C3. CORS 設定過於寬鬆 — 等同無 CORS 保護

**檔案**：`WebSecurityConfig.java:93` 及 `AuthController.java:50`

```java
// WebSecurityConfig — 全域設定
configuration.setAllowedOriginPatterns(List.of("*"));
configuration.setAllowCredentials(true);  // 允許攜帶 Cookie/Auth

// AuthController — 重複且衝突的設定
@CrossOrigin(origins = "*", maxAge = 3600)
```

同時允許所有來源 + 允許 credentials = **完全沒有 CORS 保護**。

**修復建議**：
- 移除 `AuthController` 上的 `@CrossOrigin`（已有全域設定）
- `allowedOriginPatterns` 改為實際前端網域清單
- 正式環境絕對禁止使用 `*`

---

### C4. 用戶可自行指派管理員權限（權限提升漏洞）

**檔案**：`SignupService.java:126-143`

```java
// SignupRequest 接受客戶端傳入的 roles
private Set<String> roles;

// SignupService 直接映射
case "admin" -> roles.add(findRole(ERole.ROLE_ADMIN));
case "mod"   -> roles.add(findRole(ERole.ROLE_MODERATOR));
```

任何未認證的使用者都可以在註冊時自行賦予管理員權限。

**修復建議**：移除 `SignupRequest` 中的 `roles` 欄位，新用戶一律只指派 `ROLE_USER`。

---

## 3. 重要問題 (HIGH)

### H1. Controller 層大量 try-catch 與 GlobalExceptionHandler 功能重疊

**檔案**：`VoteController.java`（455 行，幾乎每個方法都有 try-catch）

```java
// 每個 endpoint 都重複的模式
try {
    // 業務邏輯
} catch (Exception e) {
    log.error("Failed to ...", e);
    return ResponseEntity.badRequest()
        .body(ApiResponse.error(ErrorType.XXX, e.getMessage()));
}
```

`GlobalExceptionHandler` 已完整處理所有自定義例外。Controller 的 try-catch 不僅多餘，還會：
- 吞掉例外或錯誤映射狀態碼（如 `getVote` 將所有例外映射為 404）
- 將 `e.getMessage()` 洩漏給客戶端（可能包含 SQL 片段、內部 ID 等）

**修復建議**：移除 Controller 的 try-catch，讓例外自然傳播到 GlobalExceptionHandler。

```java
// 改善後 — 簡潔安全
@GetMapping("/{voteId}")
public ResponseEntity<ApiResponse<VoteResponse>> getVote(@PathVariable String voteId) {
    VoteResponse response = voteService.getVote(voteId);
    return ResponseEntity.ok(ApiResponse.success(response));
}
```

---

### H2. AuthController API 回應格式不一致

**檔案**：`AuthController.java`

同一個 Controller 混用了 **5 種回應格式**：

| 端點 | 回應類型 |
|------|----------|
| `/signin` | `JwtResponse` / `BaseResponse` |
| `/pre-signup` | `BaseResponse` |
| `/signup` | `ApiResponse` / 遞迴呼叫 signin |
| `/refreshToken` | `TokenRefreshResponse` |
| `/signout` | `MessageResponse` |
| `/force-expire-token` | `ApiResponse<Void>` |
| `/generateVerificationCode` | `BaseResponse` |

**修復建議**：統一使用 `ApiResponse<T>` 包裝，`T` 為具體的回應物件。

---

### H3. API 路徑版本不一致

```
/api/auth/**          ← 無版本號
/api/v1/votes/**      ← v1
/api/v1/users/**      ← v1
```

**修復建議**：Auth 端點遷移至 `/api/v1/auth/**`。

---

### H4. 重複的 UUID v7 Generator（死碼）

**檔案**：
- `domain/common/UuidV7Generator.java` — 手動實作，**未被任何類別引用**
- `infrastructure/generator/UUIDv7Generator.java` — 使用 `java-uuid-generator` 函式庫，**實際使用中**

**修復建議**：刪除 `domain/common/UuidV7Generator.java`。

---

### H5. `verificationCode` 欄位命名嚴重混淆

**檔案**：`User.java:54`、`UserServiceImpl.java:87`

`verificationCode` 欄位實際儲存 BCrypt hash，作為 Spring Security 的「密碼」。但 `changePassword` 方法操作的卻是這個欄位：

```java
// UserServiceImpl.java:87 — 名為「改密碼」實際操作「驗證碼」
public boolean changePassword(String userId, String currentPassword, String newPassword) {
    if (!passwordEncoder.matches(currentPassword, user.getVerificationCode())) { ... }
    user.setVerificationCode(passwordEncoder.encode(newPassword));
}
```

**修復建議**：
- 若為無密碼設計，移除 `changePassword` 方法
- 若保留，重新命名欄位為 `credential` 或 `passwordHash`

---

### H6. pom.xml 問題

**檔案**：`pom.xml`

| 問題 | 行號 | 說明 |
|------|------|------|
| `jwt.version` 重複定義 | 30, 59 | `0.11.5` 被 `0.12.6` 覆蓋，易混淆 |
| `mysql-connector.version` | 42 | 未使用（專案用 PostgreSQL） |
| `fastexcel.version` | 51 | 定義了版本但無對應 dependency |
| `poi.version` | 52 | 定義了版本但無對應 dependency |

**修復建議**：移除重複及未使用的屬性。

---

### H7. AuthController signup 端點安全隱患

**檔案**：`AuthController.java:181-198`

```java
public ResponseEntity<?> registerUser(@Valid @RequestBody SignupRequest signUpRequest) {
    var result = signupService.processSignup(signUpRequest);
    if (result.isSuccess()) {
        // 產生新驗證碼並直接呼叫 signin Controller 方法
        String verificationCode = authService.generateVerificationCode(signUpRequest.getEmail());
        return signin(new SigninRequest(signUpRequest.getEmail(), verificationCode));
    }
}
```

問題：
1. **繞過 Rate Limiter** — 直接呼叫 `signin()` 不經過 Resilience4j 攔截
2. **Controller 互相呼叫**是反模式
3. 產生的新驗證碼未經使用者驗證就直接用於登入

**修復建議**：將「註冊後自動登入」邏輯提取到 Service 層。

---

## 4. 中等問題 (MEDIUM)

### M1. VoteServiceImpl 的 N+1 查詢與記憶體問題

**檔案**：`VoteServiceImpl.java:193-196`

```java
// 載入所有 UserVote 到記憶體再做 distinct count — 資料量大時爆記憶體
int totalParticipants = (int) userVoteRepository.findByVoteId(voteUuid).stream()
    .map(mv -> mv.getUser().getId())  // 每筆 lazy-load User — N+1
    .distinct()
    .count();
```

`Vote.getTotalVotes()` 也有同樣問題：

```java
// Vote.java:106 — 載入整個 userVotes 集合只為取 count
public long getTotalVotes() {
    return userVotes.size();
}
```

**修復建議**：
- 新增 `@Query("SELECT COUNT(DISTINCT uv.user.id) FROM UserVote uv WHERE uv.vote.id = :voteId")`
- `getTotalVotes()` 改用資料庫 COUNT 或加入冗餘計數欄位

---

### M2. VoteController 過於龐大（455 行，14 個端點）

投票 CRUD + 投票操作 + 留言 CRUD + 按讚/取消按讚全部在同一個 Controller。

**修復建議**：將留言相關端點（第 287-454 行）拆分為 `VoteCommentController`。

---

### M3. Service 層大量使用 RuntimeException

```java
// RefreshTokenService.java:36
throw new RuntimeException("User not found with id: " + userId);

// UserServiceImpl.java:199
throw new RuntimeException("User not found: " + username);

// SignupService.java:147
throw new RuntimeException("Role not found: " + roleEnum);
```

**修復建議**：統一使用 `EntityNotFoundException`，讓 GlobalExceptionHandler 正確映射 HTTP 狀態碼。

---

### M4. BaseEntity 與 User 的 Lombok 註解衝突

**檔案**：`BaseEntity.java`、`User.java`

```java
// BaseEntity — @Data 產生 equals/hashCode，但又手動覆寫
@Data
public abstract class BaseEntity {
    @Override
    public boolean equals(Object o) { ... }  // 手動覆寫，@Data 產生的被浪費
    @Override
    public int hashCode() { ... }
}

// User — @EqualsAndHashCode(callSuper=true) 覆蓋了 BaseEntity 精心設計的 ID-based equals
@EqualsAndHashCode(callSuper = true)
@Data
public class User extends BaseEntity { ... }
```

**修復建議**：
- `BaseEntity`：`@Data` → `@Getter @Setter`
- `User`：`@Data` → `@Getter @Setter`，移除 `@EqualsAndHashCode`

---

### M5. generateVerificationCode 端點問題

**檔案**：`AuthController.java:274-295`

1. **使用 GET 方法**：產生驗證碼有副作用（寫 Redis、更新 DB、發送郵件），應為 POST
2. **日誌洩漏驗證碼**：

```java
// 任何能讀取日誌的人都能取得有效登入憑證
log.info("generateVerificationCode for email: {}, verificationCode: {}", email, verificationCode);
```

**修復建議**：改為 `@PostMapping`，移除驗證碼的日誌記錄。

---

### M6. AuthTokenFilter 使用 @Autowired 欄位注入

**檔案**：`AuthTokenFilter.java:28-31`

```java
@Autowired private JwtUtils jwtUtils;
@Autowired private UserDetailsServiceImpl userDetailsService;
```

**修復建議**：改為建構子注入（`@RequiredArgsConstructor`），與專案其他類別一致。

---

### M7. VoteServiceImpl 中不安全的 `.get()` 呼叫

**檔案**：`VoteServiceImpl.java:167, 183`

```java
// 並發刪除場景下會拋出 NoSuchElementException
return convertToVoteResponse(voteRepository.findById(UUID.fromString(voteId)).get());
```

**修復建議**：改為 `.orElseThrow(() -> new VoteNotFoundException(voteId))`。

---

## 5. 建議改善 (LOW)

| # | 項目 | 說明 |
|---|------|------|
| L1 | `Vote` 使用 `LocalDateTime` | 不含時區，多時區環境下可能出錯。建議改用 `Instant` |
| L2 | `VoteMapper` 手動映射 | 已引入 MapStruct 但未使用，可改為 MapStruct interface 減少 boilerplate |
| L3 | `@PreAuthorize` 重複表達式 | `hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')` 出現 10+ 次。可改為 `hasAnyRole(...)` 或自定義註解 |
| L4 | 缺少 `@Version` 樂觀鎖定 | 高並發投票場景可能出現 lost update |
| L5 | 缺少 JPA `@Index` 定義 | `votes.creator_id`、`user_votes(user_id, vote_id)`、`vote_comments.vote_id` 等常用查詢欄位未定義索引 |
| L6 | `User.updateUsershipLevel()` 硬編碼 | magic number 10000/5000/1000 應抽取為具名常量或配置 |
| L7 | `UserVote` 缺少明確業務建構子 | 目前依賴 `@AllArgsConstructor`，欄位順序由 Lombok 決定 |

---

## 6. 架構分析

### 6.1 分層評估

| 層 | 評估 | 說明 |
|---|------|------|
| Controller | 待改善 | try-catch 過度、VoteController 過大、回應格式不一致 |
| Service | 良好 | 職責分離清晰（AuthService/SignupService/VoteService 拆分合理） |
| Repository | 良好 | Spring Data JPA 標準用法 |
| Entity/Domain | 待改善 | Lombok 衝突、欄位命名混淆、缺少樂觀鎖 |
| Infrastructure | 良好 | Redis 封裝完整、配置外部化、審計 AOP |

### 6.2 模組依賴關係

```
Controller → Service (interface) → Repository → Entity
                ↓
            Mapper (DTO ↔ Entity)
                ↓
        Event Publisher → Event Listener
```

依賴方向正確，Service 透過介面解耦。但 `AuthController` 直接依賴 `UserDetailsServiceImpl`（具體實作），違反依賴反轉原則。

### 6.3 安全架構

```
Request → AuthTokenFilter → SecurityFilterChain → Controller
              ↓ (JWT 驗證)        ↓ (路徑規則)      ↓ (@PreAuthorize)
         JwtUtils           WebSecurityConfig    Method Security
              ↓
         [缺少] JwtBlacklistService 檢查
```

關鍵缺口：JWT 黑名單檢查未整合到過濾鏈中。

### 6.4 認證流程

```
[Pre-signup] → 產生 OTP → 存入 Redis → 發送 Email
                                ↓
[Signup] → 驗證 Redis OTP → 建立 User（OTP hash 存為 credential）
                                ↓
[Signin] → 驗證 Redis OTP → 產生 JWT + RefreshToken
```

設計合理，但 `verificationCode` 欄位兼任多重角色（OTP 暫存 + 永久 credential hash），語意不清。

---

## 7. 優點

| 項目 | 說明 |
|------|------|
| UUID v7 主鍵 | 時間排序 UUID，兼顧 B-tree 效能與唯一性 |
| 統一回應格式 | `ApiResponse<T>` + `ErrorType` 枚舉提供一致的 API 結構 |
| GlobalExceptionHandler | 完整覆蓋各類例外，回應格式統一 |
| Event-Driven 設計 | `VoteCreatedEvent`/`VoteCastEvent`/`VoteDeactivatedEvent` 解耦副作用 |
| 配置外部化 | `VoteConfigurationProperties` 將業務規則配置化，含合理預設值 |
| 審計日誌 | `@Auditable` AOP 切面自動記錄操作 |
| 軟刪除 | `VoteComment.softDelete()` + 域行為 `canBeEditedBy()` |
| Redis 快取 | `CacheUtil` 封裝良好，支援故障自動回退（fallback to DB） |
| 國際化 | 支援 zh_TW + en 多語系 |
| Rate Limiting | Resilience4j 限流保護認證端點 |
| Service 拆分 | Auth/Signup/PreSignup 職責分離清晰 |
| Domain Event | 投票操作觸發事件，方便擴展通知/統計等功能 |

---

## 8. 建議優先處理順序

### Phase 1：安全性修復（立即處理）

| # | 項目 | 影響 | 預估複雜度 |
|---|------|------|-----------|
| 1 | C4 — 移除 SignupRequest.roles 欄位 | 權限提升漏洞 | 低 |
| 2 | C1 — AuthTokenFilter 加入 JWT 黑名單檢查 | Token 強制過期無效 | 低 |
| 3 | C3 — 收斂 CORS 設定 | 無 CORS 保護 | 低 |
| 4 | M5 — 移除驗證碼日誌 + GET 改 POST | 憑證洩漏 | 低 |
| 5 | H7 — signup 自動登入改走 Service 層 | 繞過限流 | 中 |

### Phase 2：程式品質提升

| # | 項目 | 影響 | 預估複雜度 |
|---|------|------|-----------|
| 6 | H1 — 移除 Controller try-catch | 錯誤狀態碼、資訊洩漏 | 中 |
| 7 | H2 — 統一 AuthController 回應格式 | API 一致性 | 中 |
| 8 | M3 — RuntimeException 改為自定義例外 | 錯誤處理不正確 | 低 |
| 9 | M4 — 修復 Lombok 註解衝突 | equals/hashCode 異常 | 低 |
| 10 | H4 — 刪除重複 UuidV7Generator | 死碼 | 低 |
| 11 | H6 — 清理 pom.xml | 維護性 | 低 |
| 12 | M6 — AuthTokenFilter 改建構子注入 | 一致性 | 低 |
| 13 | M7 — `.get()` 改 `.orElseThrow()` | 並發安全 | 低 |

### Phase 3：效能與架構優化

| # | 項目 | 影響 | 預估複雜度 |
|---|------|------|-----------|
| 14 | M1 — VoteService N+1 查詢優化 | 效能 | 中 |
| 15 | M2 — 拆分 VoteCommentController | 可維護性 | 中 |
| 16 | H3 — 統一 API 版本路徑 | 一致性（需前端配合） | 中 |
| 17 | H5 — verificationCode 欄位重新命名 | 語意清晰（需 DB migration） | 高 |

### Phase 4：測試補強

| # | 項目 | 目標 |
|---|------|------|
| 18 | VoteServiceImpl 單元測試 | 核心投票邏輯 |
| 19 | AuthService + SignupService 單元測試 | 認證/註冊流程 |
| 20 | Controller 整合測試（MockMvc） | API 層驗證 |
| 21 | AuthTokenFilter 單元測試 | JWT 過濾（含黑名單） |
| 22 | 覆蓋率達 80% | 專案目標 |

---

> **總結**：專案整體架構方向正確，Service 層職責分離清晰，事件驅動與快取設計良好。主要風險集中在**安全性**（JWT 黑名單未生效、CORS 過於寬鬆、角色提升漏洞）和**程式碼品質**（Controller 錯誤處理模式不當、回應格式不一致、Lombok 衝突）。建議優先處理 Phase 1 的 5 項安全問題（均為低複雜度），可在短時間內顯著提升系統安全性。
