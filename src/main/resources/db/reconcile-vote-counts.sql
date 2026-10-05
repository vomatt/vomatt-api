-- =============================================================================
-- 選項票數校正（手動執行；不在啟動流程中）
-- vote_options.vote_count 由應用層原子加減；此檔用來檢查並修正與 user_votes 實際筆數的落差。
-- =============================================================================

-- 1) 檢查：列出儲存值與實際 Ballot 數不一致的選項（正常應為 0 筆）
SELECT o.id AS option_id, o.vote_id, o.vote_count AS stored, COUNT(uv.id) AS actual
FROM vomatt.vote_options o
LEFT JOIN vomatt.user_votes uv ON uv.option_id = o.id
GROUP BY o.id
HAVING o.vote_count <> COUNT(uv.id);

-- 2) 修正：以實際筆數覆寫
UPDATE vomatt.vote_options o
SET vote_count = c.actual
FROM (SELECT o2.id, COUNT(uv.id) AS actual
      FROM vomatt.vote_options o2
      LEFT JOIN vomatt.user_votes uv ON uv.option_id = o2.id
      GROUP BY o2.id) c
WHERE c.id = o.id AND o.vote_count <> c.actual;
