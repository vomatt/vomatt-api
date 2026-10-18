-- =============================================================================
-- Migration: Polls created with the legacy anonymous flag get Voter Visibility NOBODY
--
-- Run: psql "$DB" -v ON_ERROR_STOP=1 -f 2026-10-18-legacy-anonymous-poll.sql
-- Re-runnable: a second run changes nothing.
--
-- Rule: a Poll created or edited with only `anonymous: true` kept the default OWNER, so its owner still saw
--   who voted for what. Such a Poll becomes NOBODY; an explicit SIGNED_IN is a later choice and is kept.
--   is_anonymous is then synced to mean exactly "voter_visibility is NOBODY", as the API now writes it.
-- =============================================================================

UPDATE vomatt.votes
SET voter_visibility = 'NOBODY'
WHERE is_anonymous AND voter_visibility = 'OWNER';

UPDATE vomatt.votes
SET is_anonymous = (voter_visibility = 'NOBODY')
WHERE is_anonymous <> (voter_visibility = 'NOBODY');
