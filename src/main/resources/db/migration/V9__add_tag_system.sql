-- V9__add_tag_system.sql
-- 標籤系統：tags 表 + vote_tags 關聯表

CREATE TABLE IF NOT EXISTS tags (
    id UUID PRIMARY KEY,
    name VARCHAR(30) NOT NULL,
    slug VARCHAR(50) NOT NULL,
    description VARCHAR(200),
    display_order INT NOT NULL DEFAULT 0,
    usage_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_tags_name UNIQUE (name),
    CONSTRAINT uk_tags_slug UNIQUE (slug)
);

CREATE INDEX idx_tags_usage_count ON tags (usage_count DESC);

CREATE TABLE IF NOT EXISTS vote_tags (
    vote_id UUID NOT NULL,
    tag_id UUID NOT NULL,
    PRIMARY KEY (vote_id, tag_id),
    CONSTRAINT fk_vote_tags_vote FOREIGN KEY (vote_id) REFERENCES votes (id) ON DELETE CASCADE,
    CONSTRAINT fk_vote_tags_tag FOREIGN KEY (tag_id) REFERENCES tags (id) ON DELETE RESTRICT
);

CREATE INDEX idx_vote_tags_tag_id ON vote_tags (tag_id);
