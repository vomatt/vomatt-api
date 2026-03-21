# 投票體驗強化設計規格

**日期**: 2026-03-21
**狀態**: Draft
**類型**: 功能增強
**目標**: 強化投票系統的核心體驗，建立產品差異化

---

## 背景

Vomatt 是社群/社交型投票平台。目前已具備基礎的投票建立、投票、留言等功能。本次設計聚焦於提升投票體驗本身，透過標籤分類、多元投票類型、模板、即時結果和熱門排行，讓投票成為更豐富、更有吸引力的社交行為。

## 功能總覽

| Phase | 功能 | 新實體 | 新端點 | 複雜度 |
|-------|------|--------|--------|--------|
| 1 | 標籤/分類系統 | Tag（Vote 用 @ManyToMany） | 5 個 | 低 |
| 2 | 更多投票類型 | UserRanking, UserRating | 2 個新 + 修改現有 | 中高 |
| 3 | 熱門排行 | 無（Vote 加欄位） | 2 個 | 低 |
| 4 | 投票模板 | VoteTemplate | 5 個 | 低 |
| 5 | 即時結果 WebSocket | 無 | 2 個頻道 | 中 |

## 實作順序

```
Phase 1: 標籤系統 → Phase 2: 投票類型 → Phase 3: 熱門排行 → Phase 4: 模板 → Phase 5: WebSocket
```

**理由**:
- 標籤先行：後續功能都受益（熱門排行可按標籤篩選、模板綁建議標籤）
- 投票類型第二：核心產品差異化，模板依賴投票類型存在
- 熱門排行第三：利用 Phase 2 累積的多樣化投票內容
- 模板第四：依賴標籤 + 投票類型都完成
- WebSocket 最後：錦上添花，且前端工作量較大

## 向後相容性

所有 DTO 變動均為**新增 optional 欄位 + 預設值**，不破壞現有前端：
- `CreateVoteRequest` 新增欄位皆為 optional
- `VoteResponse` 新增欄位為額外資訊
- 現有端點行為不變

---

## Phase 1: 標籤/分類系統

### 設計決策

- 採用**系統預設標籤**，管理員建立與管理，用戶從清單中選擇
- 不開放用戶自建標籤，避免早期產生重複/低品質標籤
- 未來需要時可輕鬆開放用戶建標籤

### 資料模型

#### Tag 實體

| 欄位 | 類型 | 說明 |
|------|------|------|
| id | UUID v7 | 主鍵（繼承 BaseEntity） |
| name | VARCHAR(30), UNIQUE | 標籤名稱 |
| slug | VARCHAR(50), UNIQUE | URL 友好的標識符 |
| description | VARCHAR(200), NULLABLE | 標籤說明 |
| displayOrder | INT, DEFAULT 0 | 顯示順序 |
| usageCount | INT, DEFAULT 0 | 使用次數 |
| created_at | TIMESTAMP | 建立時間（繼承 BaseEntity） |
| updated_at | TIMESTAMP | 更新時間（繼承 BaseEntity） |

#### Vote ↔ Tag 關聯（@ManyToMany + @JoinTable）

使用 `@ManyToMany` 映射，不建立獨立的 VoteTag Entity。JPA 自動管理 `vote_tags` 關聯表：

```
vote_tags 表：
- vote_id (UUID, FK → votes, PK)
- tag_id (UUID, FK → tags, PK)
- PRIMARY KEY (vote_id, tag_id)
```

#### Slug 規則

- ADMIN 建立標籤時可手動指定 slug，若未提供則自動從 name 生成
- 自動生成規則：英文轉小寫 + 空格轉連字號、中文保留原文（例：「美食」→「美食」，「Tech News」→「tech-news」）
- 合法字符：小寫英文、數字、連字號、中日韓字符

### 索引

- `tags.name` — UNIQUE INDEX
- `tags.slug` — UNIQUE INDEX
- `tags.usage_count` — INDEX（熱門排序）
- `vote_tags.tag_id` — INDEX（按標籤查投票）

### API 端點

#### 管理端點（ADMIN 角色限定）

| Method | Endpoint | 說明 |
|--------|----------|------|
| POST | `/api/v1/admin/tags` | 建立標籤 |
| PUT | `/api/v1/admin/tags/{tagId}` | 更新標籤 |
| DELETE | `/api/v1/admin/tags/{tagId}` | 刪除標籤（無投票引用時） |

#### 公開端點

