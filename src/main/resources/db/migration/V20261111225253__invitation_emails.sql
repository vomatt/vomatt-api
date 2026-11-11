-- Sign-up invitation emails (Invite List members, community account invitations), one row per sender, scope (the
-- Invite List or community account it is about) and address with the latest send: an address hears from the same
-- sender about the same scope at most once a week, so removing and re-adding it cannot mail someone over and over,
-- while an invitation about another scope still goes out. Poll invitations keep vote_invitation_emails (once per Poll, ever).
CREATE TABLE IF NOT EXISTS vomatt.invitation_emails (
    sender_id UUID         NOT NULL REFERENCES vomatt.users (id) ON DELETE CASCADE,
    scope_id  UUID         NOT NULL,
    email     VARCHAR(254) NOT NULL,
    sent_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (sender_id, scope_id, email)
);
