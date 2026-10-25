-- =============================================================================
-- Migration: remove follows of users the follower has blocked
--
-- Run: psql "$DB" -v ON_ERROR_STOP=1 -f 2026-10-25-unfollow-blocked.sql
-- Re-runnable: a no-op once no such follow is left.
--
-- Why: blocking a user now also unfollows them, and following a blocked user is refused (user.follow.blocked).
-- Follows made before that rule would otherwise show isFollowing and isBlocked both true.
-- =============================================================================

DELETE FROM vomatt.user_follows f
USING vomatt.user_blocks b
WHERE b.blocker_id = f.follower_id AND b.blocked_id = f.followed_id;
