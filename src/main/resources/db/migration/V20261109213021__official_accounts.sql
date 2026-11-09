-- Official accounts: a users row holding the 'official' role (a group such as a building committee or club). It never
-- signs in, so it has neither email nor phone; the identifier check exempts it.
ALTER TABLE vomatt.users DROP CONSTRAINT IF EXISTS chk_users_identifier;
ALTER TABLE vomatt.users ADD CONSTRAINT chk_users_identifier
    CHECK (email IS NOT NULL OR phone_number IS NOT NULL OR 'official' = ANY (roles));

-- The people who manage an official account, each with one role: OWNER, ADMIN, EDITOR or ANALYST
CREATE TABLE IF NOT EXISTS vomatt.official_members (
    official_user_id UUID        NOT NULL REFERENCES vomatt.users (id) ON DELETE CASCADE,
    user_id          UUID        NOT NULL REFERENCES vomatt.users (id) ON DELETE CASCADE,
    role             VARCHAR(20) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (official_user_id, user_id)
);

-- The account switcher: the official accounts a user belongs to
CREATE INDEX IF NOT EXISTS idx_official_members_user ON vomatt.official_members (user_id);

-- The member who actually published a Poll or Comment while acting as an official account; null otherwise
ALTER TABLE vomatt.votes ADD COLUMN IF NOT EXISTS acted_by UUID REFERENCES vomatt.users (id) ON DELETE SET NULL;
ALTER TABLE vomatt.vote_comments ADD COLUMN IF NOT EXISTS acted_by UUID REFERENCES vomatt.users (id) ON DELETE SET NULL;
