# API Changelog

Contract changes that affect the frontend. 💥 BREAKING means frontend code must change. See [openapi.json](openapi.json) for the contract snapshot and [error-codes.md](error-codes.md) for error codes.

## 2026-10-14

- Poll response gains `deadline`: the end time the owner set (`endTime` on create / edit sets both). Ending early moves only `endTime`, so `endTime` < `deadline` means the Poll ended early. Polls that ended early before this change have `deadline` equal to `endTime`.
- 💥 BREAKING `PUT /api/votes/{voteId}/deactivate` (Close) on an Open Public Poll returns 400 `vote.close.public_not_allowed`; a Public Poll runs to its Deadline (ADR 0004). Only Open Unlisted (and, later, Invite-only) Polls can be Closed.
- 💥 BREAKING Close on a Scheduled Poll returns 400 `vote.close.scheduled` instead of cancelling it; use `DELETE /api/votes/{voteId}`. Cancelled Polls are no longer produced; existing ones stay Ended. Close on an Ended Poll is still a no-op success.
- Polls gain an Audience: `POST /api/votes` and `PUT /api/votes/{voteId}` accept `audience` (`PUBLIC` default, or `UNLISTED`); omitting it keeps `PUBLIC` on create and the current value on edit. Audience can change only while the Poll is Scheduled (edit of an Open or Ended Poll is already 400 `vote.not_editable`). `INVITE_ONLY` is reserved and not supported yet: 400 `vote.audience.unsupported`.
- Poll response gains `audience` (`PUBLIC` / `UNLISTED` / `INVITE_ONLY`). Existing Polls are `PUBLIC`.
- An `UNLISTED` Poll is reachable only through its link: anyone can read it, any signed-in user can cast a Ballot and Comment, exactly like a Public Poll. It never appears in Feed / Explore / Search, the owner's profile list or `totalPolls`, and its tags do not count in popular tag `usageCount`. It is listed in My Polls for its owner and its Participants.
- 💥 BREAKING `GET /api/users/search` items (`UserDto`) now carry only public profile fields: `id`, `username`, `displayName`, `avatarUrl`. `email`, `phoneNumber`, `firstName`, `lastName`, `location`, `points`, `membershipLevel`, `active`, `createdAt` and `lastLoginAt` are removed. `displayName` is `null` when the owner hid it on the public profile. Ordering, cursor paging and the blank-`username` error are unchanged.
- 💥 BREAKING A pagination cursor is accepted only by the list and ordering that issued it. Sending it to another list or ordering (e.g. a Feed `sort=newest` cursor to `sort=closing`, a My Polls `status=open` cursor to `status=ended`, a comments cursor to replies) returns 400 `common.cursor_invalid`; restart from the first page. Changing `tag` or `q` with the same ordering is still allowed. Cursors issued before this change are no longer valid. Response shapes are unchanged.
- Casting, Retracting and removing a Selection racing a Close now either count before the Poll Ended or fail with 400 `vote.ended`; Ballots and counts never change after Close returns. Editing a Scheduled Poll that opened and received a Ballot meanwhile fails with 400 `vote.not_editable`; a Ballot on an option replaced by an in-flight edit fails with 404 `vote.option.not_found`.
- Added `DELETE /api/votes/{voteId}` (owner only): Deletes a Poll while it is Scheduled, or within the delete window after posting (default 20 minutes, configurable) even if it holds Ballots. Returns 400 `vote.delete.window_passed` outside that window, 403 `vote.forbidden` for a non-owner. Nobody is notified.
- A Deleted Poll is gone for everyone, owner included: every single-Poll endpoint (Poll, Ballot, results, voters, my-vote-status, Comments, Replies, Comment edit / delete / like, notification read, Close, edit) returns 404 `vote.not_found`, and it leaves Feed / Explore / Search, profile lists and `totalPolls`, My Polls, Ended Notifications (unread count) and popular tag counts.
- Poll response gains `deletable` (boolean): whether the owner can still Delete the Poll. Set on every Poll response to the owner (get, lists, create, edit, Ballot, Close); null for other callers.
- Profile `totalVotes` no longer counts Ballots in Deleted Polls.
- 💥 BREAKING `GET /api/votes/{voteId}/my-vote-status` (deprecated) returns 404 `vote.not_found` for an unknown or Deleted Poll instead of `hasVoted: false`.
- 💥 BREAKING `POST /api/notifications/{voteId}/read` returns 404 `vote.not_found` (instead of `notification.not_found`) when the Poll does not exist or is Deleted.

