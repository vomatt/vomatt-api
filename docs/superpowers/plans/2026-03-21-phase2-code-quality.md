# Phase 2 Code Quality Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 修復 CODE_REVIEW_REPORT.md Phase 2 中 8 個程式品質問題，提升錯誤處理一致性、消除死碼、修正 Lombok 衝突。

**Architecture:** 8 個獨立修改，按依賴順序執行：先修 RuntimeException（M3）→ 再移除 Controller try-catch（H1）→ 其餘獨立項目。

**Tech Stack:** Java 21, Spring Boot 3.5.x, Lombok, MapStruct, jjwt 0.12.6

---

## 修改檔案總覽

| Task | 檔案 | 變更 |
|------|------|------|
| M3 | `UserServiceImpl.java` | RuntimeException → EntityNotFoundException |
| M3 | `RefreshTokenService.java` | RuntimeException → EntityNotFoundException |
| M3 | `SignupService.java` | RuntimeException → EntityNotFoundException |
| H1 | `VoteController.java` | 移除所有 try-catch |
| H1 | `UserController.java` | 移除 try-catch，授權邏輯改拋例外 |
| M7 | `VoteServiceImpl.java:167,183` | `.get()` → `.orElseThrow()` |
| M4 | `BaseEntity.java` | `@Data` → `@Getter @Setter` |
| M4 | `User.java` | 移除 `@EqualsAndHashCode`，`@Data` → `@Getter @Setter @ToString` |
| M6 | `AuthTokenFilter.java` | `@Autowired` → `@RequiredArgsConstructor` |
| H4 | `domain/common/UuidV7Generator.java` | 刪除（死碼） |
| H6 | `pom.xml` | 移除重複/未使用屬性 |
| H2 | `AuthController.java` | 統一回應格式為 ApiResponse<T> |

---

## Task 1: M3 — RuntimeException 改為 EntityNotFoundException

**Files:**
- Modify: `src/main/java/com/vomattapi/application/service/impl/UserServiceImpl.java`
- Modify: `src/main/java/com/vomattapi/application/service/RefreshTokenService.java`
- Modify: `src/main/java/com/vomattapi/application/service/SignupService.java`

- [x] 修改 UserServiceImpl.findUserById 與 getUserProfile
- [x] 修改 RefreshTokenService.createRefreshToken 與 deleteByUserId
- [x] 修改 SignupService.findRole

---

## Task 2: H1 — 移除 Controller try-catch

**Files:**
- Modify: `src/main/java/com/vomattapi/application/controller/VoteController.java`
- Modify: `src/main/java/com/vomattapi/application/controller/UserController.java`

- [x] VoteController 全部端點移除 try-catch
- [x] UserController 全部端點移除 try-catch，deleteUser 授權邏輯改拋 UnauthorizedOperationException

---

## Task 3: M7 — VoteServiceImpl `.get()` 改 `.orElseThrow()`

**Files:**
- Modify: `src/main/java/com/vomattapi/application/service/impl/VoteServiceImpl.java:167,183`

- [x] line 167: `.get()` → `.orElseThrow(() -> new VoteNotFoundException(voteId))`
- [x] line 183: `.get()` → `.orElseThrow(() -> new VoteNotFoundException(voteId))`

---

## Task 4: M4 — 修復 Lombok 註解衝突

**Files:**
- Modify: `src/main/java/com/vomattapi/domain/common/BaseEntity.java`
- Modify: `src/main/java/com/vomattapi/domain/user/User.java`

- [x] BaseEntity: `@Data` → `@Getter @Setter`，移除多餘的 `@Getter` import
- [x] User: 移除 `@EqualsAndHashCode(callSuper=true)`，`@Data` → `@Getter @Setter @ToString`

---

## Task 5: M6 — AuthTokenFilter 改建構子注入

**Files:**
- Modify: `src/main/java/com/vomattapi/application/security/jwt/AuthTokenFilter.java`

- [x] 移除 3 個 `@Autowired` 欄位，改用 `@RequiredArgsConstructor` + `final`

---

## Task 6: H4 — 刪除死碼 UuidV7Generator

**Files:**
- Delete: `src/main/java/com/vomattapi/domain/common/UuidV7Generator.java`

- [x] 確認無任何類別引用此檔案後刪除

---

## Task 7: H6 — 清理 pom.xml

**Files:**
- Modify: `pom.xml`

- [x] 移除重複的 `jwt.version`（保留 0.12.6）
- [x] 移除 `mysql-connector.version`
- [x] 移除 `fastexcel.version` 和 `poi.version`

---

## Task 8: H2 — 統一 AuthController 回應格式

**Files:**
- Modify: `src/main/java/com/vomattapi/application/controller/AuthController.java`

- [x] signin: `ResponseEntity<?>` → `ResponseEntity<ApiResponse<JwtResponse>>`
- [x] signout: `MessageResponse` → `ApiResponse<Void>`
- [x] refreshToken: 包裝回 `ApiResponse<TokenRefreshResponse>`
- [x] pre-signup / resend-verification: `BaseResponse` → `ApiResponse<...>`
