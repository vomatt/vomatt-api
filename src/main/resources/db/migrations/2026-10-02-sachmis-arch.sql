-- =============================================================================
-- One-time migration: old schema (password login / Role table / TIMESTAMP) -> new schema (db/schema.sql, sachmis architecture)
--
-- Run: psql "$DB" -v ON_ERROR_STOP=1 -f 2026-10-02-sachmis-arch.sql
-- Single transaction: any failing step rolls back everything. Back up before running.
--
-- WARNING time zone assumption: old columns are TIMESTAMP (no time zone), written by the app in JVM local time.
--   SET LOCAL TimeZone below decides which zone old values are read as; if the old service did not run in UTC, change it to the actual zone
--   (e.g. 'Asia/Taipei').
--
-- Data handling:
--   * users: roles converted from user_roles/roles to text[]; email normalized to lowercase (OTP matches lowercase);
--     password and login-lockout columns removed. Existing users keep their account by logging in via OTP / OAuth with the same email.
--   * Dropped: roles, user_roles, refresh_tokens, password_reset_tokens, user_activities
--     (refresh tokens now live in Redis -> all users must log in again).
--   * user_rankings / user_ratings: never written by the old code, so recreated with the new definition.
-- =============================================================================

BEGIN;

SET LOCAL search_path TO vomatt;
SET LOCAL TimeZone = 'UTC';

-- -----------------------------------------------------------------------------
-- 0. Pre-check: emails must not collide after lowercasing
-- -----------------------------------------------------------------------------
DO $$
DECLARE dup text;
BEGIN
    SELECT string_agg(e, ', ') INTO dup FROM (
        SELECT lower(trim(email)) AS e FROM vomatt.users
        WHERE email IS NOT NULL GROUP BY lower(trim(email)) HAVING count(*) > 1
    ) d;
    IF dup IS NOT NULL THEN
        RAISE EXCEPTION 'duplicate emails after lowercasing, merge accounts manually first: %', dup;
    END IF;
END $$;

-- -----------------------------------------------------------------------------
-- 1. users: roles to text[], add OAuth columns, drop password-related columns
-- -----------------------------------------------------------------------------
ALTER TABLE users
    ADD COLUMN roles       TEXT[] NOT NULL DEFAULT ARRAY['user'],
    ADD COLUMN auth_method VARCHAR(20),
    ADD COLUMN avatar_url  VARCHAR(500);

UPDATE users u
SET roles = r.roles
FROM (
    SELECT ur.user_id,
           array_agg(DISTINCT lower(replace(ro.name, 'ROLE_', '')) ORDER BY lower(replace(ro.name, 'ROLE_', ''))) AS roles
    FROM user_roles ur
    JOIN roles ro ON ro.id = ur.role_id
    GROUP BY ur.user_id
) r
WHERE r.user_id = u.id;

UPDATE users SET email = lower(trim(email)), auth_method = 'email_otp' WHERE email IS NOT NULL;

ALTER TABLE users
    DROP COLUMN credential,
    DROP COLUMN verification_code_expiry,
    DROP COLUMN login_attempts,
    DROP COLUMN locked_until,
    ALTER COLUMN email TYPE VARCHAR(254),
    ALTER COLUMN email DROP NOT NULL,
    ADD CONSTRAINT chk_users_identifier CHECK (email IS NOT NULL OR phone_number IS NOT NULL);

-- -----------------------------------------------------------------------------
-- 2. Drop tables no longer used
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS user_roles, roles, refresh_tokens, password_reset_tokens, user_activities,
    user_rankings, user_ratings;

-- -----------------------------------------------------------------------------
-- 3. lookup (may not exist in old environments)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS lookup (
    id             UUID         PRIMARY KEY,
    lookup_type    VARCHAR(50)  NOT NULL,
    lookup_key     VARCHAR(50)  NOT NULL,
    lookup_value   JSON         NOT NULL,
    seq            INT          NOT NULL DEFAULT 0,
    parent_type    VARCHAR(50),
    parent_key     VARCHAR(50),
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    description    VARCHAR(100),
    frontend_using BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_lookup_type_key UNIQUE (lookup_type, lookup_key)
);

