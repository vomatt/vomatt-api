-- Invitations to join a community account, the only way in besides creating one, with the role the invitee gets. Sent
-- to a user picked by id (user_id) or to an email address (email), never both. A user, or the account whose email is the
-- address (including one signed up after the invitation was mailed), sees it as a COMMUNITY_INVITATION notification and
-- accepts or declines. Accepting deletes every row of the community account for the invitee, declining deletes the row;
-- an expired one stays until the user or address is invited again, which replaces it.
CREATE TABLE IF NOT EXISTS vomatt.community_invitations (
    id                UUID         PRIMARY KEY,
    community_user_id UUID         NOT NULL REFERENCES vomatt.users (id) ON DELETE CASCADE,
    user_id           UUID         REFERENCES vomatt.users (id) ON DELETE CASCADE,
    email             VARCHAR(254),
    role              VARCHAR(20)  NOT NULL,
    invited_by        UUID         REFERENCES vomatt.users (id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ  NOT NULL,
    UNIQUE (community_user_id, user_id),
    UNIQUE (community_user_id, email),
    CONSTRAINT chk_community_invitations_user_or_email CHECK (num_nonnulls(user_id, email) = 1)
);

-- The invitee's side: their notification, accept / decline, and joining at sign-up
CREATE INDEX IF NOT EXISTS idx_community_invitations_user ON vomatt.community_invitations (user_id);
CREATE INDEX IF NOT EXISTS idx_community_invitations_email ON vomatt.community_invitations (email);
