# The notification feed is derived on read, with read state per notification

The feed adds kinds beyond "Poll ended" (ADR 0003): Invitations, follows, Comments and replies, and closing-soon reminders. Each one already exists as a row: an Invitee row, a follow, a Comment, or a Poll's end time crossing now. So the feed stays a read model: one SQL branch per kind, unioned and keyset-paged by (`created_at`, source id), newest first. A notifications table written at event time was rejected: RESULT and CLOSING_SOON fire on time passing, which still needs a scheduler (ADR 0003), and blocks, unfollows, deleted Comments and Deleted Polls must hide items after the fact, so the read-time filters would be needed anyway, next to a second copy of the truth.

A notification is identified by its kind and its source row's id, and read state is stored under that pair. Keying the Invitation by the Invitee row (not the Poll) makes a removed and re-added Invitee, or an email Invitee bound to an account, a new notification with no read state to clear. "Read all" stores one moment per user rather than a row per item.

## Consequences

- The keyset is sound because an item's `created_at` never changes while it is listed and is at most now when it appears. Items can still appear behind a cursor (following someone brings their closing-soon Polls) or vanish (a block); clients de-duplicate by id (ADR 0002).
- Each branch filters by actor (suspended, blocked either way) before its own LIMIT, so a page is never short or out of order.
- RESULT still materializes every Poll the user took part in; a time window or stored counter is the next step if it shows up in slow queries.
- Read rows have no foreign key to their source (it spans tables), so a row whose source is gone lingers, harmless and never matched.
- Email or push remains out of scope: it needs a sender with a scheduler, alongside this read model.
