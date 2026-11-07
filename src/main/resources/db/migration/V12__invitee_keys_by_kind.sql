-- Invitees and Invite List members are keyed by kind: an ID entry by user, an email entry by address. A user may now hold
-- both on the same Poll / Invite List, so whether an address belongs to an already-invited user never changes what the
-- owner sees. The (…, email) WHERE email IS NOT NULL unique indexes already exist.
-- The old UNIQUE (…, user_id) constraints were declared inline, so their names are Postgres-generated: find them by
-- their columns. Idempotent.
DO $$
DECLARE
    t record;
    c record;
BEGIN
    FOR t IN SELECT * FROM (VALUES ('vomatt.vote_invitees', 'vote_id'), ('vomatt.invite_list_members', 'invite_list_id')) AS v(tbl, parent) LOOP
        FOR c IN
            SELECT con.conname FROM pg_constraint con
            WHERE con.conrelid = t.tbl::regclass AND con.contype = 'u' AND cardinality(con.conkey) = 2
              AND (SELECT array_agg(a.attname::text) FROM pg_attribute a
                   WHERE a.attrelid = con.conrelid AND a.attnum = ANY (con.conkey)) @> ARRAY[t.parent, 'user_id']
        LOOP
            EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', t.tbl, c.conname);
        END LOOP;
    END LOOP;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_vote_invitees_vote_user ON vomatt.vote_invitees (vote_id, user_id) WHERE email IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_invite_list_members_list_user ON vomatt.invite_list_members (invite_list_id, user_id) WHERE email IS NULL;
-- The dropped constraints' indexes also served lookups by parent alone (lists, counts, ON DELETE CASCADE) and by
-- parent + user without an email condition (the first-row-of-user rule); the partial indexes above serve neither
CREATE INDEX IF NOT EXISTS idx_vote_invitees_vote_user ON vomatt.vote_invitees (vote_id, user_id);
CREATE INDEX IF NOT EXISTS idx_invite_list_members_list_user ON vomatt.invite_list_members (invite_list_id, user_id);
