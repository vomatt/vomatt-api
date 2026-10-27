-- A user sharing a Poll through its share button (POST /api/votes/{voteId}/share). One row per user per Poll, so a
-- Poll's share count is the number of distinct users who shared it and repeat taps add nothing; channel is the first one.
CREATE TABLE IF NOT EXISTS vomatt.vote_shares (
    vote_id    UUID        NOT NULL REFERENCES vomatt.votes (id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES vomatt.users (id) ON DELETE CASCADE,
    channel    VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (vote_id, user_id)
);

-- Share count (distinct sharers) per Poll; written only by atomic SQL increments in VoteRepository, like turnout
ALTER TABLE vomatt.votes ADD COLUMN IF NOT EXISTS share_count INTEGER NOT NULL DEFAULT 0;

-- Account deletion cascades by user_id
CREATE INDEX IF NOT EXISTS idx_vote_shares_user ON vomatt.vote_shares (user_id);
