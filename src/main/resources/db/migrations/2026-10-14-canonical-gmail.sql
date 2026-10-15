-- =============================================================================
-- Canonical Gmail addresses: one inbox maps to exactly one account
--
-- Canonical rule (same as User.normalizeEmail): trim and lower-case the address; when the domain is gmail.com or
-- googlemail.com, drop everything from the first + in the local part, remove every dot from the local part and
-- use the domain gmail.com. Other domains only keep the trim and lower-case.
--   e.g. A.B+news@GoogleMail.com -> ab@gmail.com
--
-- Run: psql "$DB" -v ON_ERROR_STOP=1 -f 2026-10-14-canonical-gmail.sql
--   on the direct (non-pooled) connection: the helper function lives in pg_temp, so every statement needs one session.
--   Run it right after deploying the canonical normalization. Until then, a non-canonical Gmail user signing in gets a
--   new account under the canonical address; such pairs show up in the final SELECT.
-- Re-runnable: rows already canonical are skipped. A row is rewritten only when no other row under the same unique
-- constraint has the same canonical form (users: email; vote_invitees: vote_id + email; group_members: group_id + email;
-- vote_invitation_emails: vote_id + email). Colliding rows stay unchanged; the final SELECT lists the colliding users
-- for manual handling (merge or change one of the accounts, then run this file again).
-- =============================================================================

CREATE OR REPLACE FUNCTION pg_temp.canonical_email(e text) RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE WHEN substring(lower(trim(e)) FROM '@([^@]*)$') IN ('gmail.com', 'googlemail.com')
                THEN replace(split_part(substring(lower(trim(e)) FROM '^(.*)@'), '+', 1), '.', '') || '@gmail.com'
                ELSE lower(trim(e)) END
$$;

UPDATE vomatt.users u
SET email = pg_temp.canonical_email(u.email), updated_at = now()
WHERE u.email <> pg_temp.canonical_email(u.email)
  AND NOT EXISTS (SELECT 1 FROM vomatt.users o
                  WHERE o.id <> u.id AND pg_temp.canonical_email(o.email) = pg_temp.canonical_email(u.email));

UPDATE vomatt.vote_invitees i
SET email = pg_temp.canonical_email(i.email), updated_at = now()
WHERE i.email <> pg_temp.canonical_email(i.email)
  AND NOT EXISTS (SELECT 1 FROM vomatt.vote_invitees o
                  WHERE o.id <> i.id AND o.vote_id = i.vote_id
                    AND pg_temp.canonical_email(o.email) = pg_temp.canonical_email(i.email));

UPDATE vomatt.group_members m
SET email = pg_temp.canonical_email(m.email), updated_at = now()
WHERE m.email <> pg_temp.canonical_email(m.email)
  AND NOT EXISTS (SELECT 1 FROM vomatt.group_members o
                  WHERE o.id <> m.id AND o.group_id = m.group_id
                    AND pg_temp.canonical_email(o.email) = pg_temp.canonical_email(m.email));

UPDATE vomatt.vote_invitation_emails s
SET email = pg_temp.canonical_email(s.email)
WHERE s.email <> pg_temp.canonical_email(s.email)
  AND NOT EXISTS (SELECT 1 FROM vomatt.vote_invitation_emails o
                  WHERE o.vote_id = s.vote_id AND o.email <> s.email
                    AND pg_temp.canonical_email(o.email) = pg_temp.canonical_email(s.email));

-- Colliding users: one canonical address, several accounts (left unchanged above)
SELECT pg_temp.canonical_email(email) AS canonical_email,
       array_agg(id ORDER BY created_at) AS user_ids,
       array_agg(email ORDER BY created_at) AS emails
FROM vomatt.users
WHERE email IS NOT NULL
GROUP BY pg_temp.canonical_email(email)
HAVING count(*) > 1
ORDER BY canonical_email;
