# Notifications

## Scenario
The user opens the notifications page to see what happened: Polls that Ended, Invitations, new followers, Comments on their Polls and replies to their Comments, and (if switched on) Polls about to close that they have not voted in. The nav bar shows an unread badge. See Swagger (tag `Notification`) for field details.

## Sequence
```mermaid
sequenceDiagram
    participant FE as Frontend
    participant API as vomatt-api
    loop About every 60 s in foreground / on return to foreground
        FE->>API: GET /api/notifications/unread-count
        API-->>FE: {"count": 3}
    end
    FE->>API: GET /api/notifications?limit=20
    API-->>FE: {"items": [...], "nextCursor": "..."}
    FE->>API: POST /api/notifications/{id}/read
    API-->>FE: 200 (repeat calls also 200)
    FE->>API: POST /api/notifications/read-all
    API-->>FE: 200
```

## Call order
| Step | API | Notes |
|------|-----|-------|
| 1 | `GET /api/notifications/unread-count` | Red dot / badge count, at most 100 ("99+") |
| 2 | `GET /api/notifications` | The feed, newest first; pass `nextCursor` back as `cursor` for more |
| 3 | `POST /api/notifications/{id}/read` | When the user opens an item; idempotent. `id` is the item's `id` |
| 4 | `POST /api/notifications/read-all` | "Mark all read" |
| - | `GET` / `PATCH /api/users/me/notification-preferences` | Settings page switches |

## Kinds and UI mapping
Notifications are **not stored**; they are derived from Polls, Invitations, follows and Comments when read (ADR 0003, ADR 0005). Only read state is stored.

| `kind` | When | `actor` | `poll` | `comment` | Switch |
|--------|------|---------|--------|-----------|--------|
| `RESULT` | A Poll you own, hold a Ballot in or are an Invitee of has Ended (Deadline passed, Closed, or every Invitee voted). Not after a Retraction unless you are an Invitee | null | yes | null | `resultsIn` |
| `CANCELLED` | An Invite-only Poll you are invited to was Cancelled before it opened (only Polls Cancelled before 2026-10-14) | owner | yes | null | `resultsIn` |
| `INVITATION` | You are an Invitee of a Scheduled or Open Invite-only Poll. Replaced by `RESULT` once the Poll Ends. Removed and invited again: a new, unread one | owner | yes | null | `invitations` |
| `FOLLOWED` | Someone followed you. One item per follower: group them ("@bkim and 3 others followed you") | follower | null | null | none |
| `COMMENT` | Someone else commented on or replied under your Poll, or replied to your Comment | author | yes | `{ id, parentId, excerpt }` | `comments` |
| `CLOSING_SOON` | An Open Poll ends within 24 hours, you hold no Ballot, and you are its Invitee or it is a Public Poll of someone you follow. Gone once you vote or it Ends | owner | yes | null | `closingSoon` (off by default) |

- `createdAt` is when it happened: the Poll's end, the Invitation, the follow, the Comment, or 24 hours before the Poll's end for `CLOSING_SOON` (its start if later). Group by day with it.
- `poll` is `{ id, title, startTime, endTime, status, turnout, myOptionId, winningOptionIds, winningOptionLabels, tie }`. It never carries per-option counts, so a Sealed Poll shows only Turnout ("342 voted so far"). `myOptionId` is your Ballot's option (null without one, or once your Anonymous Ballot of an Ended Poll is sealed). The outcome fields are null until the Poll has Ended; then `winningOptionIds` / `winningOptionLabels` name the option(s) with the most Ballots in display order (`[]` when none named an option, as in a Cancelled Poll) and `tie` says whether there are several. Your Ballot won when `myOptionId` is in `winningOptionIds` (tied when `tie` too). Link to `/poll/{poll.id}`; for `RESULT`, the results page.
- `actor` carries `isFollowing` (you follow them) and `followsYou` (they follow you), so a `FOLLOWED` row can offer "Follow back" without fetching the profile.
- `comment.parentId` is the Comment a Reply is under, null for a top-level Comment. A link to a Reply needs both ids.
- Nothing from a suspended user, a user you blocked or a user who blocked you is listed or counted, and nothing about a Poll whose owner blocked you (`RESULT` and `COMMENT` included). A `COMMENT` for a Reply in a thread started by a user you blocked is left out too, as in the Comment lists.
- An item can vanish (Comment deleted, follow undone, Poll Deleted, block) or appear behind the cursor (you follow someone); de-duplicate by `id`.

## Read state
- `read` is per item. `POST /api/notifications/{id}/read` reads one; `POST /api/notifications/read-all` reads everything whose `createdAt` is at or before now, switched-off kinds included. Later items arrive unread.
- **Unread count** = feed items with `read` false, of the kinds switched on. It updates on the next call; no event is needed (a Poll Ending raises it by itself). A kind switched back on returns with its items' read state as it was.
- **No push or email**: the frontend must poll. Recommended: check once after login and whenever the app / tab returns to the foreground, every 60 seconds while in the foreground, and pause in background tabs. Poll only `unread-count`; fetch the feed when the user opens the page.
- Deprecated: `POST /api/notifications/{voteId}/read` with a Poll id reads that Poll's Invitation while it is not Ended, its `RESULT` after. `GET /api/votes/my` items still carry `unread` for the same two. Both keep working until the web moves to the feed.

## Common errors
| errorCode | When | Suggested UI |
|-----------|------|--------------|
| `notification.not_found` | The id is unknown or malformed, or the item is gone (see above) | Ignore and refresh the list |
| `vote.not_found` | Deprecated Poll id only: the Poll does not exist or is Deleted | Ignore and refresh the list |
| `common.cursor_invalid` | `cursor` came from another list | Reload the first page |
| `auth.token.expired` / `auth.token.invalid` / `common.unauthorized` | See [conventions](../conventions.md) | Refresh or redirect to login per convention |
