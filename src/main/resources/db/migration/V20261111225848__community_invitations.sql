-- Invitations to join a community account, by email, with the role the invitee gets. An address with an account sees
-- it as a COMMUNITY_INVITATION notification (matched by users.email) and accepts or declines; an address with no account
-- is mailed, and signing up with it joins. Accepting, declining or joining deletes the row; an expired one stays until
-- the address is invited again, which replaces it.
CREATE TABLE IF NOT EXISTS vomatt.community_invitations (
    id                UUID         PRIMARY KEY,
    community_user_id UUID         NOT NULL REFERENCES vomatt.users (id) ON DELETE CASCADE,
    email             VARCHAR(254) NOT NULL,
    role              VARCHAR(20)  NOT NULL,
    invited_by        UUID         REFERENCES vomatt.users (id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ  NOT NULL,
    UNIQUE (community_user_id, email)
);

-- The invitee's side: their notification, accept / decline, and joining at sign-up
CREATE INDEX IF NOT EXISTS idx_community_invitations_email ON vomatt.community_invitations (email);
