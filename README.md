# Vomatt API

Vomatt 投票社群後端。架構沿用 sachmis-api（domain-driven 套件 + 集中式 entity / repository / common）。

## 技術棧

- Java 25（虛擬執行緒）、Spring Boot 4.0.5、Maven Wrapper
- Spring Web MVC、Data JPA（Hibernate 7）、Security 7、Mail、Data Redis（Jedis）
- PostgreSQL（schema `vomatt`）、Redis
- JJWT、Bucket4j、SpringDoc OpenAPI 3、Lombok + MapStruct、Thymeleaf（email）

## 快速開始

1. `docker compose up -d`：本地 Postgres + Redis，首次啟動自動套用 `src/main/resources/db/schema.sql`
2. `cp .env.example .env` 並填入 JWT_SECRET 等變數（DB / Redis 預設值即對應 docker-compose）
3. 啟動：`./mvnw spring-boot:run`；`EMAIL_ENABLED=false` 時 OTP 不寄信，可從 Redis 取：`docker compose exec redis redis-cli GET otp:email:<email>`
4. Swagger UI：<http://localhost:8080/swagger-ui.html>

## 功能

| Domain | 路徑 | 說明 |
|--------|------|------|
| auth | `/api/auth/**` | Email / 手機 OTP、Google / LINE / Apple 登入、refresh、logout |
| users | `/api/users/**` | 我的資料、公開個人頁、欄位可見度、搜尋、刪除帳號 |
| votes | `/api/votes/**` | 建立 / 查詢 / 投票 / 取消 / 結果 / 停用 |
| comments | `/api/votes/{voteId}/comments/**` | 留言、按讚 |
| tags | `/api/tags/**` | 標籤查詢（公開） |
| lookups | `/api/lookups/**` | 字典表查詢 |
| admin | `/api/admin/**` | 標籤、字典表管理（需 ADMIN） |

回應格式統一為 `ApiResponse<T>`：`{ success, data, message, errorCode, error }`；分頁為 `CursorResponse<T>`（`items / nextCursor`；`limit` 預設 20、上限 50）。

前端串接請見 [docs/frontend/README.md](docs/frontend/README.md)（Swagger 用法、全域慣例、名詞對照）。

既有資料庫從舊 schema 升級：`psql "$DB" -v ON_ERROR_STOP=1 -f src/main/resources/db/migrations/2026-10-02-sachmis-arch.sql`（單一交易，執行前先備份並確認檔頭的時區假設）。

開發規範見 [AGENTS.md](AGENTS.md)。
