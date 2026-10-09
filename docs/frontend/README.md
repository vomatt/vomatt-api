# Vomatt API 前端串接指南

## 文件分工

| 內容 | 在哪裡看 |
|------|----------|
| 單一 API 的路徑、參數、欄位、型別、錯誤範例 | Swagger（`/swagger-ui.html`、`/v3/api-docs`） |
| 跨多支 API 的流程、全域慣例、名詞對照 | 本目錄 Markdown |

Swagger 是規格的唯一來源；Markdown 不重抄欄位，只連回 Swagger。

## 在本機開啟 Swagger

Swagger 預設關閉。啟動時設定環境變數：

```bash
SWAGGER_ENABLED=true ./mvnw spring-boot:run
```

- Swagger UI：<http://localhost:8080/swagger-ui.html>
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>

Production 不開放。

## 建議閱讀順序

1. [conventions.md](conventions.md) — `ApiResponse`、HTTP status、`errorCode`、分頁、時間、i18n、CORS、限流
2. [glossary.md](glossary.md) — 領域名詞（Poll / Ballot…）↔ API 路徑 ↔ DTO
3. [auth-flow.md](auth-flow.md) — 登入、refresh、logout、401 處理
4. [error-codes.md](error-codes.md) — `errorCode` 對照表
5. `flows/*.md` — 各流程：[poll-lifecycle](flows/poll-lifecycle.md)、[comments](flows/comments.md)、[discovery](flows/discovery.md)、[notifications](flows/notifications.md)、[profile](flows/profile.md)
6. [CHANGELOG.md](CHANGELOG.md) — 面向前端的 API 變更紀錄

## 產生 TypeScript 型別

契約快照 [openapi.json](openapi.json) 由 `OpenApiSnapshotTest` 產生（勿手改）；Swagger 關閉時也能直接用它產生型別：

```bash
npx openapi-typescript docs/frontend/openapi.json -o src/api/schema.d.ts
```

根目錄舊的 `openapi.yaml` 是 Next.js 時期產物，已封存於 `docs/archive/`，不再維護，請勿使用。
