-- =============================================================================
-- Vomatt API - Database Schema（單一來源；spring.jpa.hibernate.ddl-auto=none）
-- Database: PostgreSQL / Schema: vomatt
-- ID: UUID v7，由應用層 UUIDv7Generator 產生
-- 時間欄位一律 TIMESTAMPTZ（對應 Java OffsetDateTime）
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS vomatt;

-- =============================================================================
-- USERS：OTP / OAuth 登入，不保存密碼；角色以 text[] 儲存（user / moderator / admin）
-- =============================================================================
CREATE TABLE IF NOT EXISTS vomatt.users (
    id               UUID         PRIMARY KEY,
    username         VARCHAR(50)  NOT NULL UNIQUE,
    email            VARCHAR(254) UNIQUE,
    phone_number     VARCHAR(20)  UNIQUE,
    roles            TEXT[]       NOT NULL DEFAULT ARRAY['user'],
    auth_method      VARCHAR(20),
    avatar_url       VARCHAR(500),
    last_login_at    TIMESTAMPTZ,
    points           INTEGER      NOT NULL DEFAULT 0,
    membership_level VARCHAR(20)  NOT NULL DEFAULT 'BASIC',
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    first_name       VARCHAR(50),
    last_name        VARCHAR(50),
    location         VARCHAR(100),
    display_name     VARCHAR(100),
    bio              TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_users_identifier CHECK (email IS NOT NULL OR phone_number IS NOT NULL)
);

CREATE TABLE IF NOT EXISTS vomatt.user_preferences (
    id               UUID        PRIMARY KEY,
    user_id          UUID        NOT NULL REFERENCES vomatt.users (id) ON DELETE CASCADE,
    preference_key   VARCHAR(50) NOT NULL,
    preference_value TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, preference_key)
);

-- =============================================================================
-- VOTES
-- =============================================================================
CREATE TABLE IF NOT EXISTS vomatt.votes (
    id                     UUID          PRIMARY KEY,
    title                  VARCHAR(200)  NOT NULL,
    description            VARCHAR(1000),
    creator_id             UUID          NOT NULL REFERENCES vomatt.users (id) ON DELETE CASCADE,
    vote_type              VARCHAR(20)   NOT NULL DEFAULT 'STANDARD',
    start_time             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    end_time               TIMESTAMPTZ,
    is_active              BOOLEAN       NOT NULL DEFAULT TRUE,
    allow_multiple_choices BOOLEAN       NOT NULL DEFAULT FALSE,
    is_anonymous           BOOLEAN       NOT NULL DEFAULT FALSE,
    is_public              BOOLEAN       NOT NULL DEFAULT TRUE,
    max_choices            INTEGER       NOT NULL DEFAULT 1,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS vomatt.vote_options (
    id            UUID         PRIMARY KEY,
    text          VARCHAR(200) NOT NULL,
    description   VARCHAR(500),
    vote_id       UUID         NOT NULL REFERENCES vomatt.votes (id) ON DELETE CASCADE,
    display_order INTEGER      NOT NULL DEFAULT 0,
    image_url     VARCHAR(500),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS vomatt.user_votes (
    id         UUID        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES vomatt.users (id)        ON DELETE CASCADE,
    vote_id    UUID        NOT NULL REFERENCES vomatt.votes (id)        ON DELETE CASCADE,
    option_id  UUID        NOT NULL REFERENCES vomatt.vote_options (id) ON DELETE CASCADE,
    ip_address VARCHAR(45),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, vote_id, option_id)
);

-- RANKING / RATING 投票類型用（Phase 2，尚無對應 entity / service）
CREATE TABLE IF NOT EXISTS vomatt.user_rankings (
    id            UUID        PRIMARY KEY,
    user_id       UUID        NOT NULL REFERENCES vomatt.users (id)        ON DELETE CASCADE,
    vote_id       UUID        NOT NULL REFERENCES vomatt.votes (id)        ON DELETE CASCADE,
    option_id     UUID        NOT NULL REFERENCES vomatt.vote_options (id) ON DELETE CASCADE,
    rank_position INTEGER     NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, vote_id, option_id)
);

CREATE TABLE IF NOT EXISTS vomatt.user_ratings (
    id         UUID        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES vomatt.users (id) ON DELETE CASCADE,
    vote_id    UUID        NOT NULL REFERENCES vomatt.votes (id) ON DELETE CASCADE,
    score      INTEGER     NOT NULL CHECK (score BETWEEN 1 AND 5),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, vote_id)
);

