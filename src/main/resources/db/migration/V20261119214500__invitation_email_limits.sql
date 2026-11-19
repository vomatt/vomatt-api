-- Sign-up invitation emails (Invite List members, community account invitations) are now limited per sender and
-- address across every Invite List and community account: a sender who mails the same address
-- invitation_email_burst_limit times within one invitation_email_burst_window_hours window is locked out of mailing it
-- for invitation_email_lock_days, until then or until an admin unlocks the pair. One row per sender and address holds
-- the current window; the per-scope 3-day resend window (invitation_emails) is gone.
CREATE TABLE IF NOT EXISTS vomatt.invitation_email_limits (
    sender_id    UUID         NOT NULL REFERENCES vomatt.users (id) ON DELETE CASCADE,
    email        VARCHAR(254) NOT NULL,
    window_start TIMESTAMPTZ  NOT NULL,
    sent_count   INT          NOT NULL,
    locked_until TIMESTAMPTZ,
    PRIMARY KEY (sender_id, email)
);
-- The admin list of current locks
CREATE INDEX IF NOT EXISTS invitation_email_limits_locked_idx ON vomatt.invitation_email_limits (locked_until)
    WHERE locked_until IS NOT NULL;

DROP TABLE IF EXISTS vomatt.invitation_emails;
