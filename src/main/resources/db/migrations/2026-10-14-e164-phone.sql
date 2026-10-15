-- =============================================================================
-- Migration: store users.phone_number as E.164 (User.normalizePhone)
--
-- Run: psql "$DB" -v ON_ERROR_STOP=1 -f 2026-10-14-e164-phone.sql
-- Re-runnable: rows already in E.164 are left alone.
--
-- Rule: a Taiwan mobile number stored as 09xxxxxxxx becomes +8869xxxxxxxx (drop the leading 0, prefix +886).
--   A row whose converted number already belongs to another user is skipped and keeps its old value,
--   since phone_number is UNIQUE and the two accounts must be merged by hand.
--   The final SELECT lists those collisions; it returns no rows once none are left.
-- =============================================================================

UPDATE vomatt.users u
SET phone_number = '+886' || substr(u.phone_number, 2)
WHERE u.phone_number ~ '^09[0-9]{8}$'
  AND NOT EXISTS (SELECT 1 FROM vomatt.users o WHERE o.phone_number = '+886' || substr(u.phone_number, 2));

SELECT u.id AS legacy_user_id, u.phone_number AS legacy_phone, o.id AS e164_user_id, o.phone_number AS e164_phone
FROM vomatt.users u
JOIN vomatt.users o ON o.phone_number = '+886' || substr(u.phone_number, 2)
WHERE u.phone_number ~ '^09[0-9]{8}$'
ORDER BY u.phone_number;