| Method | Endpoint | 說明 |
|--------|----------|------|
| GET | `/api/v1/tags` | 取得所有標籤（依 displayOrder 排序） |
| GET | `/api/v1/tags/popular` | 熱門標籤（依 usageCount 排序，分頁） |
| GET | `/api/v1/votes?tag={slug}` | 依標籤篩選投票（擴充現有端點） |

### DTO 變動

- `CreateVoteRequest` 新增 `tagIds: List<UUID>` 欄位（optional，最多 5 個）
- `VoteResponse` 新增 `tags: List<TagDto>` 欄位
- 新增 `TagDto`: `id`, `name`, `slug`, `description`
- 新增 `CreateTagRequest`: `name`, `slug (optional)`, `description`, `displayOrder`
- 新增 `UpdateTagRequest`: `name`, `slug`, `description`, `displayOrder`

### 行為規則

- 建立投票時可選 0~5 個現有標籤
- 傳入不存在的標籤 ID 時回傳 400 錯誤
- 標籤名稱正規化：去首尾空白
- `usageCount` 更新使用 `@Query` + `@Modifying` 原子操作（`UPDATE tags SET usage_count = usage_count + 1 WHERE id = ?`），避免並發問題
- `usageCount` 僅在投票**建立**時 +1；投票 deactivate（軟刪除）時**不**減少；投票真正刪除時 -1
- 刪除標籤時，若有投票引用則回傳 409 Conflict

### 資料庫 Migration

`V9__add_tag_system.sql`

---

## Phase 2: 更多投票類型

### 新增投票類型

| 類型 | 說明 | 使用場景 |
|------|------|----------|
| STANDARD | 現有的單選/多選 | 通用投票 |
| IMAGE | 選項附帶圖片 | 美食對決、穿搭選擇 |
| RANKING | 拖拉排序選項 | 偏好排名、Top N |
| YES_NO | 只有贊成/反對 | 快速意見調查 |
| RATING | 1~5 星評分 | 滿意度、評價 |

### 資料模型變動

#### Vote 實體新增

| 欄位 | 類型 | 說明 |
|------|------|------|
| voteType | VARCHAR(20), NOT NULL, DEFAULT 'STANDARD' | 投票類型 |

Migration 需包含：`ALTER TABLE votes ADD COLUMN vote_type VARCHAR(20) NOT NULL DEFAULT 'STANDARD'`（現有投票自動為 STANDARD）。

#### VoteOption 實體新增

| 欄位 | 類型 | 說明 |
|------|------|------|
| imageUrl | VARCHAR(500), NULLABLE | IMAGE 類型使用 |

#### 新增 UserRanking 實體（RANKING 類型專用）

| 欄位 | 類型 | 說明 |
|------|------|------|
| id | UUID v7 | 主鍵（繼承 BaseEntity） |
| user_id | UUID, FK → users | 用戶 ID |
| vote_id | UUID, FK → votes | 投票 ID |
| option_id | UUID, FK → vote_options | 選項 ID |
| rankPosition | INT | 排名位置（1 = 最高） |
| created_at | TIMESTAMP | 建立時間（繼承 BaseEntity） |
| updated_at | TIMESTAMP | 更新時間（繼承 BaseEntity） |
| UNIQUE(user_id, vote_id, option_id) | | 唯一約束 |

#### 新增 UserRating 實體（RATING 類型專用）

| 欄位 | 類型 | 說明 |
|------|------|------|
| id | UUID v7 | 主鍵（繼承 BaseEntity） |
| user_id | UUID, FK → users | 用戶 ID |
| vote_id | UUID, FK → votes | 投票 ID |
| score | INT, CHECK (1~5) | 評分 |
| created_at | TIMESTAMP | 建立時間（繼承 BaseEntity） |
| updated_at | TIMESTAMP | 更新時間（繼承 BaseEntity） |
| UNIQUE(user_id, vote_id) | | 唯一約束 |

### 各類型行為規則與參與度計算

| 類型 | 投票方式 | 結果計算 | 參與記錄 | totalVotes 語意 |
|------|----------|----------|----------|-----------------|
| STANDARD | 選 1 或多個選項 | 各選項票數/百分比 | UserVote（現有） | 總票數（含多選） |
| IMAGE | 同 STANDARD，選項多一張圖 | 同 STANDARD | UserVote（現有） | 同 STANDARD |
| RANKING | 對所有選項排序 | 各選項平均排名、Borda 計分 | UserRanking | 參與排名的人數 |
| YES_NO | 固定兩選項 | 贊成/反對比例 | UserVote（現有） | 總票數 |
| RATING | 給 1~5 分 | 平均分數、分數分佈 | UserRating | 評分的人數 |

