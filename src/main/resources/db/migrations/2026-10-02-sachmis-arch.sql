-- =============================================================================
-- 舊 schema（帳密登入 / Role 表 / TIMESTAMP）→ 新 schema（db/schema.sql，sachmis 架構）一次性遷移
--
-- 執行：psql "$DB" -v ON_ERROR_STOP=1 -f 2026-10-02-sachmis-arch.sql
-- 單一交易：任何一步失敗即整批回滾。執行前請先備份。
--
-- ⚠ 時區假設：舊欄位為 TIMESTAMP（無時區），由應用以 JVM 本地時間寫入。
--   下方 SET LOCAL TimeZone 決定舊值被解讀成哪個時區；舊服務若非跑在 UTC，請改成實際時區
--   （例：'Asia/Taipei'）。
--
-- 資料處理：
--   * users：roles 由 user_roles/roles 轉成 text[]；email 正規化為小寫（OTP 以小寫比對）；
--     移除密碼與登入鎖定欄位。既有使用者改以同一 email 走 OTP / OAuth 登入即可沿用帳號。
--   * 刪除：roles、user_roles、refresh_tokens、password_reset_tokens、user_activities
--     （refresh token 改存 Redis → 所有使用者需重新登入）。
--   * user_rankings / user_ratings：舊程式從未寫入，直接以新定義重建。
-- =============================================================================

BEGIN;

SET LOCAL search_path TO vomatt;
SET LOCAL TimeZone = 'UTC';

-- -----------------------------------------------------------------------------
-- 0. 前置檢查：email 轉小寫後不可撞號
-- -----------------------------------------------------------------------------
DO $$
DECLARE dup text;
BEGIN
    SELECT string_agg(e, ', ') INTO dup FROM (
        SELECT lower(trim(email)) AS e FROM vomatt.users
        WHERE email IS NOT NULL GROUP BY lower(trim(email)) HAVING count(*) > 1
    ) d;
    IF dup IS NOT NULL THEN
        RAISE EXCEPTION 'email 轉小寫後重複，請先人工合併帳號：%', dup;
    END IF;
END $$;

-- -----------------------------------------------------------------------------
-- 1. users：角色改 text[]、新增 OAuth 欄位、移除密碼相關欄位
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
-- 2. 移除不再使用的表
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS user_roles, roles, refresh_tokens, password_reset_tokens, user_activities,
    user_rankings, user_ratings;

-- -----------------------------------------------------------------------------
-- 3. lookup（舊環境可能未建）
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
-- 4. audit_logs：user_id 改字串且不設 FK（使用者刪除後稽核仍保留）
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
-- 5. 所有 TIMESTAMP → TIMESTAMPTZ（依上方 TimeZone 解讀舊值），預設值統一 now()
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
-- 6. 重建 RANKING / RATING 表（Phase 2 預留）
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
-- 7. 索引：移除舊 idx_*（多數與 UNIQUE 重複），改建 schema.sql 的索引組合
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
