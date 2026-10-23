-- =============================================================================
-- Option vote count and Turnout reconciliation (run manually; not part of startup)
-- vote_options.vote_count and votes.turnout are atomically incremented/decremented by the application; this file checks and fixes drift from the actual user_votes row count.
-- =============================================================================

-- Option counts are checked for Polls that have not Ended only: once a Poll ends, RetentionJob seals its Anonymous
-- Ballots (option_id = NULL), so their rows no longer say which option they count for and vote_count is the record.
-- 1) Check: list options whose stored count differs from the actual Ballot count (should normally return 0 rows)
SELECT o.id AS option_id, o.vote_id, o.vote_count AS stored, COUNT(uv.id) AS actual
FROM vomatt.vote_options o
JOIN vomatt.votes v ON v.id = o.vote_id AND v.end_time > now()
LEFT JOIN vomatt.user_votes uv ON uv.option_id = o.id
GROUP BY o.id
HAVING o.vote_count <> COUNT(uv.id);

-- 2) Fix: overwrite with the actual count
UPDATE vomatt.vote_options o
SET vote_count = c.actual
FROM (SELECT o2.id, COUNT(uv.id) AS actual
      FROM vomatt.vote_options o2
      JOIN vomatt.votes v ON v.id = o2.vote_id AND v.end_time > now()
      LEFT JOIN vomatt.user_votes uv ON uv.option_id = o2.id
      GROUP BY o2.id) c
WHERE c.id = o.id AND o.vote_count <> c.actual;

-- 3) Check: Polls whose stored Turnout (votes.turnout) differs from the actual Participant count (should normally return 0 rows)
SELECT v.id AS vote_id, v.turnout AS stored, COUNT(uv.id) AS actual
FROM vomatt.votes v
LEFT JOIN vomatt.user_votes uv ON uv.vote_id = v.id
GROUP BY v.id
HAVING v.turnout <> COUNT(uv.id);

-- 4) Fix: overwrite Turnout with the actual count
UPDATE vomatt.votes v
SET turnout = c.actual
FROM (SELECT v2.id, COUNT(uv.id) AS actual
      FROM vomatt.votes v2
      LEFT JOIN vomatt.user_votes uv ON uv.vote_id = v2.id
      GROUP BY v2.id) c
WHERE c.id = v.id AND v.turnout <> c.actual;
