# Vomatt API

Vomatt polling community backend. The architecture follows sachmis-api (domain-driven packages + centralized entity / repository / common).

## Tech stack

- Java 25 (virtual threads), Spring Boot 4.1.2, Maven Wrapper
- Spring Web MVC, Data JPA (Hibernate 7), Security 7, Mail, Data Redis (Lettuce)
- PostgreSQL (schema `vomatt`), Redis
- JJWT, SpringDoc OpenAPI 3, Lombok, Thymeleaf (email)

## Quick start

0. Just need a running API (frontend integration): `docker compose up -d --build`; see [docs/frontend/README.md](docs/frontend/README.md)
1. `docker compose up -d postgres redis`: local Postgres + Redis; Flyway applies `src/main/resources/db/migration/` when the API starts
2. `cp .env.example .env` and fill in APP_KEY, JWT_SECRET and other variables (the DB / Redis defaults match docker-compose)
3. Run: `./mvnw spring-boot:run`; with `EMAIL_ENABLED=false` no OTP email is sent; read it from the app log line `[EMAIL-NOOP] ... OTP <code>` (Redis holds only a hash)
4. Swagger UI: <http://localhost:8080/swagger-ui.html>

## API

The endpoints and their schemas are in Swagger UI (<http://localhost:8080/swagger-ui.html>) and the generated [docs/frontend/openapi.json](docs/frontend/openapi.json); error codes in [docs/frontend/error-codes.md](docs/frontend/error-codes.md), contract changes in [docs/frontend/CHANGELOG.md](docs/frontend/CHANGELOG.md).

Responses are always `ApiResponse<T>`: `{ success, data, message, errorCode, error }`; pagination uses `CursorResponse<T>` (`items / nextCursor`; `limit` defaults to 20, max 50).

For frontend integration see [docs/frontend/README.md](docs/frontend/README.md) (Swagger usage, global conventions, glossary).

Development guidelines: see [AGENTS.md](AGENTS.md).
