-- A user following a tag (PUT /api/tags/{tagId}/follow): Open Public Polls carrying the tag join their Following list.
-- One row per user per tag, so repeat follows add nothing.
CREATE TABLE IF NOT EXISTS vomatt.tag_follows (
    user_id    UUID        NOT NULL REFERENCES vomatt.users (id) ON DELETE CASCADE,
    tag_id     UUID        NOT NULL REFERENCES vomatt.tags (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, tag_id)
);

-- Tag deletion cascades by tag_id
CREATE INDEX IF NOT EXISTS idx_tag_follows_tag ON vomatt.tag_follows (tag_id);
