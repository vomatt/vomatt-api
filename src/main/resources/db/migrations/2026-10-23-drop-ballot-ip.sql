-- =============================================================================
-- Migration: drop user_votes.ip_address
--
-- Run: psql "$DB" -v ON_ERROR_STOP=1 -f 2026-10-23-drop-ballot-ip.sql
-- Re-runnable: a no-op once the column is gone (V2__catch_up.sql carries the same statement).
--
-- Why: the client IP was stored on every Ballot and never read. It is personal data with no purpose, and joined
-- with audit_logs.ip_address it would tie an Anonymous Ballot back to a signed-in user.
-- =============================================================================

ALTER TABLE vomatt.user_votes DROP COLUMN IF EXISTS ip_address;
