-- One report per user per Poll; read only by admins (GET /api/admin/vote-reports)
CREATE TABLE IF NOT EXISTS vomatt.vote_reports (
    id          UUID         PRIMARY KEY,
    vote_id     UUID         NOT NULL REFERENCES vomatt.votes (id) ON DELETE CASCADE,
    reporter_id UUID         NOT NULL REFERENCES vomatt.users (id) ON DELETE CASCADE,
    reason      VARCHAR(20)  NOT NULL,
    note        VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (vote_id, reporter_id)
);

-- Admin report list, newest first (VoteReportRepository.findPage); reporter FK cascade
CREATE INDEX IF NOT EXISTS idx_vote_reports_created  ON vomatt.vote_reports (created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_vote_reports_reporter ON vomatt.vote_reports (reporter_id);
