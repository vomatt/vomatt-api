-- Indexes for foreign keys that deleting a Poll or a user has to look up; without them each delete scans the table.

-- Both vote_id indexes on vote_comments are partial, so the cascade from votes and queries that include deleted
-- Comments (notification feed, admin list) could use neither
CREATE INDEX IF NOT EXISTS idx_vote_comments_vote ON vomatt.vote_comments (vote_id, created_at DESC, id DESC);

-- ON DELETE SET NULL references to users
CREATE INDEX IF NOT EXISTS idx_votes_acted_by ON vomatt.votes (acted_by) WHERE acted_by IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_vote_comments_acted_by ON vomatt.vote_comments (acted_by) WHERE acted_by IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_community_invitations_invited_by ON vomatt.community_invitations (invited_by)
    WHERE invited_by IS NOT NULL;
