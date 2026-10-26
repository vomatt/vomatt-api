# Glossary

Domain terms follow [CONTEXT.md](../../CONTEXT.md). API paths and DTOs still use the old names `vote` / `Vote*`; in frontend screens and code, use the terms in the left column and avoid mixing them.

| Domain term | Meaning | API path | DTO |
|----------|------|----------|-----|
| **Poll** | A question with options; always has an end time | `POST/PUT/GET /api/votes`, `GET /api/votes/{voteId}`, `GET /api/votes/my` | `CreateVoteRequest`, `VoteResponse` |
| **Scheduled / Open / Ended** | Poll status (not started / in progress / ended) | Derived from `VoteResponse` fields; lists filter by the status parameter | `VoteResponse` |
| **Close** (Cancelled) | The owner ends an Open Poll early, or cancels a Scheduled Poll | `PUT /api/votes/{voteId}/deactivate` | `SimpleResultResponse` |
| **Ballot / Selection** | A user's single choice in a Poll; voting again replaces it | `POST /api/votes/{voteId}/vote` | `VoteRequest`, `VoteResponse` |
| **Retraction** | Withdrawing the whole Ballot | `DELETE /api/votes/{voteId}/vote` (also `DELETE .../vote/{optionId}`) | `VoteResponse` |
| **Participant** | A user holding a Ballot in that Poll | `GET /api/votes/{voteId}/voters`, `GET /api/votes/{voteId}/my-vote-status` | `VoterResponse`, `UserVoteStatusResponse` |
| **Sealed** | Results of an Open Poll are sealed; per-option counts and Support are hidden until it ends | `GET /api/votes/{voteId}/results` | `VoteResultResponse` |
| **Turnout** | Number of Participants; visible at any time | Returned with the Poll | `VoteResponse` |
| **Support** | Share of Participants who chose an option; visible only after the Poll ends | `GET /api/votes/{voteId}/results` | `VoteResultResponse` |
| **Voter Visibility** | Who can see each person's choice after the Poll ends (nobody / owner only / any signed-in user) | Set when creating the Poll; read via `/voters` | `CreateVoteRequest`, `VoterResponse` |
| **Anonymous Ballot** | A Ballot left out of the voter list whatever the Voter Visibility; it still counts in Turnout. The account sets the default | `POST /api/votes/{voteId}/vote` (`anonymous`), `PATCH /api/users/me` (`anonymousBallotByDefault`) | `VoteRequest`, `VoteResponse` (`myBallotAnonymous`), `UpdateProfileRequest` |
| **Notification** | Feed item: a Poll Ended (`RESULT`), an Invitation, a follow, a Comment or reply, a Poll closing soon; derived when read | `GET /api/notifications`, `GET /api/notifications/unread-count`, `POST /api/notifications/{id}/read`, `POST /api/notifications/read-all`, `/api/users/me/notification-preferences` | `NotificationResponse`, `UnreadCountResponse`, `NotificationPreferencesResponse` |
| **Comment / Reply** | Comments under a Poll and their one level of replies | `/api/votes/{voteId}/comments`, `.../{commentId}/replies`, `.../{commentId}/like` | `CommentDto`, `CreateCommentRequest`, `UpdateCommentRequest` |
| **Feed** | Public home page: Open Polls, most recently started first | `GET /api/votes` | `VoteResponse` (`CursorResponse`) |
| **Explore / Search** | Browse Polls by tag / search Polls by keyword | `GET /api/votes`, `GET /api/tags` | `VoteResponse`, `TagDto` |

> Swagger is authoritative for paths and DTO fields; if this table disagrees with Swagger, follow Swagger and please report it.