**totalParticipants**: 所有類型統一為「不重複的參與人數」。

### API 變動

- `CreateVoteRequest` 新增 `voteType` 欄位（optional，預設 STANDARD）
- IMAGE 類型：`VoteOptionRequest` 新增 `imageUrl` 欄位
- YES_NO 類型：前端傳入兩個選項（如「贊成」「反對」），後端驗證恰好 2 個選項（不硬編碼文字，支援 i18n）
- 新增 `POST /api/v1/votes/{voteId}/rank` — body: `List<{optionId, position}>`
- 新增 `POST /api/v1/votes/{voteId}/rate` — body: `{score: 1~5}`
- 結果端點 `GET /api/v1/votes/{voteId}/results` 根據 voteType 回傳不同格式

### 圖片 URL 驗證（安全性）

後端必須驗證 `imageUrl`：
1. 必須以 `https://` 開頭（禁止 `http://`、`javascript:`、`data:` 等）
2. 禁止內部 IP 範圍（`10.x.x.x`、`172.16-31.x.x`、`192.168.x.x`、`169.254.x.x`、`127.x.x.x`）
3. 長度不超過 500 字元
4. 使用自定義 `@ValidImageUrl` validator 實作

### 資料庫 Migration

`V10__add_vote_types.sql`

---

## Phase 3: 熱門排行

### Vote 實體新增快取欄位

| 欄位 | 類型 | 說明 |
|------|------|------|
| totalVoteCount | INT, NOT NULL, DEFAULT 0 | 投票數/參與數快取 |
| totalCommentCount | INT, NOT NULL, DEFAULT 0 | 留言數快取 |
| hotScore | DOUBLE, NOT NULL, DEFAULT 0.0 | 預算好的熱度分數 |

### 熱度分數計算

```
hotScore = (totalVoteCount * 2 + totalCommentCount * 3) / (timeSinceCreatedHours + 1)^1.2
```

- 投票數權重 2、留言數權重 3（留言代表更深度的參與）
- 時間衰減指數 1.2（越久的投票分數越低）
- `+ 1` 避免除零錯誤（投票剛建立時 timeSinceCreatedHours ≈ 0）

### 更新時機

- 有人投票/撤票時 → 更新 `totalVoteCount` + 重算 `hotScore`
- 有人留言/刪留言時 → 更新 `totalCommentCount` + 重算 `hotScore`
- 排程任務每小時重算活躍投票的 `hotScore`（處理時間衰減），範圍限定為 `isActive=true AND createdAt > 30天前`，使用批次更新（500 筆/批）

### API 端點

| Method | Endpoint | 說明 |
|--------|----------|------|
| GET | `/api/v1/votes/trending` | 熱門投票（依 hotScore DESC，分頁） |
| GET | `/api/v1/votes/top?period={day,week,month}` | 期間內最多投票數（分頁） |

- 兩個端點都只回傳 `isActive=true` 的投票
- 回傳格式同現有 `VoteResponse`

### 索引

- `votes.hot_score` — INDEX（加速 trending 排序）
- `(votes.is_active, votes.created_at, votes.total_vote_count)` — 複合 INDEX（加速 top 查詢）

### 資料庫 Migration

`V11__add_vote_hot_score.sql`

---

## Phase 4: 投票模板

### 資料模型

#### VoteTemplate 實體

| 欄位 | 類型 | 說明 |
|------|------|------|
| id | UUID v7 | 主鍵（繼承 BaseEntity） |
| name | VARCHAR(100) | 模板名稱 |
| description | VARCHAR(500), NULLABLE | 模板說明 |
| voteType | VARCHAR(20) | 對應的投票類型 |
| defaultTitle | VARCHAR(200), NULLABLE | 預填標題 |
| defaultOptions | JSONB | 預填選項 `[{text, description, imageUrl}]` |
| suggestedTagIds | JSONB | 建議標籤 ID 清單 `["uuid1", "uuid2"]` |
| iconUrl | VARCHAR(500), NULLABLE | 模板圖示 |
| displayOrder | INT, DEFAULT 0 | 顯示順序 |
| isActive | BOOLEAN, DEFAULT true | 是否啟用 |
| usageCount | INT, DEFAULT 0 | 使用次數 |
| createdBy | UUID, FK → users | 建立者（ADMIN） |
| created_at | TIMESTAMP | 建立時間（繼承 BaseEntity） |
| updated_at | TIMESTAMP | 更新時間（繼承 BaseEntity） |

