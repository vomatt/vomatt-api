-- Sign-up invitation emails (Invite List members, community account invitations), one row per sender and address with
-- the latest send: an address hears from the same sender at most once a week, so removing and re-adding it cannot mail
-- someone over and over. Poll invitations keep vote_invitation_emails (once per Poll, ever).
CREATE TABLE IF NOT EXISTS vomatt.invitation_emails (
    sender_id UUID         NOT NULL REFERENCES vomatt.users (id) ON DELETE CASCADE,
    email     VARCHAR(254) NOT NULL,
    sent_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (sender_id, email)
);
