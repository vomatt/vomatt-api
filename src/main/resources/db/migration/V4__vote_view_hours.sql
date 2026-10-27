-- Views of a Poll (VoteViewRecorder), bucketed by UTC hour: the input for Trending, which needs recent views
-- rather than a lifetime total. A View is a signed-in non-owner opening an Open Public Poll, at most once per user per
-- Poll per 24 hours (deduplicated in Redis). Holds no user ids; RetentionJob drops buckets after 30 days.
CREATE TABLE IF NOT EXISTS vomatt.vote_view_hours (
    vote_id UUID        NOT NULL REFERENCES vomatt.votes (id) ON DELETE CASCADE,
    hour    TIMESTAMPTZ NOT NULL,
    views   INTEGER     NOT NULL DEFAULT 0,
    PRIMARY KEY (vote_id, hour)
);

-- Trending window scans and retention deletes
CREATE INDEX IF NOT EXISTS idx_vote_view_hours_hour ON vomatt.vote_view_hours (hour);
