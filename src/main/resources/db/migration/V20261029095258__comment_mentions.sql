-- Users a Comment or Reply @mentions, resolved from its text when it is written or edited. The MENTION notification
-- branch reads it (ADR 0005); the GIN index serves its "mentions this user" (@>) lookup
ALTER TABLE vomatt.vote_comments ADD COLUMN mentioned_user_ids UUID[] NOT NULL DEFAULT '{}';
CREATE INDEX idx_vote_comments_mentioned_user_ids ON vomatt.vote_comments USING GIN (mentioned_user_ids);
