-- P0 效能優化：補充高頻查詢缺少的索引

-- 用於檢查使用者是否已對特定投票投票（複合查詢 vote_id + user_id）
CREATE INDEX IF NOT EXISTS idx_user_votes_vote_user
    ON user_votes(vote_id, user_id);

-- 用於查詢活躍投票列表（is_active + 時間範圍篩選）
CREATE INDEX IF NOT EXISTS idx_votes_active_time
    ON votes(is_active, start_time, end_time);

-- 用於標籤關聯查詢（tag_id 為主要篩選條件）
CREATE INDEX IF NOT EXISTS idx_vote_tags_tag_vote
    ON vote_tags(tag_id, vote_id);

-- 用於評論查詢（依投票 ID 分頁，按建立時間排序）
CREATE INDEX IF NOT EXISTS idx_vote_comments_vote_created
    ON vote_comments(vote_id, created_at DESC);
