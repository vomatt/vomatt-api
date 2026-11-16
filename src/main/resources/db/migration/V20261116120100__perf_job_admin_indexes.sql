-- Indexes for the daily RetentionJob and the admin back office, which otherwise scan whole tables.

-- RetentionJob sealing: only unsealed Anonymous Ballots (Polls not yet Ended, or Pro polls waiting for their report);
-- the predicate is the seal query's own, so the planner can match it
CREATE INDEX IF NOT EXISTS idx_user_votes_unsealed_anonymous ON vomatt.user_votes (vote_id)
    WHERE anonymous AND option_id IS NOT NULL;

-- RetentionJob report snapshots: findEndedProPollsWithoutReport looks only at live, non-Cancelled Pro polls
CREATE INDEX IF NOT EXISTS idx_votes_pro_end ON vomatt.votes (end_time)
    WHERE pro AND deleted_at IS NULL AND end_time > start_time;

-- Admin lists, newest first; the users one also serves the dashboard's "joined this week" count
CREATE INDEX IF NOT EXISTS idx_users_created_id ON vomatt.users (created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_votes_created_id ON vomatt.votes (created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_audit_logs_action_created ON vomatt.audit_logs (action, created_at DESC, id DESC);

-- Admin dashboard: Ballots cast in the last 24 hours
CREATE INDEX IF NOT EXISTS idx_user_votes_created ON vomatt.user_votes (created_at);
