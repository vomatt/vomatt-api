-- Why a community account is locked (USER_CHOICE or SELECTION_DEADLINE), NULL while unlocked. The COMMUNITY_LOCKED
-- notification reads it with locked_at instead of the audit log, which may be disabled or pruned. Safe to rerun.
ALTER TABLE vomatt.users ADD COLUMN IF NOT EXISTS lock_reason VARCHAR(30);

-- The current lock of each locked account: its latest LOCK audit entry. An account with none left keeps NULL
CREATE TEMP TABLE current_lock ON COMMIT DROP AS
SELECT DISTINCT ON (a.resource_id) a.id, CAST(a.resource_id AS uuid) AS community_id, substring(a.details FROM '^reason=(.*)$') AS reason
FROM vomatt.audit_logs a JOIN vomatt.users u ON CAST(u.id AS text) = a.resource_id AND u.locked_at IS NOT NULL
WHERE a.action = 'LOCK' AND a.resource_type = 'COMMUNITY'
ORDER BY a.resource_id, a.created_at DESC, a.id DESC;

UPDATE vomatt.users u SET lock_reason = c.reason
FROM current_lock c WHERE u.id = c.community_id AND u.lock_reason IS NULL;

-- COMMUNITY_LOCKED read state was keyed by the LOCK audit entry, now by the community account: a read current lock
-- stays read; reads of earlier locks are dropped
INSERT INTO vomatt.notification_reads (user_id, kind, source_id, read_at)
SELECT r.user_id, r.kind, c.community_id, r.read_at
FROM vomatt.notification_reads r JOIN current_lock c ON c.id = r.source_id
WHERE r.kind = 'COMMUNITY_LOCKED'
ON CONFLICT DO NOTHING;
DELETE FROM vomatt.notification_reads r
WHERE r.kind = 'COMMUNITY_LOCKED' AND EXISTS (SELECT 1 FROM vomatt.audit_logs a WHERE a.id = r.source_id);

DROP TABLE current_lock;
