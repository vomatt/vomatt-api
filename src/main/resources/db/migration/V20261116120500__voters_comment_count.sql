-- Live Comments and Replies per Poll, read by every Poll response instead of a COUNT per page; written only by atomic
-- SQL in VoteCommentRepository, like like_count and share_count
ALTER TABLE vomatt.votes ADD COLUMN IF NOT EXISTS comment_count INTEGER NOT NULL DEFAULT 0;

UPDATE vomatt.votes v
SET comment_count = c.n
FROM (SELECT vote_id, COUNT(*) AS n FROM vomatt.vote_comments WHERE NOT is_deleted GROUP BY vote_id) c
WHERE c.vote_id = v.id AND v.comment_count <> c.n;

-- Whether a Poll holds an Anonymous Ballot, checked on every voter-list page; without it the check reads every Ballot
-- of a Poll that has none
CREATE INDEX IF NOT EXISTS idx_user_votes_vote_anonymous ON vomatt.user_votes (vote_id) WHERE anonymous;
