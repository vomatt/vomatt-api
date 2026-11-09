-- Pro poll result report: one snapshot per Poll, computed once after it Ends and never rewritten (demographics may
-- change later). Aggregates only; never a user id or name. Written by PollReportService (first read or RetentionJob)
CREATE TABLE IF NOT EXISTS vomatt.poll_reports (
    vote_id      UUID        PRIMARY KEY REFERENCES vomatt.votes (id) ON DELETE CASCADE,
    generated_at TIMESTAMPTZ NOT NULL,
    data         JSONB       NOT NULL
);
