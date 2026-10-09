# API 變更紀錄

面向前端的契約變更。💥 BREAKING 代表前端程式需要跟著改。契約快照見 [openapi.json](openapi.json)，錯誤碼見 [error-codes.md](error-codes.md)。

## 2026-10-09

- 💥 BREAKING 401 / 403 回應（filter 層）改為帶 `ApiResponse` body 與 `errorCode`（原本 body 為空）：`common.unauthorized`、`auth.token.expired`、`auth.token.invalid`、`common.forbidden`。前端以 `errorCode` 判斷是否 refresh（見 [auth-flow.md](auth-flow.md)）。
- 💥 BREAKING 429 回應 body 改為 `ApiResponse`（`errorCode` = `common.rate_limited`），並加上 `Retry-After` header（秒）。
- 💥 BREAKING Swagger UI 與 `/v3/api-docs` 預設關閉，需設定 `SWAGGER_ENABLED=true` 才開放（production 不開放）。前端請改用 [openapi.json](openapi.json)。
- 契約來源改為 `docs/frontend/openapi.json`（由測試產生）；根目錄舊的 `openapi.yaml`（Next.js 時期）已封存至 `docs/archive/`，不再維護。
- OpenAPI 的 `summary` / `description` / `@Schema` 說明一律改為英文，並補上各端點的權限、前置條件、副作用、錯誤與範例。
- OpenAPI：所有 2xx 回應補上 response schema；回 201 的端點（建立 Poll、建立留言 / 回覆、後台新增字典項）不再多列一個 200。
- OpenAPI 版本改為 3.0（`nullable: true` 會正確輸出；3.1 會丟掉），產生的 TS 型別可為 null 的欄位會是 `T | null`。
- 未登入可讀取留言與回覆：`GET /api/votes/{voteId}/comments`、`GET /api/votes/{voteId}/comments/{commentId}/replies` 為公開端點（發留言、按讚仍需登入）。
- 公開 Poll 路徑只比對 UUID：`GET /api/votes/{voteId}` 為公開，`GET /api/votes/my` 等具名子路徑仍需登入。
- 個人資料的可見度預設：`displayName`、`bio` 在未設定時 `visibilitySettings` 回報為公開。
- 新增 `GET /api/users/{username}/votes`（公開）：使用者公開頁的 Poll 列表，只含已開放的 Poll（Open、Ended），cursor 分頁。
- 💥 BREAKING 公開個人頁會套用 `displayName`、`bio` 的可見度設定：關閉公開時回 `null`（先前一律回傳）。
- 💥 BREAKING 停權使用者的公開頁與 Poll 列表回 404 `user.not_found`。
- `totalPolls`（公開頁與 `/api/users/me`）只算已開放的 Poll，不含 Scheduled 與開放前就取消的。
- `/api/users/search/**` 子路徑也需要登入。
- 修正：新留言的 `edited` 不再被誤標為已編輯；已刪除留言的佔位 `edited` 一律為 `false`。
- 修正：編輯 Scheduled Poll 時若未帶 `startTime`，保留原開始時間。
