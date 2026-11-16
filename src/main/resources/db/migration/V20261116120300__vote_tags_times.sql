-- Copies each Poll's start_time / end_time onto its vote_tags rows, so a tag-filtered list walks one tag's Polls in
-- time order on (tag_id, time, vote_id) instead of walking every Poll's time index and probing vote_tags per row.
-- Triggers keep the copies in sync: vote_tags is a plain @ManyToMany join table Hibernate fills with (vote_id, tag_id)
-- only, and every writer of a Poll's times (create, edit, Close, auto-end, native updates) is covered without app code.
ALTER TABLE vomatt.vote_tags ADD COLUMN IF NOT EXISTS start_time TIMESTAMPTZ;
ALTER TABLE vomatt.vote_tags ADD COLUMN IF NOT EXISTS end_time TIMESTAMPTZ;

CREATE OR REPLACE FUNCTION vomatt.vote_tags_copy_times() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    SELECT v.start_time, v.end_time INTO NEW.start_time, NEW.end_time FROM vomatt.votes v WHERE v.id = NEW.vote_id;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION vomatt.votes_sync_tag_times() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE vomatt.vote_tags SET start_time = NEW.start_time, end_time = NEW.end_time WHERE vote_id = NEW.id;
    RETURN NULL;
END $$;

DROP TRIGGER IF EXISTS vote_tags_copy_times ON vomatt.vote_tags;
CREATE TRIGGER vote_tags_copy_times BEFORE INSERT OR UPDATE OF vote_id ON vomatt.vote_tags
    FOR EACH ROW EXECUTE FUNCTION vomatt.vote_tags_copy_times();

-- Hibernate updates every column on flush; the WHEN clause skips the rewrite unless a time really changed
DROP TRIGGER IF EXISTS votes_sync_tag_times ON vomatt.votes;
CREATE TRIGGER votes_sync_tag_times AFTER UPDATE OF start_time, end_time ON vomatt.votes
    FOR EACH ROW WHEN (OLD.start_time IS DISTINCT FROM NEW.start_time OR OLD.end_time IS DISTINCT FROM NEW.end_time)
    EXECUTE FUNCTION vomatt.votes_sync_tag_times();

-- After the triggers exist, so a time change racing this backfill is not lost
UPDATE vomatt.vote_tags vt SET start_time = v.start_time, end_time = v.end_time
FROM vomatt.votes v
WHERE v.id = vt.vote_id AND (vt.start_time IS DISTINCT FROM v.start_time OR vt.end_time IS DISTINCT FROM v.end_time);

ALTER TABLE vomatt.vote_tags ALTER COLUMN start_time SET NOT NULL;
ALTER TABLE vomatt.vote_tags ALTER COLUMN end_time SET NOT NULL;

-- Keyset orders of VoteListRepository: start_time DESC (NEWEST, Following), end_time DESC (ENDED) and ASC (CLOSING, backward)
CREATE INDEX IF NOT EXISTS idx_vote_tags_tag_start ON vomatt.vote_tags (tag_id, start_time DESC, vote_id DESC);
CREATE INDEX IF NOT EXISTS idx_vote_tags_tag_end ON vomatt.vote_tags (tag_id, end_time DESC, vote_id DESC);