-- -----------------------------------------------------------------------------
-- 4. audit_logs: user_id becomes a string with no FK (audit records survive user deletion)
-- -----------------------------------------------------------------------------
DO $$
DECLARE c record;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
             WHERE conrelid = 'vomatt.audit_logs'::regclass AND contype = 'f' LOOP
        EXECUTE format('ALTER TABLE vomatt.audit_logs DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;

ALTER TABLE audit_logs
    ALTER COLUMN user_id TYPE VARCHAR(36) USING user_id::text,
    ALTER COLUMN username TYPE VARCHAR(254);

-- -----------------------------------------------------------------------------
-- 5. All TIMESTAMP -> TIMESTAMPTZ (old values read using the TimeZone above), defaults unified to now()
-- -----------------------------------------------------------------------------
DO $$
DECLARE c record;
BEGIN
    FOR c IN SELECT table_name, column_name, column_default FROM information_schema.columns
             WHERE table_schema = 'vomatt' AND data_type = 'timestamp without time zone' LOOP
        EXECUTE format('ALTER TABLE vomatt.%I ALTER COLUMN %I TYPE TIMESTAMPTZ', c.table_name, c.column_name);
        IF c.column_default IS NOT NULL THEN
            EXECUTE format('ALTER TABLE vomatt.%I ALTER COLUMN %I SET DEFAULT now()', c.table_name, c.column_name);
        END IF;
    END LOOP;
END $$;

-- -----------------------------------------------------------------------------
-- 6. Recreate RANKING / RATING tables (reserved for Phase 2)
-- -----------------------------------------------------------------------------
CREATE TABLE user_rankings (
    id            UUID        PRIMARY KEY,
    user_id       UUID        NOT NULL REFERENCES users (id)        ON DELETE CASCADE,
    vote_id       UUID        NOT NULL REFERENCES votes (id)        ON DELETE CASCADE,
    option_id     UUID        NOT NULL REFERENCES vote_options (id) ON DELETE CASCADE,
    rank_position INTEGER     NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, vote_id, option_id)
);

CREATE TABLE user_ratings (
    id         UUID        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    vote_id    UUID        NOT NULL REFERENCES votes (id) ON DELETE CASCADE,
    score      INTEGER     NOT NULL CHECK (score BETWEEN 1 AND 5),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, vote_id)
);

-- -----------------------------------------------------------------------------
-- 7. Indexes: drop old idx_* (mostly duplicates of UNIQUE) and create the schema.sql index set
-- -----------------------------------------------------------------------------
DO $$
DECLARE i record;
BEGIN
    FOR i IN SELECT indexname FROM pg_indexes WHERE schemaname = 'vomatt' AND indexname LIKE 'idx\_%' LOOP
        EXECUTE format('DROP INDEX vomatt.%I', i.indexname);
    END LOOP;
END $$;

CREATE INDEX idx_users_active                ON users (active);
CREATE INDEX idx_votes_creator_id            ON votes (creator_id);
CREATE INDEX idx_votes_active_time           ON votes (is_active, start_time, end_time);
CREATE INDEX idx_votes_vote_type             ON votes (vote_type);
CREATE INDEX idx_vote_options_vote_order     ON vote_options (vote_id, display_order);
CREATE INDEX idx_user_votes_vote_option      ON user_votes (vote_id, option_id);
CREATE INDEX idx_user_votes_user_vote        ON user_votes (user_id, vote_id);
CREATE INDEX idx_user_rankings_vote_id       ON user_rankings (vote_id);
CREATE INDEX idx_user_ratings_vote_id        ON user_ratings (vote_id);
CREATE INDEX idx_vote_comments_vote_created  ON vote_comments (vote_id, created_at) WHERE is_deleted = FALSE;
CREATE INDEX idx_vote_comments_user_id       ON vote_comments (user_id);
CREATE INDEX idx_comment_likes_user_id       ON comment_likes (user_id);
CREATE INDEX idx_tags_usage_count            ON tags (usage_count DESC);
CREATE INDEX idx_vote_tags_tag_id            ON vote_tags (tag_id);
CREATE INDEX idx_lookup_type_active          ON lookup (lookup_type, is_active);
CREATE INDEX idx_lookup_parent               ON lookup (parent_type, parent_key);
CREATE INDEX idx_lookup_frontend             ON lookup (frontend_using, is_active);
CREATE INDEX idx_audit_logs_user_id          ON audit_logs (user_id);
CREATE INDEX idx_audit_logs_resource         ON audit_logs (resource_type, resource_id);
CREATE INDEX idx_audit_logs_created_at       ON audit_logs (created_at);

COMMIT;