**JSONB 欄位**: 使用 PostgreSQL JSONB 類型，Java 端透過 `@JdbcTypeCode(SqlTypes.JSON)` 映射。`defaultOptions` 對應 `List<TemplateOptionDto>`，`suggestedTagIds` 對應 `List<UUID>`。

### API 端點

| Method | Endpoint | 說明 |
|--------|----------|------|
| GET | `/api/v1/templates` | 取得所有啟用模板（依 displayOrder） |
| GET | `/api/v1/templates/{templateId}` | 取得模板詳情 |
| POST | `/api/v1/admin/templates` | 建立模板（ADMIN） |
| PUT | `/api/v1/admin/templates/{templateId}` | 更新模板（ADMIN） |
| DELETE | `/api/v1/admin/templates/{templateId}` | 刪除模板（ADMIN） |

### 行為規則

- 模板僅作為前端預填輔助，用戶選模板後可自由修改所有欄位
- `CreateVoteRequest` 新增 `templateId (optional)` — 記錄來源模板，更新 `usageCount`
- 模板由 ADMIN 管理，用戶不能自建模板

### 資料庫 Migration

`V12__add_vote_templates.sql`

---

## Phase 5: 即時結果 WebSocket

### 技術方案

使用 Spring WebSocket + STOMP 協議，搭配 SockJS fallback。

### 新增依賴

- `spring-boot-starter-websocket`

### 頻道設計

| 頻道 | 觸發時機 | 推送內容 |
|------|----------|----------|
| `/topic/votes/{voteId}/results` | 有人投票/撤票 | 各選項最新票數、百分比 |
| `/topic/votes/{voteId}/comments` | 新留言/刪留言 | 留言數更新 |

### 推送資料格式

#### STANDARD / IMAGE / YES_NO 類型

```json
{
  "voteId": "uuid",
  "voteType": "STANDARD",
  "totalVotes": 42,
  "options": [
    { "optionId": "uuid", "voteCount": 25, "percentage": 59.5 }
  ],
  "updatedAt": "2026-03-21T12:00:00"
}
```

#### RANKING 類型

```json
{
  "voteId": "uuid",
  "voteType": "RANKING",
  "totalParticipants": 20,
  "options": [
    { "optionId": "uuid", "averageRank": 1.5, "bordaScore": 85 }
  ],
  "updatedAt": "2026-03-21T12:00:00"
}
```

#### RATING 類型

```json
{
  "voteId": "uuid",
  "voteType": "RATING",
  "totalParticipants": 30,
  "averageScore": 4.2,
  "distribution": { "1": 2, "2": 1, "3": 3, "4": 10, "5": 14 },
  "updatedAt": "2026-03-21T12:00:00"
}
```

### 實作方式

- 在現有 VoteEvent 事件監聯器中新增 WebSocket 推送邏輯
- 已有 `VoteCastEvent`、`VoteDeactivatedEvent`，直接掛載
- 不改動現有 REST API，WebSocket 是額外的推送通道

### 連線管理與權限控制

- WebSocket endpoint: `/ws`
- SockJS fallback（瀏覽器不支援 WebSocket 時降級為長輪詢）
- 匿名投票（`isAnonymous=true`）：推送結果不包含投票者資訊
- **權限控制**：訂閱時檢查投票的 `isPublic` 狀態
  - `isPublic=true`：任何人可訂閱，無需認證
  - `isPublic=false`：需在 STOMP CONNECT 時提供 JWT token，且驗證用戶有權存取該投票

### 前端串接

Next.js + React 使用 `@stomp/stompjs` 套件，在 `useEffect` 中建立連線（避免 SSR 問題）。

---

## 不做的事（YAGNI）

- 自建圖片上傳服務（用外部 URL）
- Redis 排行榜（先用 DB 欄位索引）
- 用戶自建標籤/模板
- 投票類型的即時切換（建立後不可改 voteType）

## 資料庫 Migration 總覽

| 版本 | 檔案 | 內容 |
|------|------|------|
| V9 | `V9__add_tag_system.sql` | tags 表、vote_tags 關聯表 |
| V10 | `V10__add_vote_types.sql` | votes 加 vote_type（含 UPDATE 現有資料）、vote_options 加 image_url、user_rankings 表、user_ratings 表 |
| V11 | `V11__add_vote_hot_score.sql` | votes 加 total_vote_count、total_comment_count、hot_score + 複合索引 |
| V12 | `V12__add_vote_templates.sql` | vote_templates 表（含 JSONB 欄位）、votes 加 template_id |
