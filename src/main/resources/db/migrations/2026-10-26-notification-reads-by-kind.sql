-- =============================================================================
-- Migration: key notification read state by notification (kind + source row) instead of by Poll
--
-- Run: psql "$DB" -v ON_ERROR_STOP=1 -f 2026-10-26-notification-reads-by-kind.sql
-- Run it before or after the Flyway migrations (formerly schema.sql); the API needs both. Re-runnable: does nothing once vote_id is gone.
--
-- Why: the notification feed (ADR 0005) has kinds that are not about one Poll (follows, Comments), so a read is
-- stored per notification. One row per (user, Poll) used to cover both of the Poll's notifications, told apart by
-- read_at: read before the Poll's end it was the Invitation, at or after it the Ended Notification (now RESULT).
--
-- Rule: a row read at or after the Poll's end becomes RESULT of that Poll; one read before becomes INVITATION of the
--   user's Invitee row of that Poll. A row read before the end with no Invitee row left (the user was removed) or
--   whose Poll is gone named no notification any more and is dropped. read_at is kept.
-- =============================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'vomatt' AND table_name = 'notification_reads' AND column_name = 'vote_id') THEN
        RETURN;
    END IF;
    ALTER TABLE vomatt.notification_reads ADD COLUMN kind VARCHAR(20), ADD COLUMN source_id UUID;

    UPDATE vomatt.notification_reads r SET kind = 'RESULT', source_id = r.vote_id
    FROM vomatt.votes v
    WHERE v.id = r.vote_id AND r.read_at >= v.end_time;

    UPDATE vomatt.notification_reads r SET kind = 'INVITATION', source_id = i.id
    FROM vomatt.votes v, vomatt.vote_invitees i
    WHERE v.id = r.vote_id AND r.read_at < v.end_time AND i.vote_id = r.vote_id AND i.user_id = r.user_id;

    DELETE FROM vomatt.notification_reads WHERE kind IS NULL;

    ALTER TABLE vomatt.notification_reads DROP CONSTRAINT notification_reads_pkey;
    -- Drops its foreign key and idx_notification_reads_vote with it
    ALTER TABLE vomatt.notification_reads DROP COLUMN vote_id;
    ALTER TABLE vomatt.notification_reads ALTER COLUMN kind SET NOT NULL, ALTER COLUMN source_id SET NOT NULL;
    ALTER TABLE vomatt.notification_reads ADD PRIMARY KEY (user_id, kind, source_id);
END $$;