## 2026-10-13

- `PATCH /api/users/me` accepts `username` (3-50 characters of `[a-z0-9_]`, not `search`); returns 409 `user.username.taken` when another account uses it. The old `/api/users/{username}` path returns 404 after the change.

## 2026-10-12

- Deprecated (still working, to be removed in a later 💥 release): `DELETE /api/votes/{voteId}/vote/{optionId}` → use `DELETE /api/votes/{voteId}/vote`; `GET /api/votes/{voteId}/my-vote-status` → use `myOptionId` in `GET /api/votes/{voteId}`.
- Write requests under `/api/votes/**` and `/api/users/**` (POST / PUT / PATCH / DELETE) are now rate limited per IP, with a counter separate from `/api/auth/**`, and may return 429 `common.rate_limited` with `Retry-After`; GET requests are not limited.

## 2026-10-09

- 💥 BREAKING 401 / 403 responses (filter layer) now carry an `ApiResponse` body with `errorCode` (previously the body was empty): `common.unauthorized`, `auth.token.expired`, `auth.token.invalid`, `common.forbidden`. The frontend uses `errorCode` to decide whether to refresh (see [auth-flow.md](auth-flow.md)).
- 💥 BREAKING 429 response body is now an `ApiResponse` (`errorCode` = `common.rate_limited`) and includes a `Retry-After` header (seconds), readable cross-origin. If the rate limiter itself fails, auth endpoints return 429 without `Retry-After`.
- Docs no longer list `zh-CN`: `Accept-Language` supports `zh-TW` (default) and `en`.
- 💥 BREAKING Swagger UI and `/v3/api-docs` are disabled by default; set `SWAGGER_ENABLED=true` to enable them (never enabled in production). The frontend should use [openapi.json](openapi.json) instead.
- The contract source is now `docs/frontend/openapi.json` (generated by tests); the old root-level `openapi.yaml` (from the Next.js era) is archived in `docs/archive/` and no longer maintained.
- All OpenAPI `summary` / `description` / `@Schema` text is now in English, and every endpoint documents auth, preconditions, side effects, errors, and examples.
- OpenAPI: every 2xx response now has a response schema; endpoints returning 201 (create Poll, create comment / reply, admin create lookup item) no longer also list a 200.
- OpenAPI version changed to 3.0 (`nullable: true` is emitted correctly; 3.1 drops it), so nullable fields in generated TS types are `T | null`.
- Comments and replies are readable without login: `GET /api/votes/{voteId}/comments` and `GET /api/votes/{voteId}/comments/{commentId}/replies` are public endpoints (posting and liking still require login).
- Public Poll paths only match UUIDs: `GET /api/votes/{voteId}` is public, while named sub-paths such as `GET /api/votes/my` still require login.
- Profile visibility defaults: when unset, `visibilitySettings` reports `displayName` and `bio` as public.
- Added `GET /api/users/{username}/votes` (public): Poll list for a user's public page, containing only opened Polls (Open, Ended), cursor-paginated.
- 💥 BREAKING The public profile now applies the `displayName` and `bio` visibility settings: returns `null` when hidden (previously always returned).
- 💥 BREAKING The public page and Poll list of a suspended user return 404 `user.not_found`.
- `totalPolls` (public page and `/api/users/me`) only counts opened Polls, excluding Scheduled ones and ones cancelled before opening.
- `/api/users/search/**` sub-paths also require login.
- Fix: new comments are no longer wrongly marked as `edited`; the `edited` of a deleted-comment placeholder is always `false`.
- Fix: editing a Scheduled Poll without `startTime` keeps the original start time.
