# Comments and Replies (Comment / Reply)

## Scenario

Users read, post, edit, and delete comments under a Poll, like comments, and join the discussion with single-level replies. Reading works without login. See the **Vote Comment** tag in Swagger for field details; this page only covers flow and state.

> `votes` in the path means Poll (see [glossary.md](../glossary.md)).

## Sequence

```mermaid
sequenceDiagram
    participant U as User
    participant FE as Frontend
    participant API as vomatt-api

    Note over FE,API: Read (no login required)
    FE->>API: GET /api/votes/{voteId}/comments?limit=20
    API-->>FE: Top-level Comments (newest first) + replyCount + first 2 replies + repliesNextCursor + nextCursor
    U->>FE: Show more replies of a comment
    FE->>API: GET /api/votes/{voteId}/comments/{commentId}/replies?cursor={repliesNextCursor}
    API-->>FE: Replies (oldest first) + nextCursor

    Note over FE,API: Write (login required)
    U->>FE: Submit comment / reply
    FE->>API: POST /api/votes/{voteId}/comments {text, parentId?}
    API-->>FE: 201 CommentDto
    U->>FE: Edit / delete / like
    FE->>API: PUT / DELETE /comments/{commentId}, POST / DELETE /comments/{commentId}/like
    API-->>FE: Edit returns CommentDto; others return {success:true}
```

## Call order

| Step | API | Notes |
|------|-----|-------|
| 1 | `GET /api/votes/{voteId}/comments` | Public. Returns top-level Comments only; send the token when logged in to get a correct `likedByCurrentUser` |
| 2 | `GET /api/votes/{voteId}/comments/{commentId}/replies` | Public. Each top-level Comment already carries its first 2 Replies in `replies`; call this only for more, with `cursor` = that Comment's `repliesNextCursor` (null when `replies` holds them all), so no Reply repeats. `commentId` must be a top-level Comment |
| 3 | `POST /api/votes/{voteId}/comments` | Login required. No `parentId` = top-level Comment; with `parentId` = Reply |
| 4 | `PUT /api/votes/{voteId}/comments/{commentId}` | Author only; returns the updated CommentDto |
| 5 | `DELETE /api/votes/{voteId}/comments/{commentId}` | Author only; soft delete |
| 6 | `POST` / `DELETE /api/votes/{voteId}/comments/{commentId}/like` | Like / unlike; both idempotent |
| 7 | `POST /api/votes/{voteId}/comments/{commentId}/report` | Login required. Body `{ reason, note? }`, `reason` one of `SPAM`, `HARASSMENT`, `HATE`, `MISINFORMATION`, `OTHER`, `note` up to 500 characters. Idempotent: a repeat keeps the first report. Not on your own Comments (hide the action there). The author is not told and the Comment stays until a moderator acts |

For pagination rules see [conventions.md](../conventions.md#3-pagination-cursor): pass `nextCursor` back unchanged as `cursor`; `null` means the last page.

## State and UI mapping

| State | Condition | UI | Hidden / changed fields |
|-------|-----------|----|-------------------------|
| Regular Comment | `parentId == null`, `deleted == false` | Show author, text, like count, "N replies" | — |
| Reply | `parentId != null` | Indent one level; no reply count | `replyCount` is always 0, `replies` always `[]` |
| Edited | `edited == true` | Show "Edited"; display time uses `createdAt`, edit time is `updatedAt` | — |
| Deleted, still has replies (placeholder) | `deleted == true` | Show "This comment was deleted", keep the "N replies" entry | `userId`, `author`, `text` are `null` |
| Deleted, no replies | — | Never appears in the list | — |
| Not logged in | No token | Can read; like / reply / post redirect to login | `likedByCurrentUser` is always `false` |

Key points:

- **Single level only**: to reply to a Reply, pass the Reply's id as `parentId`; the server attaches the new Reply to the same top-level Comment (the response's `parentId` is the top-level Comment). To "reply to someone", the frontend can prefix `text` with `@author`.
- **Edit / delete / like do not check Poll state**; comments are still allowed under an Ended Poll.
- **Optimistic update after delete**: if a top-level Comment has `replyCount > 0`, show the placeholder; otherwise remove it. Deleting a Reply removes it from the list and decrements the top-level Comment's `replyCount` by 1.
- **Likes** can be updated optimistically; repeated like or unlike both succeed, so there are no conflicts to handle.
- Editing / deleting / liking an already-deleted comment returns `comment.not_found`.

## Common errors

| errorCode | HTTP | When | Suggested UI |
|-----------|------|------|--------------|
| `common.validation_failed` | 400 | `text` is blank or over 2000 characters | Highlight the input, keep the draft |
| `comment.report.self` | 400 | Reporting your own Comment | Hide the report action on your own Comments |
| `comment.parent.invalid` | 400 | `parentId` does not exist, is deleted, or belongs to another Poll | Show "The comment you are replying to no longer exists" and refresh the list |
| `common.cursor_invalid` | 400 | `cursor` was not returned by the server | Drop the cursor and reload from the first page |
| `auth.token.expired` | 401 | Access token expired | Follow the refresh flow in [conventions.md](../conventions.md#2-http-status-and-frontend-handling), then retry |
| `common.unauthorized` | 401 | Write endpoint called without a token | Redirect to login |
| `comment.forbidden` | 403 | Editing / deleting someone else's comment | Hide edit / delete buttons on others' comments; show a toast if received |
| `vote.not_found` | 404 | Poll does not exist | Show Poll not found |
| `comment.not_found` | 404 | Comment does not exist / is deleted, or the replies `commentId` is not a top-level Comment of this Poll | Remove the item from the list and refresh |
| `user.not_found` | 404 | The logged-in account no longer exists | Treat as logged out and clear login state |
| `common.rate_limited` | 429 | Rate limit hit | Count down using `Retry-After` |
