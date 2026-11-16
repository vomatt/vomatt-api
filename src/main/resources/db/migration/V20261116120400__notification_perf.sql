-- LIKE and POLL_LIKE notification branches: the likes on one Comment or Poll, newest first
CREATE INDEX IF NOT EXISTS idx_comment_likes_comment_created ON vomatt.comment_likes (comment_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_vote_likes_vote_created ON vomatt.vote_likes (vote_id, created_at DESC);