-- =============================================================================
-- COMMENTS
-- =============================================================================
CREATE TABLE IF NOT EXISTS vomatt.vote_comments (
    id         UUID        PRIMARY KEY,
    vote_id    UUID        NOT NULL REFERENCES vomatt.votes (id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES vomatt.users (id) ON DELETE CASCADE,
    content    TEXT        NOT NULL,
    is_deleted BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS vomatt.comment_likes (
    id         UUID        PRIMARY KEY,
    comment_id UUID        NOT NULL REFERENCES vomatt.vote_comments (id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES vomatt.users (id)         ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (comment_id, user_id)
);

-- =============================================================================
-- TAGS
-- =============================================================================
CREATE TABLE IF NOT EXISTS vomatt.tags (
    id            UUID         PRIMARY KEY,
    name          VARCHAR(30)  NOT NULL UNIQUE,
    slug          VARCHAR(50)  NOT NULL UNIQUE,
    description   VARCHAR(200),
    display_order INTEGER      NOT NULL DEFAULT 0,
    usage_count   INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS vomatt.vote_tags (
    vote_id UUID NOT NULL REFERENCES vomatt.votes (id) ON DELETE CASCADE,
    tag_id  UUID NOT NULL REFERENCES vomatt.tags (id)  ON DELETE RESTRICT,
    PRIMARY KEY (vote_id, tag_id)
);

-- =============================================================================
-- LOOKUP（字典表）
-- =============================================================================
CREATE TABLE IF NOT EXISTS vomatt.lookup (
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
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_lookup_type_key UNIQUE (lookup_type, lookup_key)
);

-- =============================================================================
-- AUDIT_LOGS：刻意不設 FK，使用者刪除後稽核紀錄仍保留
-- =============================================================================
CREATE TABLE IF NOT EXISTS vomatt.audit_logs (
    id            UUID         PRIMARY KEY,
    user_id       VARCHAR(36),
    username      VARCHAR(254),
    action        VARCHAR(50)  NOT NULL,
    resource_type VARCHAR(50)  NOT NULL,
    resource_id   VARCHAR(36),
    ip_address    VARCHAR(45),
    user_agent    TEXT,
    request_uri   VARCHAR(500),
    http_method   VARCHAR(10),
    success       BOOLEAN      NOT NULL DEFAULT TRUE,
    error_message TEXT,
    details       TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- =============================================================================
-- INDEXES（UNIQUE 欄位已自帶索引，不重複建立）
-- =============================================================================
CREATE INDEX IF NOT EXISTS idx_users_active                ON vomatt.users (active);
CREATE INDEX IF NOT EXISTS idx_votes_creator_id            ON vomatt.votes (creator_id);
CREATE INDEX IF NOT EXISTS idx_votes_active_time           ON vomatt.votes (is_active, start_time, end_time);
CREATE INDEX IF NOT EXISTS idx_votes_vote_type             ON vomatt.votes (vote_type);
CREATE INDEX IF NOT EXISTS idx_vote_options_vote_order     ON vomatt.vote_options (vote_id, display_order);
CREATE INDEX IF NOT EXISTS idx_user_votes_vote_option      ON vomatt.user_votes (vote_id, option_id);
CREATE INDEX IF NOT EXISTS idx_user_votes_user_vote        ON vomatt.user_votes (user_id, vote_id);
CREATE INDEX IF NOT EXISTS idx_user_rankings_vote_id       ON vomatt.user_rankings (vote_id);
CREATE INDEX IF NOT EXISTS idx_user_ratings_vote_id        ON vomatt.user_ratings (vote_id);
CREATE INDEX IF NOT EXISTS idx_vote_comments_vote_created  ON vomatt.vote_comments (vote_id, created_at) WHERE is_deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_vote_comments_user_id       ON vomatt.vote_comments (user_id);
CREATE INDEX IF NOT EXISTS idx_comment_likes_user_id       ON vomatt.comment_likes (user_id);
CREATE INDEX IF NOT EXISTS idx_tags_usage_count            ON vomatt.tags (usage_count DESC);
CREATE INDEX IF NOT EXISTS idx_vote_tags_tag_id            ON vomatt.vote_tags (tag_id);
CREATE INDEX IF NOT EXISTS idx_lookup_type_active          ON vomatt.lookup (lookup_type, is_active);
CREATE INDEX IF NOT EXISTS idx_lookup_parent               ON vomatt.lookup (parent_type, parent_key);
CREATE INDEX IF NOT EXISTS idx_lookup_frontend             ON vomatt.lookup (frontend_using, is_active);
CREATE INDEX IF NOT EXISTS idx_audit_logs_user_id          ON vomatt.audit_logs (user_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_resource         ON vomatt.audit_logs (resource_type, resource_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_created_at       ON vomatt.audit_logs (created_at);
