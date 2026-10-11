# Profile and Public Page

## Scenario
Users view / edit their own profile and choose which fields are public; others view the public page by username, see Polls that user created, or search users. Users can also delete their own account. See Swagger (tag `User`) for field details.

## Sequence
```mermaid
sequenceDiagram
    participant FE as Frontend
    participant API as vomatt-api
    FE->>API: GET /api/users/me
    API-->>FE: MyProfileResponse (all fields + visibilitySettings)
    FE->>API: PATCH /api/users/me/visibility {"visibility":{"email":true}}
    API-->>FE: Full visibility map
    FE->>API: PATCH /api/users/me {"displayName":"…","bio":"…"}
    API-->>FE: UserProfileResponse (public view)
    Note over FE,API: Other users
    FE->>API: GET /api/users/{username} (no login)
    API-->>FE: UserProfileResponse (hidden fields are null)
    FE->>API: GET /api/users/{username}/votes (no login)
    API-->>FE: CursorResponse<VoteResponse> (Open, Ended)
    FE->>API: DELETE /api/users/{userId}
    API-->>FE: 200, all refresh tokens revoked → frontend clears login state
```

## Call order
| Step | API | Notes |
|------|-----|-------|
| 1 | `GET /api/users/me` | Your own profile; **not affected by visibility**, all fields returned. `visibilitySettings` is "what others see" |
| 2 | `PATCH /api/users/me/visibility` | Send only the fields to change; unsent fields stay as-is, unknown keys are ignored; returns the full settings |
| 3 | `PATCH /api/users/me` | Only changes `displayName` / `bio`; omitted or `null` = unchanged (send an empty string to clear). Returns the public view |
| 4 | `GET /api/users/{username}` | Public, no login; same response whether logged in or not |
| 4a | `GET /api/users/{username}/votes` | Public, no login; **opened** Polls created by that user (Open, Ended), most recently opened first, cursor-paginated. Excludes Scheduled ones and ones cancelled before opening. When logged in, includes your own voting state on each Poll |
| 5 | `GET /api/users/search?username=` | Login required; partial, case-insensitive match, ordered by username, cursor-paginated (see [conventions](../conventions.md)); suspended users are not listed |
| 6 | `DELETE /api/users/{userId}` | Self or admin; on success all refresh tokens of that account become invalid |

## State and UI mapping
Fields controlled by visibility settings: `email`, `firstName`, `lastName`, `location`, `points`, `membershipLevel` (**hidden** by default), and `displayName`, `bio` (**public** by default).

| Field (public page `UserProfileResponse`) | When `null` | UI |
|------|------|-----|
| `email`, `firstName`, `lastName`, `location`, `points`, `membershipLevel` | Owner has not made it public (default), or it has no value | Hide the whole row; do not infer anything beyond "hidden" |
| `displayName`, `bio` | Owner turned off public visibility, or the user never set it (public by default) | When `displayName` is `null`, use `username` as the display name |
| `username`, `joinedAt`, `totalPolls`, `totalVotes` | Always present | — |

Notes:
- Suspended users (`active=false`) do not exist publicly: both the public page and Poll list return 404 `user.not_found`, and search does not list them.
- `totalPolls` only counts **opened** Polls (Open, Ended), matching the count of `GET /api/users/{username}/votes`. `totalPolls` in `GET /api/users/me` is computed the same way.
- `UserDto` from `GET /api/users/search` does **not apply** visibility settings (includes `email`, `phoneNumber`, `active`, `lastLoginAt`). The frontend should only display necessary fields such as `username` and must not treat it as a public data source; call `GET /api/users/{username}` for public data.
- `totalVotes` is "the number of Polls where you hold a Ballot", not the number of votes cast.
- Account deletion is irreversible; on success the frontend should clear local tokens and return to the login page.

## Common errors
| errorCode | When | Suggested UI |
|-----------|------|--------------|
| `user.not_found` | username / userId does not exist, the user is suspended (public page, Poll list), or your own account no longer exists | Show user not found; for `/me`, treat as logged out |
| `user.delete.forbidden` | Deleting another user's account without admin | Show no permission (do not log out) |
| `common.missing_param` | Search `username` is missing or blank | Do not send the request when the search box is empty |
| `common.cursor_invalid` | The Poll list `cursor` was not issued by this API | Reload from the first page |
| `common.validation_failed` | `displayName` over 100 characters, `bio` over 500 characters, or `visibility` missing | Show the length limit next to the field |
| `common.bad_request` | `userId` is not a valid id (only reachable by admin) | Generic error message |
| `auth.token.expired` / `auth.token.invalid` / `common.unauthorized` | See [conventions](../conventions.md) | Refresh or redirect to login per convention |
