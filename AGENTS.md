# AGENTS.md

This file provides guidance to AI coding agents working with code in this repository.

Vomatt polling community backend: Java 25 (virtual threads), Spring Boot 4.0.5 (Security 7, Jackson 3 `tools.jackson.*`, Hibernate 7), PostgreSQL (schema `vomatt`), Redis (Lettuce). Respond in Traditional Chinese; write code comments in English.

## Commands

```bash
docker compose up -d --build         # full stack (API + Postgres + Redis) for frontend integration
docker compose up -d postgres redis  # DB only, pair with ./mvnw spring-boot:run; db/schema.sql is applied on first start
                                     # an existing volume does not rerun it: apply new db/migrations/* by hand (psql) or docker compose down -v
cp .env.example .env                 # fill in JWT_SECRET etc.
./mvnw spring-boot:run               # with EMAIL_ENABLED=false, read the OTP from Redis: redis-cli GET otp:email:<email>
./mvnw clean package -DskipTests
./mvnw test
./mvnw test -Dtest=VoteServiceTest   # single test class; -Dtest=VoteServiceTest#shouldXxx runs a single method
./mvnw test -Dsnapshot.update=true   # regenerate docs/frontend/openapi.json and error-codes.md
```

Swagger UI: <http://localhost:8080/swagger-ui.html>. `*PostgresTest` and `OpenApiSnapshotTest` start Postgres 17 with Testcontainers and need Docker.

## Architecture

- Packages are split by domain: `auth`, `users`, `votes`, `comments`, `notifications`, `tags`, `invitelists`, `lookups`, `admin`, each with Controller + Service + Mapper + `dto/`. All JPA entities live in `entity/`, all repositories in `repository/`, shared components in `common/`.
- When a Service has only one implementation, do not split it into interface / Impl; `@Transactional` goes on the Service layer.
- Code and API paths keep the `Vote` naming; business terms follow `CONTEXT.md` (Poll, Ballot, Selection, Participant, Sealed, Turnout, Support...). Renaming would break the contract, so it is not renamed.
- Poll status is not stored in a column; it is derived from time: `Vote.statusAt()`, with Ended taking precedence over Scheduled. Cancelled is an Ended Poll with `endTime < startTime`.
- Result sealing (Sealed) is handled in one place only, `VoteMapper`: while a Poll is not Ended, every option's vote count is null. Any new path that returns `VoteResponse` must go through the mapper.
- Anonymous Ballots (`user_votes.anonymous`) are excluded from the voter list (`GET /api/votes/{voteId}/voters`), which is fully hidden while other users hold fewer than `VoteService.ANONYMOUS_BALLOT_K` of them. Any new query that exposes who chose what must exclude them and apply the same k-gate: exclusion alone leaks, because per-option counts minus named voters reveal what the anonymous ones chose. Even past the gate, Anonymous Ballots that all chose the same option stay inferable (see the `ANONYMOUS_BALLOT_K` Javadoc).
- The Poll-level `votes.is_anonymous` (`anonymous` in the API) is deprecated and hides nothing on its own: `VoteService.applyVoterVisibility` keeps it equal to "Voter Visibility is NOBODY", and the response derives it the same way. Sent without `voterVisibility`, true sets NOBODY and false turns NOBODY back into OWNER (`CreateVoteRequest.resolveVoterVisibility`). Use `voterVisibility`.
- Option vote counts are stored in `vote_options.vote_count` and maintained with atomic increments/decrements; `db/reconcile-vote-counts.sql` can be used to reconcile them.
- Public lists use cursor pagination (`CursorResponse` / `Cursor`, `limit` defaults to 20, max 50); keyset queries live in `VoteListRepository`. Title and description search uses a custom bigram GIN index; `q` needs at least 2 characters.
- "Poll ended" notifications are derived at read time; the database stores only read state (`notification_reads`). There is no scheduler.
- Responses are always wrapped in `ApiResponse<T>` (`success, data, message, errorCode, error`). Raise errors with `throw ApiException.xxx(MessageKey.X)`; messages live in `resources/i18n/messages*.properties` and are handled centrally by `GlobalExceptionHandler`.
- Auth: JWT access token plus Redis refresh token (rotation + grace window + reuse detection). `User.active=false` means suspended. `User.roles` (`text[]`) is mapped to `ROLE_<UPPER>`.
- All route permissions are defined in `common/security/SecurityEndpoints`; do not edit the `SecurityConfig` filter chain directly. Public Poll paths match only UUIDs, and named sub-paths that require login (`/my`, `/search`...) are ordered before the public rules. `/api/admin/**` controllers must add `@PreAuthorize("hasRole('ADMIN')")` at the class level.

## Database

- The single source of DDL is `src/main/resources/db/schema.sql` (`ddl-auto: none`). All time columns are `TIMESTAMPTZ` <-> `OffsetDateTime`; primary keys use UUID v7 (`UUIDv7Generator`).
- Write schema changes as re-runnable `ALTER TABLE ... IF NOT EXISTS`; in production (Neon), run `schema.sql` manually. Data migrations go in `db/migrations/YYYY-MM-DD-<topic>.sql`, applied manually; each new one needs a `*MigrationPostgresTest`. The exceptions are `2026-10-02-sachmis-arch.sql` (a one-time upgrade of pre-sachmis schemas) and `2026-10-14-canonical-gmail.sql` (already applied; it targets `group_members`, renamed by `2026-10-22-invite-lists.sql`): neither can run against the current schema, so they have no test.

## API contract and frontend docs

- `docs/frontend/` is the only docs directory under version control; all other `docs/*` are gitignored.
- Controller endpoints must have `@Operation` together with `@CommonApiResponses` or `@PublicApiResponse`. `@Operation(description)` covers, in order, Auth / Precondition / Behavior / Side effects / Errors; request and response each need at least one `@ExampleObject`. OpenAPI text (summary, description, `@Schema`) is always in English. Do not return generic `Map` / `Object`.
- `docs/frontend/openapi.json` and `docs/frontend/error-codes.md` are generated by `OpenApiSnapshotTest` and `ErrorCodesDocTest`; do not edit them by hand. When the contract or `MessageKey` changes, regenerate with `-Dsnapshot.update=true` and commit them together, otherwise the tests fail.
- Record contract changes in `docs/frontend/CHANGELOG.md`; mark breaking changes with 💥.

## Testing

- Controllers use `MockMvcBuilders.standaloneSetup`; Services use `@ExtendWith(MockitoExtension.class)`. Tests that need real SQL extend `PostgresRepositoryTest` (shared Testcontainers Postgres with `schema.sql` loaded); `VoteServiceSlice` provides the beans VoteService needs in a `@DataJpaTest` slice.
- Test method naming: `shouldXxxWhenYyy`.

## Commit

Conventional Commits: `type(scope): description`. Write the description in Traditional Chinese, in the imperative mood; the subject is at most 50 characters with no trailing period. The body explains "why" and the main changes.
