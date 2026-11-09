-- A user's like (heart) on a Poll they can see (PUT /api/votes/{voteId}/like). One row per user per Poll; id is the
-- POLL_LIKE notification's source, new on every like, so an unlike followed by a like is a new, unread notification.
CREATE TABLE IF NOT EXISTS vomatt.vote_likes (
    user_id    UUID        NOT NULL REFERENCES vomatt.users (id) ON DELETE CASCADE,
    vote_id    UUID        NOT NULL REFERENCES vomatt.votes (id) ON DELETE CASCADE,
    id         UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, vote_id),
    CONSTRAINT uk_vote_likes_id UNIQUE (id)
);

-- Like count per Poll; written only by atomic SQL increments in VoteRepository, like turnout and share_count
ALTER TABLE vomatt.votes ADD COLUMN IF NOT EXISTS like_count INTEGER NOT NULL DEFAULT 0;

-- POLL_LIKE notification branch and Poll deletion cascade by vote_id
CREATE INDEX IF NOT EXISTS idx_vote_likes_vote ON vomatt.vote_likes (vote_id);
