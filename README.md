# Vomatt API

Vomatt polling community backend. The architecture follows sachmis-api (domain-driven packages + centralized entity / repository / common).

## Tech stack

- Java 25 (virtual threads), Spring Boot 4.0.5, Maven Wrapper
- Spring Web MVC, Data JPA (Hibernate 7), Security 7, Mail, Data Redis (Lettuce)
- PostgreSQL (schema `vomatt`), Redis
- JJWT, Bucket4j, SpringDoc OpenAPI 3, Lombok + MapStruct, Thymeleaf (email)

## Quick start

0. Just need a running API (frontend integration): `docker compose up -d --build`; see [docs/frontend/README.md](docs/frontend/README.md)
1. `docker compose up -d postgres redis`: local Postgres + Redis; `src/main/resources/db/schema.sql` is applied automatically on first start
2. `cp .env.example .env` and fill in JWT_SECRET and other variables (the DB / Redis defaults match docker-compose)
3. Run: `./mvnw spring-boot:run`; with `EMAIL_ENABLED=false` no OTP email is sent, read it from Redis: `docker compose exec redis redis-cli GET otp:email:<email>`
4. Swagger UI: <http://localhost:8080/swagger-ui.html>

## Features

| Domain | Path | Description |
|--------|------|------|
| auth | `/api/auth/**` | Email / phone OTP, Google / LINE / Apple login, refresh, logout |
| users | `/api/users/**` | My profile, public profile page, field visibility, search, account deletion |
| votes | `/api/votes/**` | Create / query / vote / cancel / results / deactivate |
| comments | `/api/votes/{voteId}/comments/**` | Comments, likes |
| tags | `/api/tags/**` | Tag queries (public) |
| lookups | `/api/lookups/**` | Lookup (dictionary) queries |
| admin | `/api/admin/**` | Tag and lookup management (requires ADMIN) |

Responses are always `ApiResponse<T>`: `{ success, data, message, errorCode, error }`; pagination uses `CursorResponse<T>` (`items / nextCursor`; `limit` defaults to 20, max 50).

For frontend integration see [docs/frontend/README.md](docs/frontend/README.md) (Swagger usage, global conventions, glossary).

Upgrading an existing database from the old schema: `psql "$DB" -v ON_ERROR_STOP=1 -f src/main/resources/db/migrations/2026-10-02-sachmis-arch.sql` (single transaction; back up first and check the time zone assumption in the file header).

Development guidelines: see [AGENTS.md](AGENTS.md).
