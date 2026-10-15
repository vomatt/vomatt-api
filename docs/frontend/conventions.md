# Global Conventions

## 1. Response wrapper `ApiResponse`

Every endpoint (including 401 / 403 / 429 from the filter layer) returns the same shape:

```json
{ "success": true, "data": { }, "message": null, "errorCode": null, "error": null }
```

| Field | Meaning |
|------|------|
| `success` | `true` on success, `false` on error |
| `data` | The payload on success; usually `null` on error, though a few business errors carry structured data |
| `message` | Text localized by `Accept-Language`. Mostly `null` on success; a few operations include toast text ready to display. On error, the error message |
| `errorCode` | A stable code on error, lowercase and dot-separated, e.g. `vote.not_found`. **Frontend branching must always use `errorCode`, never match on `message`**. `null` on success |
| `error` | Same as `message` on error (legacy field; use `message`) |

The value of `errorCode` is `MessageKey.code()` (`vote.not_found`), not the enum name (`VOTE_NOT_FOUND`).

## 2. HTTP status and frontend handling

| Status | Common `errorCode` | Frontend handling |
|--------|------------------|----------|
| 200 | - | Read `data` |
| 400 | `common.validation_failed`, `common.bad_request`, `common.missing_param`, `common.type_mismatch` | Ask the user to fix the input; see the validation format below |
| 401 | `common.unauthorized` (no token), `auth.token.expired`, `auth.token.invalid` | `expired` -> get a new token with the refresh token and retry the original request; `invalid` / `unauthorized` -> clear the signed-in state and go to the login page |
| 403 | `common.forbidden` | Signed in but lacking permission; show a no-permission message. Do not refresh, do not log out |
| 404 | Each domain's `*.not_found` | Show a not-found message |
| 409 | Each domain's conflict codes | Show the message matching the `errorCode` (e.g. Poll already ended) |
| 429 | `common.rate_limited` | Read the `Retry-After` header (seconds) and do not resend before it expires; the UI shows "try again later" |
| 500 | `common.internal_error` | Generic error message; do not retry automatically |
| 503 | `common.service_unavailable` | Transient failure (e.g. Redis); may retry (exponential backoff recommended) |

> A 401 body always has an `errorCode`. Use it to tell "expired" from "invalid"; do not log the user out based on status alone.

### 400 validation format

Only the **first** field error is returned. `message` is `"<field>: <message>"`, e.g. `"title: must not be blank"`, and `errorCode` is `common.validation_failed`. A structure for highlighting multiple fields at once is deferred; this is the current behavior, so do not assume a full list is returned.

## 3. Pagination (cursor)

List endpoints return `ApiResponse<CursorResponse<T>>`:

```json
{ "success": true, "data": { "items": [ ], "nextCursor": "..." } }
```

- `limit`: default 20, max 50; out-of-range values are **clamped** (below 1 becomes 1, above 50 becomes 50) rather than rejected.
- `nextCursor`: an opaque string; pass it back unchanged as the `cursor` parameter to get the next page. `null` means there is no next page.
- Jumping to arbitrary pages is not supported, and there is no total count.

## 4. Time, IDs, i18n, CORS

- **Time**: ISO-8601 with timezone offset (e.g. `2026-10-09T12:34:56+08:00`). The frontend converts to the user's timezone for display.
- **ID**: UUIDv7 strings. A malformed ID in the path returns 400.
- **i18n**: `Accept-Language` determines the language of `message`; `zh-TW` (default) and `en` are supported. Filter-layer errors (401 / 403 / 429) are localized the same way.
- **CORS**: allowed origins are set by `app.security.cors-allowed-origins` (env var `CORS_ALLOWED_ORIGINS`, default `http://localhost:3000`); ask the backend to add any new frontend domain.

## 5. Rate limiting

- Rate limited (configured in `rate-limit.paths`): every request under `/api/auth/**`, and write requests (POST / PUT / PATCH / DELETE) under `/api/votes/**` and `/api/users/**`. GET / HEAD / OPTIONS requests outside `/api/auth/**` are not limited.
- All limited requests from one IP share one counter.
- Counted per client IP; default 300 requests per 60 seconds (fixed window). Auth and write requests use separate counters, so heavy voting does not block login or token refresh. Exceeding it returns 429 + `Retry-After`.
- If Redis fails, `/api/auth/**` fails closed (returns 429 directly) while other paths are let through.
- Ballot writes (cast, Retract, remove Selection) also have a per-account limit: 30 per 60 seconds (fixed window), on top of the per-IP one. Exceeding it returns 429 `common.rate_limited` **without** `Retry-After`; wait about a minute. If Redis fails this check lets the request through.
- Frontend: on 429, count down using `Retry-After` and disable the submit button during the countdown. When the rate limiter itself is down, auth endpoints return 429 without `Retry-After`; show "try again later".
