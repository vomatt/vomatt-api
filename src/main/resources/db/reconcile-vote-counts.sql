-- =============================================================================
-- Option vote count reconciliation (run manually; not part of startup)
-- vote_options.vote_count is atomically incremented/decremented by the application; this file checks and fixes drift from the actual user_votes row count.
-- =============================================================================

-- 1) Check: list options whose stored count differs from the actual Ballot count (should normally return 0 rows)
SELECT o.id AS option_id, o.vote_id, o.vote_count AS stored, COUNT(uv.id) AS actual
FROM vomatt.vote_options o
LEFT JOIN vomatt.user_votes uv ON uv.option_id = o.id
GROUP BY o.id
HAVING o.vote_count <> COUNT(uv.id);

-- 2) Fix: overwrite with the actual count
UPDATE vomatt.vote_options o
SET vote_count = c.actual
FROM (SELECT o2.id, COUNT(uv.id) AS actual
      FROM vomatt.vote_options o2
      LEFT JOIN vomatt.user_votes uv ON uv.option_id = o2.id
      GROUP BY o2.id) c
WHERE c.id = o.id AND o.vote_count <> c.actual;
