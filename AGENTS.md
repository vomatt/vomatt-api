# AGENTS.md

This file provides guidance to AI coding agents working with code in this repository.

Vomatt 投票社群後端：Java 25（虛擬執行緒）、Spring Boot 4.0.5（Security 7、Jackson 3 `tools.jackson.*`、Hibernate 7）、PostgreSQL（schema `vomatt`）、Redis（Jedis）。回答使用繁體中文，程式碼註解用英文。

## 指令

```bash
docker compose up -d                 # 本地 Postgres + Redis，首次啟動套用 db/schema.sql
cp .env.example .env                 # 填 JWT_SECRET 等
./mvnw spring-boot:run               # EMAIL_ENABLED=false 時 OTP 從 Redis 取：redis-cli GET otp:email:<email>
./mvnw clean package -DskipTests
./mvnw test
./mvnw test -Dtest=VoteServiceTest   # 單一測試類別；-Dtest=VoteServiceTest#shouldXxx 跑單一方法
./mvnw test -Dsnapshot.update=true   # 重新產生 docs/frontend/openapi.json 與 error-codes.md
```

Swagger UI：<http://localhost:8080/swagger-ui.html>。`*PostgresTest`、`OpenApiSnapshotTest` 用 Testcontainers 起 Postgres 17，需要 Docker。

## 架構

- 套件以 domain 分：`auth`、`users`、`votes`、`comments`、`notifications`、`tags`、`lookups`、`admin`，各自 Controller + Service + Mapper + `dto/`。JPA entity 全部集中在 `entity/`，repository 全部集中在 `repository/`，共用元件在 `common/`。
- Service 只有一個實作時不拆 interface / Impl；`@Transactional` 放在 Service 層。
- 程式碼與 API 路徑沿用 `Vote` 命名，業務用語以 `CONTEXT.md` 為準（Poll、Ballot、Selection、Participant、Sealed、Turnout、Support…）。改名會破壞合約，所以不改。
- Poll 狀態不存欄位，由時間推導：`Vote.statusAt()`，Ended 優先於 Scheduled。Cancelled 是 `endTime < startTime` 的 Ended Poll。
- 結果封存（Sealed）只在 `VoteMapper` 一處處理：Poll 未 Ended 時，各選項票數一律為 null。新增回傳 `VoteResponse` 的路徑一定要經過 mapper。
- 選項票數存在 `vote_options.vote_count`，以原子加減維護；`db/reconcile-vote-counts.sql` 可用來校正。
- 前台列表使用 cursor 分頁（`CursorResponse` / `Cursor`，`limit` 預設 20、上限 50），keyset 查詢寫在 `VoteListRepository`。標題與描述的搜尋走自建 bigram GIN index，`q` 至少 2 字元。
- 「Poll 結束」通知在讀取時推算，資料庫只存已讀狀態（`notification_reads`），沒有排程器。
- 回應一律包成 `ApiResponse<T>`（`success, data, message, errorCode, error`）。錯誤用 `throw ApiException.xxx(MessageKey.X)`，訊息放在 `resources/i18n/messages*.properties`，由 `GlobalExceptionHandler` 統一處理。
- 認證：JWT access token 搭配 Redis refresh token（rotation + grace window + reuse 偵測）。`User.active=false` 即停權。`User.roles`（`text[]`）會轉成 `ROLE_<UPPER>`。
- 路由權限全部定義在 `common/security/SecurityEndpoints`，不要直接改 `SecurityConfig` 的 filter chain。公開 Poll 路徑只比對 UUID，需登入的具名子路徑（`/my`、`/search`…）排在公開規則之前。`/api/admin/**` controller 要在類別層級加 `@PreAuthorize("hasRole('ADMIN')")`。

## 資料庫

- DDL 唯一來源是 `src/main/resources/db/schema.sql`（`ddl-auto: none`）。時間欄位一律 `TIMESTAMPTZ` ↔ `OffsetDateTime`，主鍵使用 UUID v7（`UUIDv7Generator`）。
- schema 變更寫成可重複執行的 `ALTER TABLE ... IF NOT EXISTS`，正式環境（Neon）手動執行 `schema.sql`。舊 schema 升級用 `db/migrations/2026-10-02-sachmis-arch.sql`。

## API 合約與前端文件

- `docs/frontend/` 是唯一進版控的 docs 目錄，其餘 `docs/*` 都已 gitignore。
- Controller 端點必須有 `@Operation` 搭配 `@CommonApiResponses` 或 `@PublicApiResponse`。`@Operation(description)` 依序寫 Auth / Precondition / Behavior / Side effects / Errors；request 與 response 至少各有一個 `@ExampleObject`。OpenAPI 文字（summary、description、`@Schema`）一律用英文。不要回傳泛型 `Map` / `Object`。
- `docs/frontend/openapi.json` 與 `docs/frontend/error-codes.md` 由 `OpenApiSnapshotTest`、`ErrorCodesDocTest` 產生，不要手改。合約或 `MessageKey` 有變動時，加上 `-Dsnapshot.update=true` 重新產生並一起提交，否則測試會失敗。
- 合約變更要記錄在 `docs/frontend/CHANGELOG.md`，breaking change 標 💥。

## 測試

- Controller 用 `MockMvcBuilders.standaloneSetup`，Service 用 `@ExtendWith(MockitoExtension.class)`。需要真實 SQL 的測試繼承 `PostgresRepositoryTest`（共用的 Testcontainers Postgres，已載入 `schema.sql`）；`VoteServiceSlice` 提供 VoteService 在 `@DataJpaTest` slice 裡需要的 bean。
- 測試方法命名：`shouldXxxWhenYyy`。

## Commit

Conventional Commits：`type(scope): 描述`，描述用繁體中文、祈使語氣，subject 不超過 50 字、結尾不加句號。body 說明「為什麼」與主要變更點。
