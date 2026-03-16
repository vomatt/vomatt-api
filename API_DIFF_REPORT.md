# OpenAPI vs 現有 API 差異報告

> 產生日期：2026-03-16
> 比較對象：`openapi.yaml` (前端規格) vs 現有 Spring Boot 實作

---

## 目錄

1. [差異總覽](#差異總覽)
2. [Auth 模組差異](#1-auth-模組差異)
3. [Polls (Votes) 模組差異](#2-polls-votes-模組差異)
4. [Voting 模組差異](#3-voting-模組差異)
5. [Comments 模組差異](#4-comments-模組差異)
6. [Users 模組差異](#5-users-模組差異)
7. [OpenAPI 規格有但後端不存在的 API（需新增）](#openapi-規格有但後端不存在的-api需新增)
8. [後端存在但 OpenAPI 規格未定義的 API](#後端存在但-openapi-規格未定義的-api)
9. [Schema / DTO 差異](#schema--dto-差異)

---

## 差異總覽

| 類型 | 數量 |
|------|------|
| 欄位 / 回應格式不一致 | 8 |
| 路徑 / HTTP method 不一致 | 3 |
| OpenAPI 規格有但後端完全不存在 | 2 |
| 後端有但 OpenAPI 規格未定義 | 10 |

---

## 1. Auth 模組差異

### 1.1 POST `/api/auth/signup` — 回應格式不一致

| 項目 | OpenAPI 規格 | 現有實作 |
|------|-------------|---------|
| 回應 schema | `AuthResponse`：`{ success, token, refreshToken, errorCode }` | `ApiResponse<Void>`：`{ success, errorType, data, message, timestamp, path }` |
| 註冊成功是否回傳 token | ✅ 回傳 `token` + `refreshToken` | ❌ 不回傳 token，需要另外登入 |
| 錯誤欄位名稱 | `errorCode` | `errorType` |

**建議**：修改 `AuthController.registerUser()` 的回應，註冊成功後直接回傳 JWT token + refreshToken，符合前端期望的一步完成流程。

---

### 1.2 POST `/api/auth/signin` — 回應格式不一致

| 項目 | OpenAPI 規格 | 現有實作 |
|------|-------------|---------|
| 回應 schema | `AuthResponse`：`{ success, token, refreshToken, errorCode }` | `JwtResponse`：`{ token, type, refreshToken, id, username, email, roles, success, errorType }` |
| 回傳用戶資訊 | ❌ 不含 `id`, `username`, `email`, `roles` | ✅ 包含 `id`, `username`, `email`, `roles` |
| 錯誤欄位名稱 | `errorCode` | `errorType` |
| token type 欄位 | 無 | `type: "Bearer"` |

**建議**：精簡 `JwtResponse`，或在前端 proxy 層做轉換。統一錯誤欄位名為 `errorCode`。

---

### 1.3 POST `/api/auth/refreshToken` — 回應格式不一致

| 項目 | OpenAPI 規格 | 現有實作 |
|------|-------------|---------|
| 回應 schema | `{ accessToken, refreshToken, message }` | `TokenRefreshResponse`：`{ accessToken, refreshToken, tokenType }` |
| 額外欄位 | `message`（nullable） | `tokenType`（default: "Bearer"） |

**建議**：將 `tokenType` 移除或改為 `message`，保持一致。

---

### 1.4 GET `/api/auth/generateVerificationCode` — 回應格式不一致

| 項目 | OpenAPI 規格 | 現有實作 |
|------|-------------|---------|
| 回應 schema | `{ success }` | `BaseResponse`：`{ success, errorType }` |

**影響**：輕微，前端只讀 `success`，額外欄位不影響。

---

### 1.5 POST `/api/auth/pre-signup` — 回應格式不一致

| 項目 | OpenAPI 規格 | 現有實作 |
|------|-------------|---------|
| 回應 schema | `{ success, message }` | `BaseResponse`：`{ success, errorType }` + 實際回傳含 `sessionKey`, `expirationMinutes` |

**建議**：確認前端是否需要 `sessionKey`，若需要則更新 OpenAPI 規格。

---

## 2. Polls (Votes) 模組差異

### 2.1 GET `/api/v1/votes` — 查詢參數不一致

| 參數 | OpenAPI 規格 | 現有實作 |
|------|-------------|---------|
| `page` | ✅ | ✅（透過 Spring `Pageable`） |
| `q`（搜尋關鍵字） | ✅ | ❌ **不存在** |
| `sort`（`newest` / `oldest` / `popular`） | ✅ | ❌ **不存在**（僅 Spring 預設 sort） |
| `status`（`all` / `active` / `closed`） | ✅ | ❌ **不存在**（僅回傳 active） |
| `creatorUsername` | ✅ | ❌ **不存在** |

**建議**：需在 `VoteController` 和 `VoteService` 新增搜尋、排序、狀態篩選、創建者篩選功能。

---

### 2.2 POST `/api/v1/votes` — 建立投票請求不一致

| 欄位 | OpenAPI 規格 | 現有實作 |
|------|-------------|---------|
| `isAnonymous` / `anonymous` | `anonymous` | `isAnonymous` |
| `privacyMode` | ✅ `public` / `link-only` / `invite-only` | ❌ **不存在**（僅有 `isPublic: boolean`） |
| `invitedUsers` | ✅ 字串陣列 | ❌ **不存在** |
| `endTime` nullable | ✅ `string \| null` | ✅ `LocalDateTime`（可 null） |
| `startTime` required | ✅ required | ✅（但現有允許 null） |
| `description` | optional | optional（Size max 1000） |

**建議**：
1. 新增 `PollPrivacyMode` enum（`public`, `link-only`, `invite-only`）替代 `isPublic` boolean
2. 新增 `invitedUsers` 欄位支援邀請制投票
3. 統一欄位名：`anonymous` vs `isAnonymous`

---

### 2.3 Poll 回應 Schema — 欄位不一致

| 欄位 | OpenAPI 規格 | 現有實作 (`VoteResponse`) |
|------|-------------|---------|
| `errorCode` | ✅ `string \| null` | `errorType`（在 `BaseResponse`） |
| `votingActive` | ✅ | `isVotingActive` |
| `anonymous` | ✅ | `isAnonymous` |
| `active` | ✅ | `isActive` |
| `comments` (內嵌在 Poll) | ✅ 陣列 `{ id, author, text, createdAt }` | ❌ **不含在 VoteResponse 中**，需另外呼叫 |
| `privacyMode` | ✅ enum | ❌ **不存在** |
| `options[].votes` | ✅（投票數） | `voteCount`（欄位名不同） |
| `options[].description` | ❌ 不含 | ✅ 存在 |
| `options[].displayOrder` | ❌ 不含 | ✅ 存在 |
| `options[].createdAt` | ❌ 不含 | ✅ 存在 |
| `success` | ✅ 在 Poll 物件內 | ✅ 在 BaseResponse 父類 |
| `endTime` nullable | ✅ `string \| null` | `LocalDateTime`（可 null） |

**建議**：
1. 統一 boolean 命名：移除 `is` prefix（`active`, `votingActive`, `anonymous`）
2. 在 Poll 回應中內嵌 comments（或確認前端是否接受分開取得）
3. 新增 `privacyMode` 欄位
4. 選項的 `voteCount` → `votes`

---

### 2.4 GET `/api/v1/votes/my` vs `/api/v1/votes/my-votes` — 路徑不一致

| 項目 | OpenAPI 規格 | 現有實作 |
|------|-------------|---------|
| 路徑 | `/api/v1/votes/my` | `/api/v1/votes/my-votes` |
| 回應格式 | 分頁（`PollPage`） | `List<VoteResponse>`（非分頁） |

**建議**：
1. 路徑改為 `/api/v1/votes/my`
2. 回應改為分頁格式

---

## 3. Voting 模組差異

### 3.1 投票 API — 路徑與方式完全不同

| 項目 | OpenAPI 規格 | 現有實作 |
|------|-------------|---------|
| 投票路徑 | `POST /api/v1/votes/{pollId}/options/{optionId}` | `POST /api/v1/votes/{voteId}/vote` |
| 投票方式 | 路徑參數指定 optionId（單選） | Request Body `{ optionIds: [...] }`（支援多選） |
| 回應 | `{ success }` | `ApiResponse<VoteResponse>`（完整投票資訊） |

**建議**：這是最大的結構性差異。
- **方案 A**：後端改為 path-based 單選 API（OpenAPI 規格風格），多選時前端發多次請求
- **方案 B**：更新 OpenAPI 規格以支援 body-based 多選（推薦，因為原子性更好）

---

## 4. Comments 模組差異

### 4.1 GET `/api/v1/votes/{pollId}/comments` — 回應格式不一致

| 項目 | OpenAPI 規格 | 現有實作 |
|------|-------------|---------|
| 回應格式 | `Comment[]`（純陣列） | `ApiResponse<Page<CommentDto>>`（分頁） |
| Comment 欄位 | `{ id, author, text, createdAt }` | `{ id, voteId, userId, username, content, createdAt, updatedAt, isEdited, likeCount, isLikedByCurrentUser }` |
| 內容欄位名 | `text` | `content` |
| 作者欄位名 | `author` | `username` |
| 額外欄位 | 無 | `isEdited`, `likeCount`, `isLikedByCurrentUser`, `updatedAt` |

**建議**：
1. 欄位名統一：`content` → `text`，`username` → `author`
2. 確認前端是否需要分頁或純陣列
3. 額外欄位（like、edited 等）前端是否需要？若需要則更新 OpenAPI 規格

---

### 4.2 POST `/api/v1/votes/{pollId}/comments` — 請求/回應不一致

| 項目 | OpenAPI 規格 | 現有實作 |
|------|-------------|---------|
| 請求欄位名 | `text` | `content` |
| 回應 | `Comment { id, author, text, createdAt }` | `ApiResponse<CommentDto>`（含更多欄位） |

---

## 5. Users 模組差異

### 5.1 GET `/api/v1/users/{username}` — 後端完全不存在 🆕

**OpenAPI 規格定義**：
```
GET /api/v1/users/{username}
回應：UserProfile {
  username: string
  displayName: string | null
  bio: string | null
  joinedAt: date-time
  totalPolls: integer
  totalVotes: integer
}
```

**現有實作**：
- 僅有 `GET /api/v1/users/search?username=xxx`（模糊搜尋，分頁）
- `UserDto` 不含 `displayName`、`bio`、`totalPolls`、`totalVotes`
- `User` entity 也不含 `displayName`、`bio` 欄位

**需要實作**：
1. 新增 `GET /api/v1/users/{username}` endpoint
2. User entity 新增 `displayName`、`bio` 欄位（DB migration）
3. 新增 `UserProfileResponse` DTO
4. 計算 `totalPolls`（該用戶建立的投票數）和 `totalVotes`（該用戶投過的票數）
5. `joinedAt` 對應 `createdAt`

**設計實作建議**：

```java
// UserController 新增
@GetMapping("/{username}")
public ResponseEntity<UserProfileResponse> getUserProfile(
    @PathVariable String username) {
    return ResponseEntity.ok(userService.getUserProfile(username));
}

// UserProfileResponse（新增 DTO）
public record UserProfileResponse(
    String username,
    String displayName,
    String bio,
    LocalDateTime joinedAt,
    int totalPolls,
    int totalVotes
) {}

// UserService 新增
UserProfileResponse getUserProfile(String username);

// UserServiceImpl
public UserProfileResponse getUserProfile(String username) {
    User user = userRepository.findByUsername(username)
        .orElseThrow(() -> new EntityNotFoundException("User not found"));
    int totalPolls = voteRepository.countByCreatorId(user.getId());
    int totalVotes = userVoteRepository.countDistinctVoteByUserId(user.getId());
    return new UserProfileResponse(
        user.getUsername(),
        user.getDisplayName(),
        user.getBio(),
        user.getCreatedAt(),
        totalPolls,
        totalVotes
    );
}
```

**DB Migration**：
```sql
ALTER TABLE users ADD COLUMN display_name VARCHAR(100);
ALTER TABLE users ADD COLUMN bio TEXT;
```

---

### 5.2 PATCH `/api/v1/users/me` — 後端完全不存在 🆕

**OpenAPI 規格定義**：
```
PATCH /api/v1/users/me
Auth: Bearer JWT
Request: { displayName?: string | null, bio?: string | null }
Response: UserProfile
```

**需要實作**：
1. 新增 `PATCH /api/v1/users/me` endpoint
2. 新增 `UpdateProfileRequest` DTO
3. 從 JWT 取得當前用戶，更新 `displayName` 和 `bio`
4. 回傳更新後的 `UserProfileResponse`

**設計實作建議**：

```java
// UserController 新增
@PatchMapping("/me")
@PreAuthorize("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')")
public ResponseEntity<UserProfileResponse> updateMyProfile(
    @AuthenticationPrincipal UserDetailsImpl userDetails,
    @RequestBody UpdateProfileRequest request) {
    return ResponseEntity.ok(
        userService.updateProfile(userDetails.getId(), request));
}

// UpdateProfileRequest（新增 DTO）
public record UpdateProfileRequest(
    String displayName,
    String bio
) {}

// UserService 新增
UserProfileResponse updateProfile(String userId, UpdateProfileRequest request);
```

---

## OpenAPI 規格有但後端不存在的 API（需新增）

| # | 端點 | 說明 | 優先級 |
|---|------|------|--------|
| 1 | `GET /api/v1/users/{username}` | 取得用戶公開檔案 | 🔴 高 |
| 2 | `PATCH /api/v1/users/me` | 更新個人檔案 | 🔴 高 |

---

## 後端存在但 OpenAPI 規格未定義的 API

| # | 端點 | 說明 | 建議 |
|---|------|------|------|
| 1 | `POST /api/auth/resend-verification` | 重寄驗證碼 | 前端可能需要，建議加入規格 |
| 2 | `POST /api/auth/signout` | 登出 | 前端必要，建議加入規格 |
| 3 | `POST /api/auth/force-expire-token` | 強制失效 token | 管理功能，可選 |
| 4 | `GET /api/v1/users/search` | 搜尋用戶 | 前端可能需要 |
| 5 | `DELETE /api/v1/users/{userId}` | 刪除用戶 | 帳號管理功能 |
| 6 | `DELETE /api/v1/votes/{voteId}/vote/{optionId}` | 取消投票 | 前端可能需要 |
| 7 | `GET /api/v1/votes/{voteId}/results` | 投票結果（含百分比、投票者） | 前端可能需要 |
| 8 | `GET /api/v1/votes/{voteId}/my-vote-status` | 查詢自己的投票狀態 | 前端需要（顯示已投選項） |
| 9 | `PUT /api/v1/votes/{voteId}/deactivate` | 關閉投票 | 投票管理功能 |
| 10 | `PUT/DELETE /{voteId}/comments/{commentId}` | 編輯/刪除留言 | 前端需要 |
| 11 | `POST/DELETE /{voteId}/comments/{commentId}/like` | 按讚/取消按讚 | 前端可能需要 |

---

## Schema / DTO 差異

### 統一命名對照表

| 概念 | OpenAPI 規格 | 現有實作 | 建議統一為 |
|------|-------------|---------|-----------|
| 錯誤碼 | `errorCode` | `errorType` | `errorCode` |
| 是否啟用 | `active` | `isActive` | `active` |
| 投票中 | `votingActive` | `isVotingActive` | `votingActive` |
| 匿名 | `anonymous` | `isAnonymous` | `anonymous` |
| 選項票數 | `votes` | `voteCount` | `votes` |
| 留言內容 | `text` | `content` | `text` |
| 留言作者 | `author` | `username` | `author` |
| 隱私模式 | `privacyMode` (enum) | `isPublic` (boolean) | `privacyMode` |

### 新增欄位需求

| Entity | 需新增欄位 | 型別 | 說明 |
|--------|-----------|------|------|
| `User` | `displayName` | `VARCHAR(100)` | 顯示名稱 |
| `User` | `bio` | `TEXT` | 個人簡介 |
| `Vote` | `privacyMode` | `VARCHAR(20)` / Enum | 取代 `isPublic` |
| `Vote` | `invitedUsers` | 關聯表 | 邀請制投票的受邀用戶 |

---

## 優先修改建議

### Phase 1：修正現有 API 使其符合規格（最小變動）
1. 統一欄位命名（`errorCode`、`active`、`anonymous`、`votes`、`text`、`author`）
2. 修正路徑 `/my-votes` → `/my`
3. 修正 `signup` 回應，回傳 token

### Phase 2：新增缺失 API
1. `GET /api/v1/users/{username}` — 用戶公開檔案
2. `PATCH /api/v1/users/me` — 更新個人檔案
3. DB migration 新增 `display_name`、`bio` 欄位

### Phase 3：功能增強
1. 投票列表支援搜尋（`q`）、排序（`sort`）、狀態篩選（`status`）、創建者篩選（`creatorUsername`）
2. 新增 `privacyMode` 和 `invitedUsers` 支援
3. 統一投票 API 設計（path-based vs body-based）

### Phase 4：補齊 OpenAPI 規格
1. 將後端已有但規格未定義的 API 加入 `openapi.yaml`
2. 例如：signout、取消投票、投票結果、留言編輯/刪除/按讚等
