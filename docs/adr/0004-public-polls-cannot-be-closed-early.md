# Public Polls cannot be Closed early; Delete is limited to 20 minutes

An owner who can end a Public Poll at any moment can watch the Turnout, sense the outcome and stop it while it favours them, which makes public results untrustworthy. So only Unlisted and Invite-only Polls can be Closed; a Public Poll runs to its Deadline. To still let owners fix a mistaken post, any Poll can be Deleted while Scheduled or within 20 minutes of posting, which is too early to read a trend. Ending early never moves the Deadline, so an early end stays visible.

## Consequences

- Breaking change: closing a Public Poll used to be allowed and now fails.
- Cancelled is retired: a Scheduled Poll is Deleted instead of Closed. Polls already Cancelled stay Ended.
- Polls ended early before this change lost their original end time; their Deadline is backfilled with the time they Ended.
