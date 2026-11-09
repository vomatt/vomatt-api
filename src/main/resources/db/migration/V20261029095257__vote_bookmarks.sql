-- A user's bookmark on a Poll they can reach (PUT /api/votes/{voteId}/bookmark). One row per user per Poll; the
-- bookmarked list pages by (created_at, id) newest first, and an Ended bookmarked Poll gives its RESULT notification.
CREATE TABLE IF NOT EXISTS vomatt.vote_bookmarks (
    id         UUID        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES vomatt.users (id) ON DELETE CASCADE,
    vote_id    UUID        NOT NULL REFERENCES vomatt.votes (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_vote_bookmarks_user_vote UNIQUE (user_id, vote_id)
);

-- Bookmarked list keyset; the unique (user_id, vote_id) serves the notification lookup and the per-page flag
CREATE INDEX IF NOT EXISTS idx_vote_bookmarks_user_created ON vomatt.vote_bookmarks (user_id, created_at DESC, id DESC);
-- Poll deletion cascades by vote_id
CREATE INDEX IF NOT EXISTS idx_vote_bookmarks_vote ON vomatt.vote_bookmarks (vote_id);
