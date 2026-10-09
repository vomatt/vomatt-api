# 全域慣例

## 1. 回應包裝 `ApiResponse`

所有端點（含 filter 層的 401 / 403 / 429）都回同一個形狀：

```json
{ "success": true, "data": { }, "message": null, "errorCode": null, "error": null }
```

| 欄位 | 語意 |
|------|------|
| `success` | 成功為 `true`，錯誤為 `false` |
| `data` | 成功時為內容；錯誤時通常為 `null`，少數業務錯誤會帶結構化資料 |
| `message` | 依 `Accept-Language` 本地化的文字。成功時多為 `null`，少數操作會附上可直接顯示的 toast 文字；錯誤時為錯誤訊息 |
| `errorCode` | 錯誤時的穩定代碼，小寫點分隔，如 `vote.not_found`。**前端分支一律看 `errorCode`，不要比對 `message`**。成功時為 `null` |
| `error` | 錯誤時與 `message` 相同（舊欄位，請用 `message`） |

`errorCode` 的值是 `MessageKey.code()`（`vote.not_found`），不是列舉名稱（`VOTE_NOT_FOUND`）。

## 2. HTTP status 與前端處理

| Status | 常見 `errorCode` | 前端處理 |
|--------|------------------|----------|
| 200 | — | 讀 `data` |
| 400 | `common.validation_failed`、`common.bad_request`、`common.missing_param`、`common.type_mismatch` | 提示使用者修正輸入；見下方 validation 格式 |
| 401 | `common.unauthorized`（沒帶 token）、`auth.token.expired`、`auth.token.invalid` | `expired` → 用 refresh token 換新後重送原請求；`invalid` / `unauthorized` → 清除登入狀態，導向登入頁 |
| 403 | `common.forbidden` | 已登入但權限不足；顯示無權限，不要 refresh、不要登出 |
| 404 | 各 domain 的 `*.not_found` | 顯示找不到資源 |
| 409 | 各 domain 衝突碼 | 依 `errorCode` 顯示對應提示（如 Poll 已結束） |
| 429 | `common.rate_limited` | 讀 `Retry-After` 標頭（秒），到期前不要重送；UI 顯示稍後再試 |
| 500 | `common.internal_error` | 通用錯誤提示，不要自動重試 |
| 503 | `common.service_unavailable` | 暫時性故障（如 Redis），可重試（建議指數退避） |

> 401 的 body 一定有 `errorCode`，用它區分「過期」與「無效」，不要看 status 就一律登出。

### 400 validation 格式

目前只回**第一個**欄位錯誤，`message` 為 `"欄位名: 訊息"`，例如 `"title: must not be blank"`，`errorCode` 為 `common.validation_failed`。多欄位同時標紅的結構延後處理；這是現況，請勿假設會回完整清單。

## 3. 分頁（cursor）

列表端點回 `ApiResponse<CursorResponse<T>>`：

```json
{ "success": true, "data": { "items": [ ], "nextCursor": "…" } }
```

- `limit`：預設 20，上限 50；超出會被**夾到範圍內**（小於 1 取 1，大於 50 取 50），不會報錯。
- `nextCursor`：不透明字串，請原樣帶回 `cursor` 參數取下一頁；`null` 表示沒有下一頁。
- 不支援跳頁，也沒有總筆數。

## 4. 時間、ID、i18n、CORS

- **時間**：ISO-8601 含時區 offset（例 `2026-10-09T12:34:56+08:00`）。顯示時前端自行轉成使用者時區。
- **ID**：UUIDv7 字串。路徑中格式錯誤會得到 400。
- **i18n**：以 `Accept-Language` 決定 `message` 語言，支援 `zh-TW`（預設）、`en`。filter 層錯誤（401 / 403 / 429）同樣依此本地化。
- **CORS**：允許的來源由 `app.security.cors-allowed-origins`（環境變數 `CORS_ALLOWED_ORIGINS`，預設 `http://localhost:3000`）設定；新的前端網域需請後端加入。

## 5. 限流

- 目前只對 `/api/auth/**` 限流（設定 `rate-limit.paths`），其他路徑不限。
- 以 client IP 計算，預設每 60 秒 300 次（固定視窗），超過回 429 + `Retry-After`。
- Redis 故障時，`/api/auth/**` 為 fail-closed（直接回 429），其他路徑放行。
- 前端：收到 429 依 `Retry-After` 倒數；倒數期間停用送出按鈕。限流服務本身故障時，認證端點回 429 但不帶 `Retry-After`，請顯示稍後再試。
