-- LIKE notifications are derived from comment_likes (NotificationRepository). Likes given before the kind existed
-- were never shown, so they start read instead of all arriving unread at once. Idempotent.
INSERT INTO vomatt.notification_reads (user_id, kind, source_id, read_at)
SELECT c.user_id, 'LIKE', l.id, now()
FROM vomatt.comment_likes l
JOIN vomatt.vote_comments c ON c.id = l.comment_id
WHERE l.user_id <> c.user_id
ON CONFLICT (user_id, kind, source_id) DO NOTHING;
