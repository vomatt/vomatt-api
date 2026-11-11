-- A locked community account: still readable, but members cannot write as it until it is unlocked (its owners' plan
-- no longer covers it)
ALTER TABLE vomatt.users ADD COLUMN IF NOT EXISTS locked_at TIMESTAMPTZ;
-- Set when a downgrade leaves the user owning more community accounts than the plan allows: if they have not chosen
-- which to keep by then, the earliest created are kept and the rest locked
ALTER TABLE vomatt.users ADD COLUMN IF NOT EXISTS community_selection_deadline TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_users_community_selection_deadline ON vomatt.users (community_selection_deadline)
    WHERE community_selection_deadline IS NOT NULL;
