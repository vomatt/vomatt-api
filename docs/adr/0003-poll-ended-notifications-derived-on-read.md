# "Poll ended" notifications are derived on read, not stored

The API runs on Vercel Fluid compute, which scales to zero, so `@Scheduled` jobs cannot be trusted to fire at a Poll's end time, and Vercel Cron on the Hobby plan runs at most daily with ±59 min drift. Instead of a notifications table filled by a job, a user's notifications are computed when read: every Poll they own or hold a Ballot in whose end time has passed. Only read state is stored, in `(user_id, poll_id, read_at)`. Notifications are exact at close time with no job, and an early Close shows up automatically because it sets the end time.

## Consequences

- No launch cutoff is needed: production held no Polls when this shipped (checked 2026-10-05).
- Email, push or "closing soon" reminders need a real scheduler (Vercel Cron Pro or an external one); they add a sender alongside this read model and don't replace it.
- Users who Retract before the end are not notified, because they are no longer Participants.
- A Poll Closed while still Scheduled (cancelled) never opened and produces no notification.
