-- Phase 2: 更多投票類型

-- votes 表新增 vote_type 欄位（現有投票預設為 STANDARD）
ALTER TABLE votes ADD COLUMN vote_type VARCHAR(20) NOT NULL DEFAULT 'STANDARD';

-- vote_options 表新增 image_url 欄位（IMAGE 類型使用）
ALTER TABLE vote_options ADD COLUMN image_url VARCHAR(500);

-- 建立 user_rankings 表（RANKING 類型專用）
CREATE TABLE IF NOT EXISTS user_rankings (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    vote_id VARCHAR(36) NOT NULL,
    option_id VARCHAR(36) NOT NULL,
    rank_position INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    FOREIGN KEY (vote_id) REFERENCES votes (id) ON DELETE CASCADE,
    FOREIGN KEY (option_id) REFERENCES vote_options (id) ON DELETE CASCADE,
    UNIQUE(user_id, vote_id, option_id)
);

-- 建立 user_ratings 表（RATING 類型專用）
CREATE TABLE IF NOT EXISTS user_ratings (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    vote_id VARCHAR(36) NOT NULL,
    score INTEGER NOT NULL CHECK (score >= 1 AND score <= 5),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    FOREIGN KEY (vote_id) REFERENCES votes (id) ON DELETE CASCADE,
    UNIQUE(user_id, vote_id)
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_votes_vote_type ON votes(vote_type);
CREATE INDEX IF NOT EXISTS idx_user_rankings_user_vote ON user_rankings(user_id, vote_id);
CREATE INDEX IF NOT EXISTS idx_user_rankings_vote_id ON user_rankings(vote_id);
CREATE INDEX IF NOT EXISTS idx_user_ratings_user_vote ON user_ratings(user_id, vote_id);
CREATE INDEX IF NOT EXISTS idx_user_ratings_vote_id ON user_ratings(vote_id);
